package com.doubledeltas.vel.kotlinc

import com.doubledeltas.vel.core.VelDiagnostic
import com.doubledeltas.vel.core.VelFrontend
import com.doubledeltas.vel.core.codegen.Boundary
import com.doubledeltas.vel.core.codegen.CodeGenerator
import com.doubledeltas.vel.core.codegen.ConcreteType
import com.doubledeltas.vel.core.codegen.JavaTypes
import com.doubledeltas.vel.core.codegen.VelCompileError
import com.doubledeltas.vel.core.type.Card
import com.doubledeltas.vel.core.type.DimSpec
import com.doubledeltas.vel.core.type.VelTypeException
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Replaces calls to `VelInline.vel` with IR generated from the VEL expression (style C), after type
 * resolution.
 *
 * The Kotlin variables visible at the call (parameters, earlier local variables, lambda parameters)
 * of primitive or unsigned types or arrays of them (sized by `@Dim`) can be used from VEL; a nullable
 * `T?` is the same VEL type as `T` and is checked when read (kotlin.md 1.3). The result is
 * concretized to the type Kotlin inferred for the call (`val x: Float = vel(...)`).
 */
class VelIrGenerationExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (file in moduleFragment.files) {
            file.transformChildrenVoid(InlineRewriter(file, pluginContext))
        }
    }

    /** A visible Kotlin value, its VEL type (null when VEL cannot use it) and how it is read. */
    private class Binding(val declaration: IrValueDeclaration, val type: Boundary?, val adapter: IrEmitter.Adapter?)

    private object Bindings {
        fun of(declaration: IrValueDeclaration, type: Boundary?): Binding {
        val t = declaration.type
        val ushort = t.classFqName?.asString() == "kotlin.UShort"
        val adapter = when {
            ushort && t.isMarkedNullable() -> IrEmitter.Adapter.NULLABLE_USHORT
            ushort -> IrEmitter.Adapter.USHORT
            t.isMarkedNullable() -> IrEmitter.Adapter.NOT_NULL
            else -> null
        }
        return Binding(declaration, type, adapter)
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private class InlineRewriter(
        private val file: IrFile,
        private val context: IrPluginContext,
    ) : IrElementTransformerVoid() {

        private val reporter: IrDiagnosticReporter = context.diagnosticReporter
        private val scopes = ArrayDeque<MutableMap<String, Binding>>()
        private val functions = ArrayDeque<IrFunction>()

        override fun visitFunction(declaration: IrFunction): IrStatement {
            val params = HashMap<String, Binding>()
            for (p in declaration.parameters) {
                if (!p.name.isSpecial) params[p.name.asString()] = Bindings.of(p, boundary(p.type, p.annotations))
            }
            scopes.addLast(params)
            functions.addLast(declaration)
            try {
                return super.visitFunction(declaration)
            } finally {
                functions.removeLast()
                scopes.removeLast()
            }
        }

        override fun visitBlockBody(body: IrBlockBody): IrBody {
            scopes.addLast(HashMap())
            try {
                return super.visitBlockBody(body)
            } finally {
                scopes.removeLast()
            }
        }

        override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
            scopes.addLast(HashMap())
            try {
                return super.visitContainerExpression(expression)
            } finally {
                scopes.removeLast()
            }
        }

        override fun visitVariable(declaration: IrVariable): IrStatement {
            val result = super.visitVariable(declaration)
            if (!declaration.name.isSpecial) {
                scopes.lastOrNull()?.put(declaration.name.asString(),
                    Bindings.of(declaration, boundary(declaration.type, declaration.annotations)))
            }
            return result
        }

        override fun visitCall(expression: IrCall): IrExpression {
            if (expression.symbol.owner.callableId == VEL_INLINE) {
                return rewrite(expression) ?: expression
            }
            return super.visitCall(expression)
        }

        /** The generated IR for a `vel("...")` call, or null after reporting an error. */
        private fun rewrite(call: IrCall): IrExpression? {
            val arg = call.arguments[0]
            if (arg !is IrConst || arg.kind != IrConstKind.String) {
                report(arg ?: call, "vel(...) argument must be a string literal without templates")
                return null
            }
            val parsed = VelFrontend.parseExpression(arg.value as String)
            for (d in parsed.diagnostics()) {
                val factory = if (d.severity() == VelDiagnostic.Severity.WARNING) VelErrors.VEL_WARNING else VelErrors.VEL_ERROR
                reporter.at(arg, file).report(factory, d.format())
            }
            if (parsed.hasErrors()) return null
            val function = functions.lastOrNull()
            if (function == null) {
                report(call, "vel(...) can only be used inside a function")
                return null
            }
            val visible = HashMap<String, Binding>()
            for (scope in scopes) visible.putAll(scope) // outer first, inner wins
            val variables = LinkedHashMap<String, ConcreteType>()
            val codeNames = HashMap<String, String>()
            val values = HashMap<String, IrValueDeclaration>()
            val adapters = HashMap<String, IrEmitter.Adapter>()
            for ((name, binding) in visible) {
                val b = binding.type ?: continue
                variables[name] = b.type()
                values[name] = binding.declaration
                if (b.isDirect) binding.adapter?.let { adapters[name] = it } else codeNames[name] = name + "__v"
            }
            return try {
                val target = boundary(call.type, emptyList())
                val body = CodeGenerator.expression(parsed.tree(), variables, codeNames, target?.type())
                val reads = body.used().filter { it in codeNames }.map {
                    IrEmitter.Read(visible.getValue(it).type!!, visible.getValue(it).declaration, codeNames.getValue(it))
                }
                val write = if (target != null && !target.isDirect) IrEmitter.Write(target, call.type) else null
                IrEmitter(context, file, function, call.startOffset, call.endOffset, values, adapters)
                    .emit(body, toUShort = call.type.classFqName?.asString() == "kotlin.UShort", reads = reads, write = write)
            } catch (e: VelCompileError) {
                report(arg, VelDiagnostic.error(e.line(), e.column(), e.message).format())
                null
            } catch (e: VelTypeException) {
                report(arg, "VEL: ${e.message}")
                null
            }
        }

        private fun report(at: IrElement, message: String) {
            reporter.at(at, file).report(VelErrors.VEL_ERROR, message)
        }

        /**
         * The boundary of a Kotlin type (kotlin.md 1–3), sized by `@Dim`; null when VEL cannot use it.
         * Primitives, unsigned integers and their arrays are used as they are (a nullable one is checked
         * where it is read); `List`s and `Array`s of other values are sequences; `Pair`, `Triple` and data
         * classes are component objects.
         */
        private fun boundary(type: IrType, annotations: List<IrConstructorCall>): Boundary? {
            val b = try {
                boundaryOf(type, nested = false)
            } catch (e: VelTypeException) {
                null
            } ?: return null
            val dim = annotations.firstOrNull { it.type.classFqName?.asString() == DIM_ANNOTATION } ?: return b
            return try {
                Boundary.withDims(b, DimSpec.fromAnnotation(dim.ints("value"), dim.ints("java"), dim.ints("vel")))
            } catch (e: VelTypeException) {
                report(dim, "VEL: ${e.message}")
                null
            }
        }

        private fun boundaryOf(type: IrType, nested: Boolean): Boundary? {
            val name = type.classFqName?.asString() ?: return null
            PRIMITIVES[name]?.let { scalar ->
                return if (nested && type.isMarkedNullable()) Boundary.Boxed(scalar, JavaTypes.javaName(scalar.resolution()))
                else Boundary.Direct(listOf(), scalar)
            }
            PRIMITIVE_ARRAYS[name]?.let { return Boundary.Direct(listOf(Card.TOP), it) }
            val arguments = (type as? IrSimpleType)?.arguments ?: emptyList()
            if (name == "kotlin.Array") {
                val element = arguments.singleOrNull()?.typeOrNull ?: return null
                val inner = boundaryOf(element, nested = true) ?: return null
                if (inner is Boundary.Direct && inner.rank() > 0) return Boundary.Direct(inner.dims() + Card.TOP, inner.element())
                return Boundary.Sequence(Boundary.Sequence.Kind.ARRAY, inner, Card.TOP, name)
            }
            if (name in LISTS) {
                val element = arguments.singleOrNull()?.typeOrNull ?: return null
                return Boundary.Sequence(Boundary.Sequence.Kind.LIST, boundaryOf(element, nested = true) ?: return null, Card.TOP, name)
            }
            val cls = type.classOrNull?.owner ?: return null
            val functions = cls.declarations.filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrSimpleFunction>()
            val parts = ArrayList<Boundary.Part>()
            var n = 1
            while (true) {
                val f = functions.firstOrNull { it.name.asString() == "component$n" && it.parameters.size == 1 } ?: break
                var partType = f.returnType
                val parameter = partType.classifierOrNull as? org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
                if (parameter != null) {
                    val index = cls.typeParameters.indexOfFirst { it.symbol == parameter }
                    partType = arguments.getOrNull(index)?.typeOrNull ?: return null
                }
                parts.add(Boundary.Part("component$n()", boundaryOf(partType, nested = true) ?: return null))
                n++
            }
            return if (parts.isEmpty()) null else Boundary.Components(name, parts)
        }

        private fun IrConstructorCall.ints(name: String): IntArray {
            val index = symbol.owner.parameters.indexOfFirst { it.name.asString() == name }
            if (index < 0) return IntArray(0)
            return when (val v = arguments[index]) {
                null -> IntArray(0)
                is IrConst -> intArrayOf((v.value as Number).toInt())
                is IrVararg -> v.elements.map { ((it as IrConst).value as Number).toInt() }.toIntArray()
                else -> IntArray(0)
            }
        }
    }

    companion object {
        val VEL_INLINE = CallableId(
            ClassId(FqName("com.doubledeltas.vel"), Name.identifier("VelInline")),
            Name.identifier("vel"),
        )
        const val DIM_ANNOTATION = "com.doubledeltas.vel.Dim"

        private fun primitive(name: String) = JavaTypes.ofPrimitive(name)
        private fun unsigned(bits: Int) =
            com.doubledeltas.vel.core.concretization.ScalarType.integer(com.doubledeltas.vel.core.concretization.Resolution.unsigned(bits))

        /** Kotlin scalar types (kotlin.md 1.1, 1.2). */
        private val PRIMITIVES = mapOf(
            "kotlin.Boolean" to primitive("boolean"), "kotlin.Byte" to primitive("byte"), "kotlin.Short" to primitive("short"),
            "kotlin.Char" to primitive("char"), "kotlin.Int" to primitive("int"), "kotlin.Long" to primitive("long"),
            "kotlin.Float" to primitive("float"), "kotlin.Double" to primitive("double"),
            "kotlin.UByte" to unsigned(8), "kotlin.UShort" to unsigned(16), "kotlin.UInt" to unsigned(32),
            "kotlin.ULong" to unsigned(64),
        )

        private val LISTS = setOf("kotlin.collections.List", "kotlin.collections.MutableList", "java.util.ArrayList")

        /** Kotlin arrays of scalars. `UShortArray` is not supported: `Z+16` is held in `Char`. */
        private val PRIMITIVE_ARRAYS = mapOf(
            "kotlin.BooleanArray" to primitive("boolean"), "kotlin.ByteArray" to primitive("byte"),
            "kotlin.ShortArray" to primitive("short"), "kotlin.CharArray" to primitive("char"),
            "kotlin.IntArray" to primitive("int"), "kotlin.LongArray" to primitive("long"),
            "kotlin.FloatArray" to primitive("float"), "kotlin.DoubleArray" to primitive("double"),
            "kotlin.UByteArray" to unsigned(8), "kotlin.UIntArray" to unsigned(32), "kotlin.ULongArray" to unsigned(64),
        )
    }
}
