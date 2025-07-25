package com.tap.synk.processor.context

import com.google.devtools.ksp.innerArguments
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.tap.synk.processor.ext.asType
import com.tap.synk.processor.ext.isEnum

internal data class EncoderContext(
    private val processorContext: ProcessorContext,
    private val classDeclaration: KSClassDeclaration,
    private val serializers: List<KSClassDeclaration>,
) : ProcessorContext by processorContext {

    val type by lazy { classDeclaration.asType() }
    val typeName by lazy { type.toClassName() }

    val packageName by lazy { classDeclaration.packageName.asString() }
    val declarationName by lazy { classDeclaration.simpleName.asString() }

    // FooMapEncoder
    val className by lazy { ClassName(classDeclaration.packageName.asString(), classDeclaration.simpleName.asString() + "MapEncoder") }

    val isSealed by lazy { classDeclaration.modifiers.contains(Modifier.SEALED) }
    val sealedSubClasses by lazy { classDeclaration.getSealedSubclasses().toList() }
    private val parameters by lazy { classDeclaration.primaryConstructor?.parameters ?: emptyList() }

    val derivedParameters by lazy {
        parameters.map { param ->
            DerivedParameter(
                param,
                param.name?.asString() ?: "",
                param.type.resolve(),
            )
        }
    }

    val serializerMap by lazy {
        serializers.fold(mutableMapOf<KSClassDeclaration, KSClassDeclaration>()) { acc, serializerDeclaration ->
            acc.apply {
                val superType = serializerDeclaration.superTypes.first().resolve()
                val serializedType = superType.innerArguments.first().type!!.resolve()
                put(serializedType.declaration as KSClassDeclaration, serializerDeclaration)
            }
        }
    }

    inner class DerivedParameter(
        val parameter: KSValueParameter,
        val name: String,
        val type: KSType,
    ) {
        val isInstanceOfCollection by lazy {
            symbols.isCollection(type)
        }

        val isInstanceOfDataClass by lazy {
            symbols.isDataClass(type.makeNotNullable())
        }

        val hasProvidedSerializer by lazy {
            serializerMap.contains(type.makeNotNullable().declaration)
        }

        val isEnum by lazy {
            (type.declaration as KSClassDeclaration).isEnum()
        }

        val innerType by lazy {
            type.innerArguments.first().type?.resolve() ?: run {
                throw IllegalStateException("Inner type was not resolved for parameter $name")
            }
        }

        val innerTypeName by lazy {
            innerType.toTypeName()
        }
    }
}
