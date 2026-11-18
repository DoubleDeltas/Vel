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
