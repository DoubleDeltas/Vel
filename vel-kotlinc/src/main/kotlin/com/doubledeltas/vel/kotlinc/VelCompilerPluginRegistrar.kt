package com.doubledeltas.vel.kotlinc

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Kotlin compiler plugin for inline `vel("...")` expressions (style C, Kotlin counterpart of the
 * javac plugin). Registered through `META-INF/services`; with Gradle, add this module to the
 * `kotlinCompilerPluginClasspath` configuration.
 */
@OptIn(ExperimentalCompilerApi::class)
class VelCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(VelIrGenerationExtension())
    }

    companion object {
        const val PLUGIN_ID = "com.doubledeltas.vel"
    }
}
