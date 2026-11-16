package com.doubledeltas.vel.kotlinc

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.warning1

/** Diagnostics reported by the VEL compiler plugin. The message is fully rendered by VEL. */
object VelErrors : KtDiagnosticsContainer() {

    val VEL_ERROR by error1<PsiElement, String>()

    val VEL_WARNING by warning1<PsiElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = VelErrorMessages
}

object VelErrorMessages : BaseDiagnosticRendererFactory() {

    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Vel") { map ->
        map.put(VelErrors.VEL_ERROR, "{0}", CommonRenderers.STRING)
        map.put(VelErrors.VEL_WARNING, "{0}", CommonRenderers.STRING)
    }
}
