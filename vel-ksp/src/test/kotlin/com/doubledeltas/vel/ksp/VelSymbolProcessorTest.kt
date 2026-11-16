package com.doubledeltas.vel.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
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
