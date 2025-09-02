package com.tap.synk.processor.visitor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo
import com.tap.synk.processor.context.EncoderContext
import com.tap.synk.processor.context.ProcessorContext
import com.tap.synk.processor.filespec.encoder.mapEncoder
import com.tap.synk.processor.filespec.encoder.mapEncoderFileSpec

internal class MapEncoderVisitor(
    private val processorContext: ProcessorContext,
    private val serializers: List<KSClassDeclaration>,
) : KSVisitorVoid() {

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val encoderContext = EncoderContext(processorContext, classDeclaration, serializers)
        val containingFile = classDeclaration.containingFile ?: run {
            processorContext.logger.error("Failed to find annotation containing file")
            return
        }

        val mapEncoder = with(encoderContext) { mapEncoder() }
        val mapEncoderFileSpec = mapEncoderFileSpec(
            mapEncoder,
        ) {
            // The generated MapEncoder depends on the CRDT declaration and on any
            // discovered @SynkSerializer classes. Declare all as originating to
            // make KSP incremental aware of these dependencies.
            addOriginatingKSFile(containingFile)
            serializers.forEach { serializerDecl ->
                serializerDecl.containingFile?.let { addOriginatingKSFile(it) }
            }
        }
        // Mark as aggregating because output depends on symbols beyond the CRDT class
        // (e.g., external @SynkSerializer declarations, sealed subclasses).
        mapEncoderFileSpec.writeTo(codeGenerator = processorContext.codeGenerator, aggregating = true)
    }
}
