package com.doubledeltas.vel.kotlinc

import com.doubledeltas.vel.core.VelDiagnostic
import com.doubledeltas.vel.core.VelFrontend
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
 * of primitive types or arrays of them (sized by `@Dim`) can be used from VEL. The result is
 * concretized to the type Kotlin inferred for the call (`val x: Float = vel(...)`).
 */
class VelIrGenerationExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (file in moduleFragment.files) {
            file.transformChildrenVoid(InlineRewriter(file, pluginContext))
        }
    }

    /** A visible Kotlin value and its VEL type (null when VEL cannot use it). */
    private class Binding(val declaration: IrValueDeclaration, val type: ConcreteType?)

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
                if (!p.name.isSpecial) params[p.name.asString()] = Binding(p, concreteType(p.type, p.annotations))
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
                    Binding(declaration, concreteType(declaration.type, declaration.annotations)))
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
            val values = HashMap<String, IrValueDeclaration>()
            for ((name, binding) in visible) {
                val type = binding.type ?: continue
                variables[name] = type
                values[name] = binding.declaration
            }
            return try {
                val body = CodeGenerator.expression(parsed.tree(), variables, concreteType(call.type, emptyList(), target = true))
                IrEmitter(context, file, function, call.startOffset, call.endOffset, values).emit(body)
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
         * The VEL type of a Kotlin type (kotlin.md 1, 2): primitives and their arrays, sized by `@Dim`;
         * null when VEL cannot use it. For the expected type of a call, nullability (a platform type) is ignored.
         */
        private fun concreteType(type: IrType, annotations: List<IrConstructorCall>, target: Boolean = false): ConcreteType? {
            var t = type
            var rank = 0
            while (true) {
                if (!target && t.isMarkedNullable()) return null
                val name = t.classFqName?.asString() ?: return null
                if (name == "kotlin.Array") {
                    t = (t as? IrSimpleType)?.arguments?.singleOrNull()?.typeOrNull ?: return null
                    rank++
                    continue
                }
                val primitive = PRIMITIVES[name]
                val array = PRIMITIVE_ARRAYS[name]
                val element = primitive ?: array ?: return null
                if (array != null) rank++
                val scalar = JavaTypes.ofPrimitive(element)
                if (rank == 0) return ConcreteType.scalar(scalar)
                val dim = annotations.firstOrNull { it.type.classFqName?.asString() == DIM_ANNOTATION }
                val dims: List<Card>? = dim?.let {
                    try {
                        DimSpec.fromAnnotation(it.ints("value"), it.ints("java"), it.ints("vel"))
                    } catch (e: VelTypeException) {
                        report(it, "VEL: ${e.message}")
                        null
                    }
                }
                return try {
                    ConcreteType.javaArray(scalar, rank, dims)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
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

        private val PRIMITIVES = mapOf(
            "kotlin.Boolean" to "boolean", "kotlin.Byte" to "byte", "kotlin.Short" to "short",
            "kotlin.Char" to "char", "kotlin.Int" to "int", "kotlin.Long" to "long",
            "kotlin.Float" to "float", "kotlin.Double" to "double",
        )
        private val PRIMITIVE_ARRAYS = mapOf(
            "kotlin.BooleanArray" to "boolean", "kotlin.ByteArray" to "byte", "kotlin.ShortArray" to "short",
            "kotlin.CharArray" to "char", "kotlin.IntArray" to "int", "kotlin.LongArray" to "long",
            "kotlin.FloatArray" to "float", "kotlin.DoubleArray" to "double",
        )
    }
}
