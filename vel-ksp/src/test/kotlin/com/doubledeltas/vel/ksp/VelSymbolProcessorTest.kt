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
    fun unsignedAndNullableTypes() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            interface Numbers {
                @Vel("a + b")                      // Z+32: wraps modulo 2^32
                fun add(a: UInt, b: UInt): UInt
                @Vel("a / b")                      // unsigned division of Z+64, then true division: F64
                fun ratio(a: ULong, b: ULong): Double
                @Vel("a // b")
                fun quotient(a: ULong, b: ULong): ULong
                @Vel("if a > b then 1 else 0")     // unsigned comparison
                fun greater(a: UInt, b: UInt): Int
                @Vel("a")
                fun same(a: UShort): UShort
                @Vel("a + b")
                fun bytes(a: UByte, b: UByte): UInt
                @Vel("2 * u")
                fun twice(u: UIntArray): UIntArray
                @Vel("x * 2")
                fun nullable(x: Double?): Double?
                @Vel("m[0, 1]")
                fun nested(m: Array<DoubleArray?>?): Double
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val type = result.classLoader.loadClass("NumbersVel")
        val n = type.getConstructor().newInstance()
        fun call(name: String, vararg args: Any?): Any? {
            val m = type.methods.single { it.name == name || it.name.startsWith("$name-") }
            try {
                return m.invoke(n, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause!!
            }
        }
        // value classes are passed as their stored bits
        assertEquals(1, call("add", -1, 2))                          // 0xFFFFFFFF + 2 = 1
        assertEquals(Long.MAX_VALUE.toDouble() + 1, call("ratio", Long.MIN_VALUE, 1L)) // 2^63 / 1
        assertEquals(Long.MIN_VALUE ushr 1, call("quotient", Long.MIN_VALUE, 2L))    // 2^63 / 2
        assertEquals(1, call("greater", -1, 1))                      // 4294967295 > 1
        assertEquals(65535.toShort(), call("same", 65535.toShort()))
        assertEquals(510, call("bytes", 255.toByte(), 255.toByte()))
        assertArrayEquals(intArrayOf(2, 0), call("twice", intArrayOf(1, Int.MIN_VALUE)) as IntArray)
        assertEquals(6.0, call("nullable", 3.0))
        assertThrows(NullPointerException::class.java) { call("nullable", null) }
        assertEquals(2.0, call("nested", arrayOf(doubleArrayOf(1.0), doubleArrayOf(2.0))))
    }

    @Test
    fun listsArraysAndComponentObjects() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            import com.doubledeltas.vel.Dim
            data class Point(val x: Double, val y: Double)
            data class Mixed(val a: Int, val b: Double)
            interface Shapes {
                @Vel("u + v")
                fun add(u: List<Double>, v: List<Double>): List<Double>
                @Vel("2 * a")
                fun doubled(a: Array<Int>): Array<Int>
                @Vel("m'")
                fun transposed(m: List<List<Double>>): List<List<Double>>
                @Vel("(-p[1], p[0])")
                fun rotate(p: Point): Point
                @Vel("q[0] + q[1]")
                fun sum(q: Mixed): Double
                @Vel("(p[1], p[0])")
                fun swap(p: Pair<Double, Double>): Pair<Double, Double>
                @Vel("t[0] * t[1] * t[2]")
                fun product(t: Triple<Int, Int, Int>): Int
                @Vel("2 * m")
                fun scaled(@Dim(2, 2) m: Array<UIntArray>): Array<UIntArray>
                @Vel("x * 2")
                fun nullableList(x: List<Double>?): List<Double>
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val type = result.classLoader.loadClass("ShapesVel")
        val shapes = type.getConstructor().newInstance()
        fun call(name: String, vararg args: Any?): Any? {
            val m = type.methods.single { it.name == name || it.name.startsWith("$name-") }
            try {
                return m.invoke(shapes, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause!!
            }
        }
        assertEquals(listOf(5.0, 7.0), call("add", listOf(1.0, 2.0), listOf(4.0, 5.0)))
        assertArrayEquals(arrayOf(2, 4), call("doubled", arrayOf(1, 2)) as Array<*>)
        assertEquals(listOf(listOf(1.0, 3.0), listOf(2.0, 4.0)), call("transposed", listOf(listOf(1.0, 2.0), listOf(3.0, 4.0))))
        val point = result.classLoader.loadClass("Point")
        val make = point.getConstructor(Double::class.java, Double::class.java)
        assertEquals(make.newInstance(-2.0, 1.0), call("rotate", make.newInstance(1.0, 2.0)))
        val mixed = result.classLoader.loadClass("Mixed").getConstructor(Int::class.java, Double::class.java).newInstance(1, 0.5)
        assertEquals(1.5, call("sum", mixed))
        assertEquals(Pair(2.0, 1.0), call("swap", Pair(1.0, 2.0)))
        assertEquals(24, call("product", Triple(2, 3, 4)))
        // Array<UIntArray> is an array of boxed UIntArray objects on the JVM
        val m = java.lang.reflect.Array.newInstance(UIntArray::class.java, 2) as Array<Any?>
        m[0] = uintArrayOf(1u, 2u)
        m[1] = uintArrayOf(3u, 4u)
        @Suppress("UNCHECKED_CAST")
        val scaled = call("scaled", m) as Array<UIntArray>
        assertEquals(listOf(2u, 4u), scaled[0].toList())
        assertEquals(listOf(6u, 8u), scaled[1].toList())
        assertEquals(listOf(2.0), call("nullableList", listOf(1.0)))
        assertThrows(NullPointerException::class.java) { call("nullableList", null) }
    }

    @Test
    fun staticMethodsAreCalled() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            interface Norms {
                @Vel("Math.sqrt(x^2 + y^2)")
                fun norm(x: Double, y: Double): Double
                @Vel("Math.max(a, b)")                 // max(long, long)
                fun larger(a: Int, b: Long): Long
                @Vel("Math.abs{Double}(-2)")
                fun two(): Double
                @Vel("2 * java.util.Arrays.copyOf(v, 2)")
                fun firstTwice(v: DoubleArray): DoubleArray
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val type = result.classLoader.loadClass("NormsVel")
        val norms = type.getConstructor().newInstance()
        fun call(name: String, vararg args: Any?): Any? = type.methods.single { it.name == name }.invoke(norms, *args)
        assertEquals(5.0, call("norm", 3.0, 4.0))
        assertEquals(3L, call("larger", 3, 2L))
        assertEquals(2.0, call("two"))
        assertArrayEquals(doubleArrayOf(2.0, 4.0), call("firstTwice", doubleArrayOf(1.0, 2.0, 3.0)) as DoubleArray)
    }

    @Test
    fun ushortArrays() {
        val result = compile(
            """
            import com.doubledeltas.vel.Vel
            interface Shorts {
                @Vel("u")
                fun same(u: UShortArray): UShortArray
                @Vel("2 * u")                      // Z+32, wrapped back into Z+16 when returned
                fun twice(u: UShortArray): UShortArray
                @Vel("u[1]")
                fun second(u: UShortArray): UShort
                @Vel("u[0] + n")
                fun plus(u: UShortArray, n: UInt): UInt
                @Vel("c + 1")                      // Char and UShort are both Z+16
                fun next(c: Char): UShort
            }
            """
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val type = result.classLoader.loadClass("ShortsVel")
        val shorts = type.getConstructor().newInstance()
        fun call(name: String, vararg args: Any?): Any? {
            val m = type.methods.single { it.name == name || it.name.startsWith("$name-") }
            return m.invoke(shorts, *args)
        }
        // UShortArray is a short[] on the JVM, UShort a short
        assertArrayEquals(shortArrayOf(1, -1), call("same", shortArrayOf(1, -1)) as ShortArray)
        assertArrayEquals(shortArrayOf(2, 14464), call("twice", shortArrayOf(1, 40000.toShort())) as ShortArray)
        assertEquals(65535.toShort(), call("second", shortArrayOf(0, -1)))
        assertEquals(65535 + 1, call("plus", shortArrayOf(-1), 1))
        assertEquals(98.toShort(), call("next", 'a'))
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
