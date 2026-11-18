package com.doubledeltas.vel.kotlinc

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
class VelCompilerPluginTest {

    @Test
    fun validInlineExpressionCompiles() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel
            fun ke(m: Double, v: Double): Double = vel("0.5 * m * v^2")
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun inlineExpressionIsReplacedByGeneratedIr() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel
            import com.doubledeltas.vel.Dim

            object Calc {
                @JvmStatic fun ke(m: Double, v: Double): Double {
                    val half = 0.5
                    return vel("half * m * v^2")
                }
                @JvmStatic fun fall(t: Float): Float {
                    val g = 9.8
                    val h: Float = vel("g * t^2 / 2")   // computed in F64, concretized to Float
                    return h
                }
                @JvmStatic fun step(@Dim(3) p: DoubleArray, @Dim(3) v: DoubleArray, dt: Double): DoubleArray =
                    vel("p + v * dt")
                @JvmStatic fun dot(u: DoubleArray, v: DoubleArray): Double = vel("u' * v")
                @JvmStatic fun apply(u: IntArray): IntArray = vel("[1, 2; 3, 4] * u")
                @JvmStatic fun last(a: IntArray): Int = vel("a[end - 1]")
                @JvmStatic fun abs(x: Int): Int = vel("if x < 0 then -x else x")
                @JvmStatic fun quotient(a: Long, b: Long): Long = vel("a // b")
                @JvmStatic fun next(c: Char): Int = vel("c + 1")
                @JvmStatic fun inLambda(y: Double): Double {
                    val f = { x: Double -> vel<Double>("x * y") }
                    return f(2.0)
                }
                @JvmStatic fun mat(): Array<IntArray> = vel("[1, 2; 3, 4]")
                @JvmStatic fun checked(x: Double): Double = vel("if x > 0 then x else error()")
                @JvmStatic fun at(v: DoubleArray, i: Int): Double = vel("v[i]")
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val calc = result.classLoader.loadClass("Calc")
        fun call(name: String, vararg args: Any): Any? {
            val m = calc.methods.single { it.name == name }
            try {
                return m.invoke(null, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause!!
            }
        }
        assertEquals(0.5 * 2 * 9, call("ke", 2.0, 3.0))
        assertEquals((9.8 * 4 / 2).toFloat(), call("fall", 2f))
        assertArrayEquals(doubleArrayOf(1.5, 2.0, 2.5),
            call("step", doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, 2.0, 3.0), 0.5) as DoubleArray)
        assertEquals(32.0, call("dot", doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0)))
        assertArrayEquals(intArrayOf(17, 39), call("apply", intArrayOf(5, 6)) as IntArray)
        assertEquals(30, call("last", intArrayOf(10, 20, 30)))
        assertEquals(5, call("abs", -5))
        assertEquals(-4L, call("quotient", -7L, 2L))
        assertEquals(98, call("next", 'a'))
        assertEquals(6.0, call("inLambda", 3.0))
        @Suppress("UNCHECKED_CAST")
        val m = call("mat") as Array<IntArray>
        assertArrayEquals(intArrayOf(1, 3), m[0]) // Mat(2, 2) is Java/Kotlin a[col][row]
        assertArrayEquals(intArrayOf(2, 4), m[1])
        assertEquals(2.0, call("checked", 2.0))
        assertEquals("com.doubledeltas.vel.VelException", assertThrows(Throwable::class.java) { call("checked", -1.0) }.javaClass.name)
        assertThrows(NullPointerException::class.java) { call("at", doubleArrayOf(1.0), 3) }
    }

    @Test
    fun unsignedAndNullableTypes() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel

            object Numbers {
                @JvmStatic fun add(a: UInt, b: UInt): UInt = vel("a + b")
                @JvmStatic fun ratio(a: ULong, b: ULong): Double = vel("a / b")
                @JvmStatic fun greater(a: UInt, b: UInt): Int = vel("if a > b then 1 else 0")
                @JvmStatic fun same(a: UShort): UShort = vel("a")
                @JvmStatic fun twice(u: UIntArray): UIntArray = vel("2 * u")
                @JvmStatic fun nullable(x: Double?): Double? = vel("x * 2")
                @JvmStatic fun checkAdd(): Boolean = add(UInt.MAX_VALUE, 2u) == 1u
                @JvmStatic fun checkRatio(): Boolean = ratio(ULong.MAX_VALUE, 1uL) == 1.8446744073709552E19
                @JvmStatic fun checkGreater(): Boolean = greater(UInt.MAX_VALUE, 1u) == 1
                @JvmStatic fun checkSame(): Boolean = same(UShort.MAX_VALUE) == UShort.MAX_VALUE
                @JvmStatic fun checkTwice(): Boolean = twice(uintArrayOf(1u, 2147483648u)).contentEquals(uintArrayOf(2u, 0u))
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val numbers = result.classLoader.loadClass("Numbers")
        for (check in listOf("checkAdd", "checkRatio", "checkGreater", "checkSame", "checkTwice")) {
            assertEquals(true, numbers.getMethod(check).invoke(null), check)
        }
        assertEquals(6.0, numbers.getMethod("nullable", java.lang.Double::class.java).invoke(null, 3.0))
        val e = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            numbers.getMethod("nullable", java.lang.Double::class.java).invoke(null, null)
        }
        assertTrue(e.cause is NullPointerException, e.cause.toString())
    }

    @Test
    fun listsArraysAndComponentObjects() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel
            import com.doubledeltas.vel.Dim

            data class Point(val x: Double, val y: Double)
            data class Mixed(val a: Int, val b: Double)

            object Shapes {
                @JvmStatic fun add(u: List<Double>, v: List<Double>): List<Double> = vel("u + v")
                @JvmStatic fun doubled(a: Array<Int>): Array<Int> = vel("2 * a")
                @JvmStatic fun transposed(m: List<List<Double>>): List<List<Double>> = vel("m'")
                @JvmStatic fun rotate(p: Point): Point = vel("(-p[1], p[0])")
                @JvmStatic fun sum(q: Mixed): Double = vel("q[0] + q[1]")
                @JvmStatic fun swap(p: Pair<Double, Double>): Pair<Double, Double> = vel("(p[1], p[0])")
                @JvmStatic fun product(t: Triple<Int, Int, Int>): Int = vel("t[0] * t[1] * t[2]")
                @JvmStatic fun scaled(@Dim(2, 2) m: Array<UIntArray>): Array<UIntArray> = vel("2 * m")
                @JvmStatic fun nullableList(x: List<Double>?): List<Double> = vel("x * 2")
                @JvmStatic fun first(unused: List<Double>, v: DoubleArray): Double = vel("v[0]")

                @JvmStatic fun checkScaled(): Boolean {
                    val r = scaled(arrayOf(uintArrayOf(1u, 2u), uintArrayOf(3u, 4u)))
                    return r[0].contentEquals(uintArrayOf(2u, 4u)) && r[1].contentEquals(uintArrayOf(6u, 8u))
                }
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val shapes = result.classLoader.loadClass("Shapes")
        fun call(name: String, vararg args: Any?): Any? {
            val m = shapes.methods.single { it.name == name }
            try {
                return m.invoke(null, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause!!
            }
        }
        assertEquals(listOf(5.0, 7.0), call("add", listOf(1.0, 2.0), listOf(4.0, 5.0)))
        assertArrayEquals(arrayOf(2, 4), call("doubled", arrayOf(1, 2)) as Array<*>)
        assertEquals(listOf(listOf(1.0, 3.0), listOf(2.0, 4.0)), call("transposed", listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))))
        val make = result.classLoader.loadClass("Point").getConstructor(Double::class.java, Double::class.java)
        assertEquals(make.newInstance(-2.0, 1.0), call("rotate", make.newInstance(1.0, 2.0)))
        val mixed = result.classLoader.loadClass("Mixed").getConstructor(Int::class.java, Double::class.java).newInstance(1, 0.5)
        assertEquals(1.5, call("sum", mixed))
        assertEquals(Pair(2.0, 1.0), call("swap", Pair(1.0, 2.0)))
        assertEquals(24, call("product", Triple(2, 3, 4)))
        assertEquals(true, call("checkScaled"))
        assertEquals(listOf(2.0), call("nullableList", listOf(1.0)))
        assertThrows(NullPointerException::class.java) { call("nullableList", null) }
        assertEquals(7.0, call("first", listOf<Double>(), doubleArrayOf(7.0)))
    }

    @Test
    fun typeErrorIsReportedAtLiteral() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel
            fun f(x: Double, b: Boolean): Double {
                return vel("x + b")
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("VEL 1:" in result.messages, result.messages)
        assertTrue("Test.kt:3:" in result.messages, result.messages)
    }

    @Test
    fun syntaxErrorIsReportedAtLiteral() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel
            fun ke(m: Double, v: Double): Double {
                return vel("0.5 * (m * v")
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("VEL 1:" in result.messages, result.messages)
        assertTrue("Test.kt:3:" in result.messages, result.messages)
    }

    @Test
    fun nonLiteralArgumentIsRejected() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel
            fun ke(src: String): Double = vel(src)
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("string literal" in result.messages, result.messages)
    }

    @Test
    fun stringTemplateIsRejected() {
        val result = compile(
            """
            import com.doubledeltas.vel.VelInline.vel
            fun ke(name: String): Double = vel("2 * ${'$'}name")
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("string literal" in result.messages, result.messages)
    }

    @Test
    fun unrelatedFunctionNamedVelIsIgnored() {
        val result = compile(
            """
            fun vel(s: String): Double = 0.0
            fun x(): Double = vel("(((")
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    private fun compile(source: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Test.kt", source.trimIndent()))
            inheritClassPath = true
            messageOutputStream = System.out
            compilerPluginRegistrars = listOf(VelCompilerPluginRegistrar())
        }.compile()
}
