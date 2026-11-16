package com.doubledeltas.vel.kotlinc

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
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
