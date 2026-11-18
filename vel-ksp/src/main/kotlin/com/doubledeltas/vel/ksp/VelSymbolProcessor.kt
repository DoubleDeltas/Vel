package com.doubledeltas.vel.ksp

import com.doubledeltas.vel.core.VelDiagnostic
import com.doubledeltas.vel.core.VelFrontend
import com.doubledeltas.vel.core.codegen.CodeGenerator
import com.doubledeltas.vel.core.codegen.ConcreteType
import com.doubledeltas.vel.core.codegen.JavaTypes
import com.doubledeltas.vel.core.codegen.KotlinPrinter
import com.doubledeltas.vel.core.codegen.VelCompileError
import com.doubledeltas.vel.core.type.Card
import com.doubledeltas.vel.core.type.DimSpec
import com.doubledeltas.vel.core.type.VelTypeException
import com.google.devtools.ksp.processing.CodeGenerator as KspCodeGenerator
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier

class VelSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        VelSymbolProcessor(environment.logger, environment.codeGenerator)
}

/**
 * Handles `@Vel` functions in Kotlin sources (style B, Kotlin counterpart of `VelProcessor`): checks
 * each VEL body against the function's Kotlin types and generates `<Owner>Vel`, a Kotlin class
 * implementing every `@Vel` function of the owner (kotlin.md).
 *
 * For an interface the class implements it; for an abstract class it extends it (through its
 * no-argument constructor). When the owner has other abstract members, the generated class is
 * abstract too. Supported Kotlin types for now: `Boolean`, `Byte`, `Short`, `Char`, `Int`, `Long`,
 * `Float`, `Double`, the unsigned `UByte`, `UShort`, `UInt`, `ULong`, their arrays (`DoubleArray`,
 * `UIntArray`, …; not `UShortArray`) and `Array`s of those, with `@Dim`. A nullable type `T?` is the
 * same VEL type as `T` (kotlin.md 1.3): a null value throws `NullPointerException`.
 */
class VelSymbolProcessor(
    private val logger: KSPLogger,
    private val codeGenerator: KspCodeGenerator,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val generated = LinkedHashMap<KSClassDeclaration, MutableList<String>>()
        val failed = HashSet<KSClassDeclaration>()
        for (symbol in resolver.getSymbolsWithAnnotation(VEL_ANNOTATION)) {
            val annotation = symbol.annotations.first { it.isAnnotation(VEL_ANNOTATION) }
            if (symbol !is KSFunctionDeclaration) {
                logger.error("@Vel can only be applied to functions", annotation)
                continue
            }
            val owner = symbol.parentDeclaration as? KSClassDeclaration
            val code = processFunction(symbol, annotation)
            if (owner != null) {
                generated.getOrPut(owner) { mutableListOf() }
                if (code == null) failed += owner else generated.getValue(owner) += code
            }
        }
        for ((owner, functions) in generated) {
            if (owner !in failed) writeClass(owner, functions)
        }
        return emptyList()
    }

    /** The Kotlin source of the overriding function, or null after reporting an error. */
    private fun processFunction(function: KSFunctionDeclaration, annotation: KSAnnotation): String? {
        if (!function.isAbstract) {
            logger.error("@Vel function must be abstract (its body is generated from VEL)", annotation)
            return null
        }
        val owner = function.parentDeclaration as? KSClassDeclaration
        val ownerIsAbstract = owner != null && (owner.classKind == ClassKind.INTERFACE
                || (owner.classKind == ClassKind.CLASS && Modifier.ABSTRACT in owner.modifiers))
        if (!ownerIsAbstract) {
            logger.error("@Vel function must be declared in an interface or abstract class", annotation)
            return null
        }
        if (function.typeParameters.isNotEmpty() || owner!!.typeParameters.isNotEmpty()) {
            logger.error("generic @Vel functions and owners are not supported yet", annotation)
            return null
        }

        val source = annotation.arguments.first { it.name?.asString() == "value" }.value as String
        val parsed = VelFrontend.parseProgram(source)
        for (d in parsed.diagnostics()) {
            when (d.severity()) {
                VelDiagnostic.Severity.ERROR -> logger.error(d.format(), annotation)
                VelDiagnostic.Severity.WARNING -> logger.warn(d.format(), annotation)
                null -> Unit
            }
        }
        if (parsed.hasErrors()) return null

        return try {
            val params = LinkedHashMap<String, ConcreteType>()
            val signature = StringBuilder()
            val prologue = StringBuilder()
            for (p in function.parameters) {
                val name = p.name!!.asString()
                val type = concreteType(p.type, p.annotations)
                params[name] = type
                val declared = render(p.type.resolve())
                if (signature.isNotEmpty()) signature.append(", ")
                signature.append(name).append(": ").append(declared)
                // adapt the declared type to the generated one: T? to T (null throws), UShort to Char
                val used = KotlinPrinter.type(codeType(type))
                if (declared != used) {
                    val value = if (declared.removeSuffix("?") == "kotlin.UShort") "($name as kotlin.UShort).toInt().toChar()"
                    else "$name as $used"
                    prologue.append("val ").append(name).append(": ").append(used).append(" = ").append(value).append('\n')
                }
            }
            val returnRef = function.returnType ?: throw VelTypeException("a @Vel function must declare its return type")
            val returnType = concreteType(returnRef, function.annotations)
            val declaredReturn = render(returnRef.resolve())
            val returned: (String) -> String =
                if (declaredReturn.removeSuffix("?") == "kotlin.UShort") { v -> "($v).code.toUShort()" } else { v -> v }
            val body = prologue.toString() +
                KotlinPrinter.functionBody(CodeGenerator.program(parsed.tree(), params, returnType), returned)

            val visibility = when {
                Modifier.PROTECTED in function.modifiers -> "protected "
                Modifier.INTERNAL in function.modifiers -> "internal "
                else -> ""
            }
            buildString {
                if (prologue.isNotEmpty()) append("    @Suppress(\"NAME_SHADOWING\", \"UNCHECKED_CAST\")\n")
                append("    ").append(visibility).append("override fun ").append(function.simpleName.asString())
                append('(').append(signature).append("): ").append(declaredReturn).append(" {\n")
                for (line in body.lines()) {
                    if (line.isNotEmpty()) append("        ").append(line)
                    append('\n')
                }
                setLength(length - 1) // body.lines() ends with an empty line
                append("    }\n")
            }
        } catch (e: VelCompileError) {
            logger.error(VelDiagnostic.error(e.line(), e.column(), e.message).format(), annotation)
            null
        } catch (e: VelTypeException) {
            logger.error("VEL: ${e.message}", function)
            null
        }
    }

    /** The VEL type of a Kotlin type (kotlin.md 1, 2): primitives and their arrays, sized by `@Dim`. */
    private fun concreteType(ref: KSTypeReference, annotations: Sequence<KSAnnotation>): ConcreteType {
        var type: KSType = ref.resolve()
        var rank = 0
        while (true) {
            // T? is the same VEL type as T (kotlin.md 1.3)
            val name = type.declaration.qualifiedName?.asString()
            if (name == "kotlin.Array") {
                type = type.arguments.single().type?.resolve() ?: throw VelTypeException("unsupported type $type")
                rank++
                continue
            }
            val primitive = PRIMITIVES[name]
            val array = PRIMITIVE_ARRAYS[name]
            val scalar = primitive ?: array
                ?: throw VelTypeException("the Kotlin type ${ref.resolve()} is not supported by VEL yet")
            if (array != null) rank++
            val dim = annotations.firstOrNull { it.isAnnotation(DIM_ANNOTATION) }
            if (rank == 0) {
                if (dim != null) throw VelTypeException("@Dim on the scalar type ${ref.resolve()}")
                return ConcreteType.scalar(scalar)
            }
            val dims: List<Card>? = dim?.let {
                DimSpec.fromAnnotation(it.ints("value"), it.ints("java"), it.ints("vel"))
            }
            return ConcreteType.javaArray(scalar, rank, dims)
        }
    }

    private fun KSAnnotation.ints(name: String): IntArray =
        when (val v = arguments.firstOrNull { it.name?.asString() == name }?.value) {
            null -> IntArray(0)
            is Int -> intArrayOf(v)
            is List<*> -> v.map { it as Int }.toIntArray()
            is IntArray -> v
            else -> throw VelTypeException("unexpected @Dim value $v")
        }

    /** A Kotlin type as written in a signature: qualified names, type arguments and nullability. */
    private fun render(t: KSType): String {
        val name = t.declaration.qualifiedName!!.asString()
        val args = if (t.arguments.isEmpty()) "" else
            t.arguments.joinToString(", ", "<", ">") { a -> a.type?.resolve()?.let(::render) ?: "*" }
        return name + args + if (t.isMarkedNullable) "?" else ""
    }

    private fun codeType(t: ConcreteType) =
        com.doubledeltas.vel.core.codegen.Code.Type(JavaTypes.javaName(t.element().resolution()), t.javaRank())

    private fun writeClass(owner: KSClassDeclaration, functions: List<String>) {
        val pkg = owner.packageName.asString()
        val name = generatedName(owner)
        val ownerName = owner.qualifiedName!!.asString()
        val velFunctions = functions.size
        val abstractMembers = owner.declarations.count {
            (it is KSFunctionDeclaration && it.isAbstract) || (it is KSPropertyDeclaration && it.isAbstract())
        }
        val complete = abstractMembers == velFunctions
        val supertype = if (owner.classKind == ClassKind.INTERFACE) ownerName else "$ownerName()"
        val text = buildString {
            if (pkg.isNotEmpty()) append("package ").append(pkg).append("\n\n")
            append("/** Generated by vel-ksp from the @Vel functions of [").append(ownerName).append("]. */\n")
            append(if (complete) "" else "abstract ").append("class ").append(name).append(" : ").append(supertype).append(" {\n")
            for (f in functions) append('\n').append(f)
            append("}\n")
        }
        val file = owner.containingFile
        val dependencies = if (file != null) Dependencies(false, file) else Dependencies(false)
        codeGenerator.createNewFile(dependencies, pkg, name).bufferedWriter().use { it.write(text) }
    }

    /** `PhysicsVel`; for a nested owner `Outer_InnerVel`. */
    private fun generatedName(owner: KSClassDeclaration): String {
        val names = ArrayList<String>()
        var d: KSClassDeclaration? = owner
        while (d != null) {
            names.add(0, d.simpleName.asString())
            d = d.parentDeclaration as? KSClassDeclaration
        }
        return names.joinToString("_") + "Vel"
    }

    private fun KSAnnotation.isAnnotation(qualifiedName: String): Boolean =
        annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName

    companion object {
        const val VEL_ANNOTATION = "com.doubledeltas.vel.Vel"
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
