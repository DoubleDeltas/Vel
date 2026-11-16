package com.doubledeltas.vel.kotlinc

import com.doubledeltas.vel.core.VelDiagnostic
import com.doubledeltas.vel.core.VelFrontend
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Finds calls to `VelInline.vel` in the IR after type resolution.
 *
 * Current stage: checks that the argument is a string literal and that it parses.
 * Next stage: type-check against the resolved types of referenced variables and replace the call
 * with generated IR.
 */
class VelIrGenerationExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val reporter = pluginContext.diagnosticReporter
        for (file in moduleFragment.files) {
            file.acceptChildrenVoid(InlineChecker(file, reporter))
        }
    }

    private class InlineChecker(
        private val file: IrFile,
        private val reporter: IrDiagnosticReporter,
    ) : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        // The IR is fully built when IrGenerationExtension runs, so resolving symbol owners is safe.
        @OptIn(UnsafeDuringIrConstructionAPI::class)
        override fun visitCall(expression: IrCall) {
            if (expression.symbol.owner.callableId == VEL_INLINE) {
                check(expression)
            }
            super.visitCall(expression)
        }

        private fun check(call: IrCall) {
            val arg = call.arguments[0]
            if (arg !is IrConst || arg.kind != IrConstKind.String) {
                reporter.at(arg ?: call, file)
                    .report(VelErrors.VEL_ERROR, "vel(...) argument must be a string literal without templates")
                return
            }
            val source = arg.value as String
            for (d in VelFrontend.parseExpression(source).diagnostics()) {
                val factory = when (d.severity()) {
                    VelDiagnostic.Severity.WARNING -> VelErrors.VEL_WARNING
                    else -> VelErrors.VEL_ERROR
                }
                reporter.at(arg, file).report(factory, d.format())
            }
            // TODO: resolve referenced names in scope and replace the call with generated IR.
        }
    }

    companion object {
        val VEL_INLINE = CallableId(
            ClassId(FqName("com.doubledeltas.vel"), Name.identifier("VelInline")),
            Name.identifier("vel"),
        )
    }
}
