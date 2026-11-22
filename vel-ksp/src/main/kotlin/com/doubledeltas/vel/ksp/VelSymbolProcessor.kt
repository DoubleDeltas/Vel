package com.doubledeltas.vel.ksp

import com.doubledeltas.vel.core.VelDiagnostic
import com.doubledeltas.vel.core.VelFrontend
import com.doubledeltas.vel.core.codegen.Boundary
import com.doubledeltas.vel.core.codegen.CodeGenerator
import com.doubledeltas.vel.core.codegen.KotlinBoundary
import com.doubledeltas.vel.core.codegen.ConcreteType
import com.doubledeltas.vel.core.codegen.JavaTypes
import com.doubledeltas.vel.core.codegen.KotlinPrinter
import com.doubledeltas.vel.core.codegen.MethodLookup
import com.doubledeltas.vel.core.codegen.VelCompileError
import com.doubledeltas.vel.core.type.Card
import com.doubledeltas.vel.core.type.DimSpec
import com.doubledeltas.vel.core.type.VelTypeException
import com.google.devtools.ksp.processing.CodeGenerator as KspCodeGenerator
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FunctionKind
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

    private lateinit var resolver: Resolver

    override fun process(resolver: Resolver): List<KSAnnotated> {
        this.resolver = resolver
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
            val boundaries = LinkedHashMap<String, Boundary>()
            val declared = HashMap<String, KSType>()
            val signature = StringBuilder()
            for (p in function.parameters) {
                val name = p.name!!.asString()
                val type = p.type.resolve()
                boundaries[name] = boundary(type, p.annotations)
                declared[name] = type
                if (signature.isNotEmpty()) signature.append(", ")
                signature.append(name).append(": ").append(render(type))
            }
            val returnRef = function.returnType ?: throw VelTypeException("a @Vel function must declare its return type")
            val result = boundary(returnRef.resolve(), function.annotations)
            val declaredReturn = render(returnRef.resolve())

            // a parameter that is not used as it is gets converted into the local name__v
            val types = LinkedHashMap<String, ConcreteType>()
            val codeNames = HashMap<String, String>()
            for ((name, b) in boundaries) {
                types[name] = b.type()
                if (!b.isDirect || render(declared.getValue(name)).replace("kotlin.", "") != KotlinPrinter.type(codeType(b.type()))) {
                    codeNames[name] = name + "__v"
                }
            }
            val code = CodeGenerator.program(parsed.tree(), types, codeNames, result.type(), MethodLookup(::staticMethods))
            val prologue = StringBuilder()
            for (name in code.used()) {
                val local = codeNames[name] ?: continue
                prologue.append(read(boundaries.getValue(name), declared.getValue(name), name, local))
            }
            val returned: (String) -> String = { v -> v }
            val resultBlock: ((String) -> String)? =
                if (result.isDirect) null else { v -> KotlinBoundary.write(result, v, "result") }
            val body = KotlinPrinter.functionBody(code, prologue.toString(), returned, resultBlock)

            val visibility = when {
                Modifier.PROTECTED in function.modifiers -> "protected "
                Modifier.INTERNAL in function.modifiers -> "internal "
                else -> ""
            }
            buildString {
                append("    @Suppress(\"UNCHECKED_CAST\")\n")
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

    /** Statements declaring [local], the value of the parameter [name] converted for the generated code. */
    private fun read(b: Boundary, declared: KSType, name: String, local: String): String {
        val used = KotlinPrinter.type(codeType(b.type()))
        if (b.isDirect) {
            // T? to T (null throws), nested nullable arrays (unchecked)
            return "val $local: $used = $name as $used\n"
        }
        return KotlinBoundary.read(b, if (declared.isMarkedNullable) "$name!!" else name, local)
    }

    /**
     * The boundary of a Kotlin type (kotlin.md 1–3), sized by `@Dim`: primitives, unsigned integers and
     * their arrays as they are; nullable primitives unboxed (`T?` is the same VEL type as `T`); `List`s
     * and `Array`s of other values as sequences; `Pair`, `Triple` and data classes (`componentN()`) as
     * component objects.
     */
    private fun boundary(type: KSType, annotations: Sequence<KSAnnotation>): Boundary {
        val b = boundaryOf(type)
        val dim = annotations.firstOrNull { it.isAnnotation(DIM_ANNOTATION) }
        if (dim != null && b is Boundary.Direct && b.rank() == 0) throw VelTypeException("@Dim on the scalar type $type")
        val dims: List<Card>? = dim?.let { DimSpec.fromAnnotation(it.ints("value"), it.ints("java"), it.ints("vel")) }
        return Boundary.withDims(b, dims)
    }

    private fun boundaryOf(type: KSType): Boundary {
        val name = type.declaration.qualifiedName?.asString()
            ?: throw VelTypeException("the Kotlin type $type is not supported by VEL yet")
        val representation = REPRESENTATIONS[name]
        PRIMITIVES[name]?.let { scalar ->
            return if (type.isMarkedNullable) Boundary.Boxed(scalar, representation ?: JavaTypes.javaName(scalar.resolution()))
            else Boundary.Direct(listOf(), scalar, representation)
        }
        PRIMITIVE_ARRAYS[name]?.let { return Boundary.Direct(listOf(Card.fresh()), it, representation) }
        if (name == "kotlin.Array") {
            val element = type.arguments.single().type?.resolve() ?: throw VelTypeException("unsupported type $type")
            val inner = boundaryOf(element)
            // Array<DoubleArray> is a primitive array of rank 2; Array<Double> holds boxed values
            if (inner is Boundary.Direct && inner.rank() > 0) { // nullable rows are cast (null throws when read)
                return Boundary.Direct(inner.dims() + Card.fresh(), inner.element(), inner.representation())
            }
            return Boundary.Sequence(Boundary.Sequence.Kind.ARRAY, inner, Card.fresh(), render(type.makeNotNullable()))
        }
        if (name in LISTS) {
            val element = type.arguments.single().type?.resolve() ?: throw VelTypeException("unsupported type $type")
            return Boundary.Sequence(Boundary.Sequence.Kind.LIST, boundaryOf(element), Card.fresh(), render(type.makeNotNullable()))
        }
        val declaration = type.declaration as? KSClassDeclaration
        if (declaration != null) {
            val parts = ArrayList<Boundary.Part>()
            val functions = declaration.getAllFunctions().toList()
            var n = 1
            while (true) {
                val f = functions.firstOrNull { it.simpleName.asString() == "component$n" && it.parameters.isEmpty() } ?: break
                val returnType = f.asMemberOf(type.makeNotNullable()).returnType
                    ?: throw VelTypeException("component$n of $type has no type")
                parts.add(Boundary.Part("component$n()", boundaryOf(returnType)))
                n++
            }
            if (parts.isNotEmpty()) {
                return Boundary.Components(render(type.makeNotNullable()), parts)
            }
        }
        throw VelTypeException("the Kotlin type $type is not supported by VEL yet")
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

    /**
     * The public static methods of a Java class named in VEL by its qualified name or, for `java.lang`,
     * its simple name. Functions of Kotlin `object`s and companions are not found yet.
     * The signature `{…}` names parameters by Kotlin types (kotlin.md 4.1): `Double`, `DoubleArray`.
     */
    private fun staticMethods(owner: String, name: String): List<MethodLookup.Method>? {
        val cls = (if ('.' in owner) resolver.getClassDeclarationByName(resolver.getKSNameFromString(owner)) else null)
            ?: resolver.getClassDeclarationByName(resolver.getKSNameFromString("java.lang.$owner"))
            ?: return null
        return cls.declarations.filterIsInstance<KSFunctionDeclaration>()
            .filter {
                it.simpleName.asString() == name && it.functionKind == FunctionKind.STATIC && it.isPublic()
                        && it.typeParameters.isEmpty() && it.parameters.none { p -> p.isVararg }
            }
            .map { f ->
                MethodLookup.Method(
                    cls.qualifiedName!!.asString(), name,
                    f.parameters.map { p ->
                        val t = p.type.resolve()
                        MethodLookup.Param(render(t), direct(t))
                    },
                    f.returnType?.resolve()?.let(::direct),
                )
            }.toList()
    }

    /** The VEL type of a primitive or an array of one (Java `double` is `kotlin.Double`), or null. */
    private fun direct(t: KSType): ConcreteType? =
        try {
            (boundaryOf(t.makeNotNullable()) as? Boundary.Direct)?.type()
        } catch (e: VelTypeException) {
            null
        }

    private fun codeType(t: ConcreteType) =
        com.doubledeltas.vel.core.codegen.Code.Type(t.primitive(), t.javaRank())

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

        private val LISTS = setOf("kotlin.collections.List", "kotlin.collections.MutableList", "java.util.ArrayList",
            "kotlin.collections.ArrayList")

        /** `UShort` holds `Z+16` in the bits of a `Short` (the `ushort` representation), not in a `Char`. */
        private val REPRESENTATIONS = mapOf("kotlin.UShort" to "ushort", "kotlin.UShortArray" to "ushort")

        /** Kotlin arrays of scalars. */
        private val PRIMITIVE_ARRAYS = mapOf(
            "kotlin.BooleanArray" to primitive("boolean"), "kotlin.ByteArray" to primitive("byte"),
            "kotlin.ShortArray" to primitive("short"), "kotlin.CharArray" to primitive("char"),
            "kotlin.IntArray" to primitive("int"), "kotlin.LongArray" to primitive("long"),
            "kotlin.FloatArray" to primitive("float"), "kotlin.DoubleArray" to primitive("double"),
            "kotlin.UByteArray" to unsigned(8), "kotlin.UShortArray" to unsigned(16), "kotlin.UIntArray" to unsigned(32),
            "kotlin.ULongArray" to unsigned(64),
        )
    }
}
