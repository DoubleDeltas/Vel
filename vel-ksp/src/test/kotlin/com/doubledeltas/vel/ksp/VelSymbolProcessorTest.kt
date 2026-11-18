package com.doubledeltas.vel.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
class VelSymbolProcessorTest {

    @Test
    fun validVelCompiles() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            interface Physics {
                @Vel(${"\"\"\""}
                    return p + v * dt + 0.5 * a * dt^2
                    ${"\"\"\""})
                fun position(p: Double, v: Double, a: Double, dt: Double): Double
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun generatedClassRuns() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            import com.doubledeltas.vel.Dim
            interface Physics {
                @Vel(${"\"\"\""}
                    # constant-acceleration step
                    return p + v * dt + 0.5 * a * dt^2
                    ${"\"\"\""})
                fun position(p: Double, v: Double, a: Double, dt: Double): Double

                @Vel("p + v * dt")
                @Dim(3) fun step(@Dim(3) p: DoubleArray, @Dim(3) v: DoubleArray, dt: Double): DoubleArray

                @Vel("[1, 2; 3, 4] * u")
                fun apply(@Dim(2) u: IntArray): IntArray

                @Vel("c + 1")
                fun next(c: Char): Int

                @Vel("if x < 0 then error() else x")
                fun nonNegative(x: Float): Float
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val type = result.classLoader.loadClass("PhysicsVel")
        val physics = type.getConstructor().newInstance()
        assertEquals(1 + 2 * 3 + 0.5 * 4 * 9.0,
            type.getMethod("position", Double::class.java, Double::class.java, Double::class.java, Double::class.java)
                .invoke(physics, 1.0, 2.0, 4.0, 3.0))
        assertArrayEquals(doubleArrayOf(1.5, 2.0, 2.5),
            type.getMethod("step", DoubleArray::class.java, DoubleArray::class.java, Double::class.java)
                .invoke(physics, doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, 2.0, 3.0), 0.5) as DoubleArray)
        assertArrayEquals(intArrayOf(1 * 5 + 2 * 6, 3 * 5 + 4 * 6),
            type.getMethod("apply", IntArray::class.java).invoke(physics, intArrayOf(5, 6)) as IntArray)
        assertEquals(98, type.getMethod("next", Char::class.java).invoke(physics, 'a'))
        val nonNegative = type.getMethod("nonNegative", Float::class.java)
        assertEquals(2f, nonNegative.invoke(physics, 2f))
        val e = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { nonNegative.invoke(physics, -1f) }
        assertEquals("com.doubledeltas.vel.VelException", e.cause!!.javaClass.name)
    }

    @Test
    fun unsupportedTypeIsReported() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            interface Text {
                @Vel("s")
                fun f(s: String): String
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("not supported" in result.messages, result.messages)
    }

    @Test
    fun syntaxErrorIsReported() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            interface Physics {
                @Vel("return p + * v")
                fun f(p: Double, v: Double): Double
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("VEL 1:" in result.messages, result.messages)
    }

    @Test
    fun concreteFunctionIsRejected() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            interface Physics {
                @Vel("return 1")
                fun f(): Double = 0.0
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("must be abstract" in result.messages, result.messages)
    }

    @Test
    fun functionInConcreteClassIsRejected() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            object Physics {
                @Vel("return 1")
                fun f(): Double = 0.0
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
    }

    private fun compile(source: String): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Physics.kt", source.trimIndent()))
            inheritClassPath = true
            messageOutputStream = System.out
            configureKsp {
                symbolProcessorProviders += VelSymbolProcessorProvider()
            }
        }.compile()
}
