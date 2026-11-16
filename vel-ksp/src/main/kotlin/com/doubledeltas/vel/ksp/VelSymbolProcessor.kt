package com.doubledeltas.vel.ksp

import com.doubledeltas.vel.core.VelDiagnostic
import com.doubledeltas.vel.core.VelFrontend
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

class VelSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        VelSymbolProcessor(environment.logger)
}

/**
 * Handles `@Vel` functions in Kotlin sources (style B, Kotlin counterpart of `VelProcessor`).
 *
 * Current stage: validates placement and VEL syntax, reporting errors at the annotation.
 * Next stage: type-check against parameter types and generate the implementing class.
 */
class VelSymbolProcessor(private val logger: KSPLogger) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        for (symbol in resolver.getSymbolsWithAnnotation(VEL_ANNOTATION)) {
            val annotation = symbol.annotations.first { it.isVel() }
            if (symbol !is KSFunctionDeclaration) {
                logger.error("@Vel can only be applied to functions", annotation)
                continue
            }
            processFunction(symbol, annotation)
        }
        // Nothing is generated yet, so nothing needs to be deferred to a later round.
        return emptyList()
    }

    private fun processFunction(function: KSFunctionDeclaration, annotation: KSAnnotation) {
        if (!function.isAbstract) {
            logger.error("@Vel function must be abstract (its body is generated from VEL)", annotation)
            return
        }
        val owner = function.parentDeclaration as? KSClassDeclaration
        val ownerIsAbstract = owner != null && (owner.classKind == ClassKind.INTERFACE
                || (owner.classKind == ClassKind.CLASS && Modifier.ABSTRACT in owner.modifiers))
        if (!ownerIsAbstract) {
            logger.error("@Vel function must be declared in an interface or abstract class", annotation)
            return
        }

        val source = annotation.arguments.first { it.name?.asString() == "value" }.value as String
        for (d in VelFrontend.parseProgram(source).diagnostics()) {
            when (d.severity()) {
                VelDiagnostic.Severity.ERROR -> logger.error(d.format(), annotation)
                VelDiagnostic.Severity.WARNING -> logger.warn(d.format(), annotation)
                null -> Unit
            }
        }
        // TODO: type check + code generation via CodeGenerator
    }

    private fun KSAnnotation.isVel(): Boolean =
        annotationType.resolve().declaration.qualifiedName?.asString() == VEL_ANNOTATION

    companion object {
        const val VEL_ANNOTATION = "com.doubledeltas.vel.Vel"
    }
}
