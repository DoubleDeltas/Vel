package com.doubledeltas.vel.kotlinc

import com.doubledeltas.vel.core.codegen.Code
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irLong
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhileLoopImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNotNull
import com.doubledeltas.vel.core.codegen.JavaTypes
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Builds Kotlin IR for target-neutral [Code] (the Kotlin IR backend of the concretizer).
 *
 * Arithmetic and comparisons call the members of the primitive classes (`Double.plus`, …), arrays are
 * the primitive array classes (`DoubleArray`) and `Array`s of them, loops are `while` loops, and
 * `VelMath` and `java.lang.Math` provide the helpers, as in the Java and Kotlin source backends.
 * The unsigned `ubyte uint ulong` are `UByte UInt ULong`, whose members are already unsigned.
 *
 * A value can be adapted where it is read ([Adapter]): a nullable Kotlin value is checked (`!!`), a
 * `UShort` is read as the `Char` that holds `Z+16`.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class IrEmitter(
    private val plugin: IrPluginContext,
    file: IrFile,
    private val function: IrFunction,
    private val start: Int,
    private val end: Int,
    values: Map<String, IrValueDeclaration>,
    private val adapters: Map<String, Adapter> = emptyMap(),
) {
    /** How a Kotlin value is read into the generated code. */
    enum class Adapter { NOT_NULL, USHORT, NULLABLE_USHORT }

    private val builtIns = plugin.irBuiltIns
    private val finder = plugin.finderForSource(file)
    private val builder = DeclarationIrBuilder(plugin, function.symbol, start, end)
    private val locals = HashMap(values)

    /**
     * The expression computing an inline VEL expression: its statements end with one Return (or Throw).
     * With [toUShort], a `Char` result (`Z+16`) is returned as the `UShort` the context expects.
     */
    fun emit(body: Code.Body, toUShort: Boolean = false): IrExpression {
        val last = body.statements().last()
        val resultType = if (last is Code.Return) irType(last.value().type()) else builtIns.nothingType
        val type = if (toUShort && last is Code.Return) irType(Code.Type.scalar("ushort")) else resultType
        val block = builder.irBlock(resultType = type) {
            if (toUShort && last is Code.Return) {
                statements(body.statements().dropLast(1))
                +charToUShort(expr(last.value()))
            } else {
                statements(body.statements())
            }
        }
        if (!body.guarded()) return block
        return IrTryImpl(start, end, type, block, listOf(
            outOfRange("IndexOutOfBoundsException"),
            outOfRange("NegativeArraySizeException"),
        ), null)
    }

    /** `catch (e: java.lang.<name>) { throw VelMath.outOfRange(e) }` (java.md 2.2). */
    private fun outOfRange(name: String): IrCatch {
        val exception = finder.findClass(ClassId(FqName("java.lang"), Name.identifier(name)))!!
        val e = buildVariable(function, start, end, IrDeclarationOrigin.CATCH_PARAMETER, Name.identifier("e"),
            exception.defaultType, isVar = false, isConst = false, isLateinit = false)
        val report = builder.irCall(staticFunction(VEL_MATH, "outOfRange", 1)).apply { arguments[0] = builder.irGet(e) }
        return IrCatchImpl(start, end, e, IrThrowImpl(start, end, builtIns.nothingType, report), null)
    }

    // ------------------------------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------------------------------

    private fun IrStatementsBuilder<*>.statements(list: List<Code.Stmt>) {
        for (s in list) statement(s)
    }

    private fun IrStatementsBuilder<*>.statement(s: Code.Stmt) {
        when (s) {
            is Code.Let -> locals[s.name()] = irTemporary(expr(s.init()), s.name(), irType(s.type()), isMutable = false)
            is Code.Var -> locals[s.name()] = irTemporary(expr(s.init()), s.name(), irType(s.type()), isMutable = true)
            is Code.Declare -> locals[s.name()] = irTemporary(null, s.name(), irType(s.type()), isMutable = true)
            is Code.Assign -> +irSet(locals.getValue(s.name()), expr(s.value()))
            is Code.Store -> +member(s.array().type(), "set", expr(s.array()), expr(s.index()), expr(s.value()), resultType = builtIns.unitType)
            is Code.For -> {
                val n = irTemporary(expr(s.length()), "n", builtIns.intType)
                val i = irTemporary(irInt(0), s.index(), builtIns.intType, isMutable = true)
                locals[s.index()] = i
                val loop = IrWhileLoopImpl(start, end, builtIns.unitType, null)
                loop.condition = compare(builtIns.lessFunByOperandType, builtIns.intType, irGet(i), irGet(n))
                loop.body = irBlock(resultType = builtIns.unitType) {
                    statements(s.body())
                    +irSet(i, member(Code.Type.INT, "plus", irGet(i), irInt(1)))
                }
                +loop
            }
            is Code.If -> {
                val then = irBlock(resultType = builtIns.unitType) { statements(s.then()) }
                +(if (s.otherwise().isEmpty()) irIfThen(builtIns.unitType, expr(s.condition()), then)
                else irIfThenElse(builtIns.unitType, expr(s.condition()), then,
                    irBlock(resultType = builtIns.unitType) { statements(s.otherwise()) }))
            }
            is Code.Return -> +expr(s.value())
            is Code.Throw -> +throwVelException()
        }
    }

    private fun IrBuilderWithScope.throwVelException(): IrExpression {
        val constructor = finder.findConstructors(ClassId(FqName(VEL_PACKAGE), Name.identifier("VelException")))
            .single { it.owner.parameters.isEmpty() }
        return IrThrowImpl(start, end, builtIns.nothingType, irCallConstructor(constructor, emptyList()))
    }

    // ------------------------------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------------------------------

    private fun IrBuilderWithScope.expr(e: Code.Expr): IrExpression = when (e) {
        is Code.Lit -> literal(e)
        is Code.Ref -> read(e)
        is Code.Binary -> binary(e)
        is Code.Neg -> member(e.type(), "unaryMinus", expr(e.operand()))
        is Code.Convert -> convert(expr(e.operand()), e.operand().type().primitive(), e.type().primitive())
        is Code.Call -> call(e)
        is Code.Choose -> irIfThenElse(irType(e.type()), expr(e.condition()), expr(e.ifTrue()), expr(e.ifFalse()))
        is Code.Fail -> irCall(staticFunction(VEL_MATH, "error", 0), irType(e.type()))
            .apply { typeArguments[0] = irType(e.type()) }
        is Code.Get -> member(e.array().type(), "get", expr(e.array()), expr(e.index()), resultType = irType(e.type()))
        is Code.Length -> {
            val size = finder.findProperties(CallableId(arrayClassId(e.array().type()), Name.identifier("size")))
                .single().owner.getter!!.symbol
            irCall(size).apply { arguments[0] = expr(e.array()) }
        }
        is Code.NewArray -> newArray(e.type(), e.lengths())
    }

    private fun IrBuilderWithScope.read(r: Code.Ref): IrExpression {
        val value: IrExpression = irGet(locals[r.name()] ?: error("VEL: unknown generated name ${r.name()}"))
        val adapter = adapters[r.name()] ?: return value
        val nonNull = if (adapter == Adapter.USHORT) value else
            irCall(builtIns.checkNotNullSymbol, value.type.makeNotNull()).apply {
                typeArguments[0] = value.type.makeNotNull()
                arguments[0] = value
            }
        return if (adapter == Adapter.NOT_NULL) nonNull
        else member(Code.Type.INT, "toChar", member(Code.Type.scalar("ushort"), "toInt", nonNull))
    }

    /** A `Char` result as the `UShort` the context expects. */
    fun IrBuilderWithScope.charToUShort(value: IrExpression): IrExpression =
        extension("toUShort", "int", convert(value, "char", "int"))

    private fun IrBuilderWithScope.literal(l: Code.Lit): IrExpression {
        val type = irType(l.type())
        return when (val v = l.value()) {
            is Boolean -> irBoolean(v)
            is BigDecimal -> if (l.type().primitive() == "float") IrConstImpl.float(start, end, type, v.toFloat())
            else IrConstImpl.double(start, end, type, v.toDouble())
            is BigInteger -> when (l.type().primitive()) {
                "uint" -> extension("toUInt", "int", irInt(v.toInt()))
                "ulong" -> extension("toULong", "long", irLong(v.toLong()))
                "ubyte" -> extension("toUByte", "int", irInt(v.toInt()))
                "int" -> irInt(v.toInt())
                "long" -> irLong(v.toLong())
                "short" -> IrConstImpl.short(start, end, type, v.toShort())
                "byte" -> IrConstImpl.byte(start, end, type, v.toByte())
                "char" -> IrConstImpl.char(start, end, type, v.toInt().toChar())
                "float" -> IrConstImpl.float(start, end, type, v.toFloat())
                else -> IrConstImpl.double(start, end, type, v.toDouble())
            }
            else -> error("VEL: unexpected literal $v")
        }
    }

    private fun IrBuilderWithScope.binary(b: Code.Binary): IrExpression {
        val l = expr(b.left())
        val r = expr(b.right())
        val operand = b.left().type()
        return when (b.op()!!) {
            Code.Op.ADD -> member(operand, "plus", l, r)
            Code.Op.SUB -> member(operand, "minus", l, r)
            Code.Op.MUL -> member(operand, "times", l, r)
            Code.Op.DIV -> member(operand, "div", l, r)
            Code.Op.REM -> member(operand, "rem", l, r)
            Code.Op.AND -> irIfThenElse(builtIns.booleanType, l, r, irFalse())
            Code.Op.OR -> irIfThenElse(builtIns.booleanType, l, irTrue(), r)
            Code.Op.XOR -> member(operand, "xor", l, r)
            Code.Op.EQ -> if (operand.isUnsigned) unsignedCompare(Code.Op.EQ, operand, l, r) else irEquals(l, r)
            Code.Op.NE -> if (operand.isUnsigned) unsignedCompare(Code.Op.NE, operand, l, r) else irNotEquals(l, r)
            else -> if (operand.isUnsigned) unsignedCompare(b.op()!!, operand, l, r) else ordered(b.op()!!, operand, l, r)
        }
    }

    /** `a.compareTo(b) op 0`: the members of the unsigned classes compare as unsigned. */
    private fun IrBuilderWithScope.unsignedCompare(op: Code.Op, operand: Code.Type, l: IrExpression, r: IrExpression): IrExpression {
        val c = member(operand, "compareTo", l, r, resultType = builtIns.intType)
        return when (op) {
            Code.Op.EQ -> irEquals(c, irInt(0))
            Code.Op.NE -> irNotEquals(c, irInt(0))
            else -> ordered(op, Code.Type.INT, c, irInt(0))
        }
    }

    private fun IrBuilderWithScope.ordered(op: Code.Op, operand: Code.Type, l: IrExpression, r: IrExpression): IrExpression {
        val functions = when (op) {
            Code.Op.LT -> builtIns.lessFunByOperandType
            Code.Op.LE -> builtIns.lessOrEqualFunByOperandType
            Code.Op.GT -> builtIns.greaterFunByOperandType
            else -> builtIns.greaterOrEqualFunByOperandType
        }
        return ordered(functions, operand, l, r)
    }

    /** An ordering comparison; Byte and Short are compared as Int. */
    private fun IrBuilderWithScope.ordered(
        functions: Map<org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol, IrSimpleFunctionSymbol>,
        operand: Code.Type, l: IrExpression, r: IrExpression,
    ): IrExpression {
        if (operand.primitive() == "byte" || operand.primitive() == "short") {
            return compare(functions, builtIns.intType,
                convert(l, operand.primitive(), "int"), convert(r, operand.primitive(), "int"))
        }
        return compare(functions, irType(operand), l, r)
    }

    private fun IrBuilderWithScope.compare(
        functions: Map<org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol, IrSimpleFunctionSymbol>,
        type: IrType, l: IrExpression, r: IrExpression,
    ): IrExpression = irCall(functions.getValue(type.classOrNull!!), builtIns.booleanType).apply {
        arguments[0] = l
        arguments[1] = r
    }

    /** A primitive conversion with Java cast semantics, through `Int` where Kotlin has no direct one. */
    private fun IrBuilderWithScope.convert(value: IrExpression, from: String, to: String): IrExpression {
        if (from == to) return value
        if (JavaTypes.isUnsigned(from)) {
            // the unsigned classes convert to every numeric type as unsigned values
            if (to == "char") return member(Code.Type.INT, "toChar", member(Code.Type.scalar(from), "toInt", value))
            return member(Code.Type.scalar(from), "to" + kotlinName(to), value)
        }
        if (JavaTypes.isUnsigned(to)) {
            val source = if (from == "char") convert(value, "char", "int") else value
            return extension("to" + kotlinName(to), if (from == "char") "int" else from, source)
        }
        var v = value
        var f = from
        if (f == "char") {
            // Char.code is an extension property of the kotlin package
            val code = finder.findProperties(CallableId(FqName("kotlin"), Name.identifier("code")))
                .first { it.owner.getter?.parameters?.singleOrNull()?.type?.classFqName?.asString() == "kotlin.Char" }
                .owner.getter!!.symbol
            v = irCall(code).apply { arguments[0] = value }
            f = "int"
            if (to == "int") return v
        }
        val floating = f == "float" || f == "double"
        return when (to) {
            "char" -> member(Code.Type.INT, "toChar", if (f == "int") v else member(Code.Type.scalar(f), "toInt", v))
            "byte", "short" -> {
                val integral = if (floating) member(Code.Type.scalar(f), "toInt", v) else v
                member(Code.Type.scalar(if (floating) "int" else f), "to" + kotlinName(to), integral)
            }
            else -> member(Code.Type.scalar(f), "to" + kotlinName(to), v)
        }
    }

    private fun IrBuilderWithScope.call(c: Code.Call): IrExpression {
        val operand = c.args().first().type()
        if (operand.isUnsigned) {
            when (c.helper()!!) {
                Code.Helper.FLOOR_DIV -> return member(operand, "div", expr(c.args()[0]), expr(c.args()[1]))
                Code.Helper.FLOOR_MOD -> return member(operand, "rem", expr(c.args()[0]), expr(c.args()[1]))
                Code.Helper.INT_POW -> {
                    // repeated multiplication on the stored bits wraps like the unsigned type
                    val bits = JavaTypes.storage(operand.primitive())
                    val signed = member(operand, "to" + kotlinName(bits), expr(c.args()[0]))
                    val pow = irCall(staticFunction(c.helper().owner(), c.helper().method(), 2,
                        listOf(Code.Type.scalar(bits), Code.Type.INT))).apply {
                        arguments[0] = signed
                        arguments[1] = expr(c.args()[1])
                    }
                    return extension("to" + kotlinName(operand.primitive()), bits, pow)
                }
                else -> Unit
            }
        }
        return irCall(staticFunction(c.helper().owner(), c.helper().method(), c.args().size, c.args().map { it.type() }))
            .apply { c.args().forEachIndexed { i, a -> arguments[i] = expr(a) } }
    }

    /** A call to an extension function of the kotlin package (`Int.toUInt()`, …) on a primitive receiver. */
    private fun IrBuilderWithScope.extension(name: String, receiver: String, value: IrExpression): IrExpression {
        val receiverClass = FqName("kotlin." + kotlinName(receiver))
        val symbol = finder.findFunctions(CallableId(FqName("kotlin"), Name.identifier(name))).firstOrNull {
            val params = it.owner.parameters
            params.size == 1 && params[0].type.classFqName == receiverClass
        } ?: error("VEL: no extension $receiverClass.$name")
        return irCall(symbol).apply { arguments[0] = value }
    }

    // ------------------------------------------------------------------------------------------
    // Arrays
    // ------------------------------------------------------------------------------------------

    private fun IrBuilderWithScope.newArray(type: Code.Type, lengths: List<Code.Expr>): IrExpression {
        if (type.rank() == 1) {
            val constructor = finder.findConstructors(arrayClassId(type))
                .single { it.owner.parameters.size == 1 && it.owner.parameters[0].type.classFqName == FqName("kotlin.Int") }
            return irCallConstructor(constructor, emptyList()).apply { arguments[0] = expr(lengths.first()) }
        }
        // arrays of arrays: VelMath.newArray(descriptor, lengths...) cast to the array type
        if (type.isUnsigned) error("VEL: arrays of unsigned arrays are not supported by the Kotlin IR backend yet")
        val descriptor = when (type.primitive()) {
            "double" -> 'D'; "float" -> 'F'; "long" -> 'J'; "int" -> 'I'
            "short" -> 'S'; "byte" -> 'B'; "char" -> 'C'; else -> 'Z'
        }
        val intArray = builtIns.primitiveArrayForType.getValue(builtIns.intType).defaultType
        val create = irCall(staticFunction(VEL_MATH, "newArray", 2)).apply {
            arguments[0] = IrConstImpl.char(start, end, builtIns.charType, descriptor)
            arguments[1] = irVararg(builtIns.intType, lengths.map { expr(it) }).also { it.type = intArray }
        }
        return irAs(create, irType(type))
    }

    // ------------------------------------------------------------------------------------------
    // Members, helpers and types
    // ------------------------------------------------------------------------------------------

    /** A call to a member of a primitive or array class, chosen by the number and classes of its parameters. */
    private fun IrBuilderWithScope.member(
        receiverType: Code.Type, name: String, receiver: IrExpression, vararg args: IrExpression,
        resultType: IrType? = null,
    ): IrExpression {
        val classId = if (receiverType.rank() > 0) arrayClassId(receiverType) else scalarClassId(receiverType.primitive())
        val candidates = finder.findFunctions(CallableId(classId, Name.identifier(name))).filter {
            val params = it.owner.parameters.drop(1)
            params.size == args.size && (receiverType.rank() > 1 ||
                    params.zip(args).all { (p, a) -> p.type.classFqName == a.type.classFqName })
        }
        val symbol = candidates.firstOrNull() ?: error("VEL: no member $name on $classId for ${args.map { it.type.classFqName }}")
        val call = if (resultType != null) irCall(symbol, resultType) else irCall(symbol)
        call.arguments[0] = receiver
        args.forEachIndexed { i, a -> call.arguments[i + 1] = a }
        return call
    }

    /** A static method of a Java class, chosen by the number (and, when given, the types) of its parameters. */
    private fun staticFunction(owner: String, name: String, arity: Int, types: List<Code.Type>? = null): IrSimpleFunctionSymbol {
        val classId = ClassId.topLevel(FqName(owner))
        // Java static methods are not in the member scope searched by findFunctions: look in the class itself
        val cls = finder.findClass(classId) ?: error("VEL: class $owner is not on the classpath")
        val candidates = cls.owner.declarations.filterIsInstance<IrSimpleFunction>().filter {
            val params = it.parameters
            it.name.asString() == name && params.size == arity && (types == null ||
                    params.zip(types).all { (p, t) -> p.type.classFqName == irType(t).classFqName })
        }
        return candidates.firstOrNull()?.symbol ?: error("VEL: no static method $owner.$name for $types")
    }

    private fun irType(t: Code.Type): IrType {
        if (t.isUnsigned || t.primitive() == "ushort") {
            if (t.rank() > 1) error("VEL: arrays of unsigned arrays are not supported by the Kotlin IR backend yet")
            val name = kotlinName(t.primitive()) + if (t.rank() == 1) "Array" else ""
            return finder.findClass(ClassId(FqName("kotlin"), Name.identifier(name)))!!.defaultType
        }
        val scalar = when (t.primitive()) {
            "boolean" -> builtIns.booleanType
            "byte" -> builtIns.byteType
            "short" -> builtIns.shortType
            "char" -> builtIns.charType
            "int" -> builtIns.intType
            "long" -> builtIns.longType
            "float" -> builtIns.floatType
            "double" -> builtIns.doubleType
            else -> error("VEL: unexpected primitive ${t.primitive()}")
        }
        if (t.rank() == 0) return scalar
        var type: IrType = builtIns.primitiveArrayForType.getValue(scalar).defaultType
        repeat(t.rank() - 1) { type = builtIns.arrayClass.typeWith(type) }
        return type
    }

    private fun arrayClassId(t: Code.Type): ClassId =
        if (t.rank() == 1) ClassId(FqName("kotlin"), Name.identifier(kotlinName(t.primitive()) + "Array"))
        else ClassId(FqName("kotlin"), Name.identifier("Array"))

    private fun scalarClassId(primitive: String) = ClassId(FqName("kotlin"), Name.identifier(kotlinName(primitive)))

    private fun kotlinName(primitive: String) = when (primitive) {
        "ubyte" -> "UByte"
        "ushort" -> "UShort"
        "uint" -> "UInt"
        "ulong" -> "ULong"
        "boolean" -> "Boolean"
        "byte" -> "Byte"
        "short" -> "Short"
        "char" -> "Char"
        "int" -> "Int"
        "long" -> "Long"
        "float" -> "Float"
        "double" -> "Double"
        else -> error("VEL: unexpected primitive $primitive")
    }

    companion object {
        const val VEL_PACKAGE = "com.doubledeltas.vel"
        const val VEL_MATH = "com.doubledeltas.vel.VelMath"
    }
}
