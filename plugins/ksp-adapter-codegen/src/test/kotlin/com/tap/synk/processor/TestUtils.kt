package com.tap.synk.processor

import com.tschuchort.compiletesting.CompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.io.File
import kotlin.test.assertEquals
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity

internal fun compileWith(workDir: File, vararg source: SourceFile): CompilationResult =
    KotlinCompilation().apply {
        sources = source.toList()
        jvmTarget = "11"
        configureKsp(useKsp2 = true) {
            symbolProcessorProviders.add(SynkAdapterProcessorProvider())
            allWarningsAsErrors = true
            loggingLevels = CompilerMessageSeverity.VERBOSE
        }
        workingDir = workDir
        inheritClassPath = true
        verbose = true
    }.compile()

internal fun assertSourceEquals(
    @Language("kotlin") expected: String,
    actual: String,
) {
    assertEquals(
        expected.trimIndent(),
        actual.trimIndent().replace("\t", "    "),
    )
}

internal fun CompilationResult.sourceFor(fileName: String): String {
    val sources = kspGeneratedSources()
    return sources.find { it.name == fileName }
        ?.readText()
        ?: throw IllegalArgumentException("Could not find file $fileName in $sources")
}

internal fun CompilationResult.kspGeneratedSources(): List<File> {
    val kspWorkingDir = workingDir.resolve("ksp")
    val kspGeneratedDir = kspWorkingDir.resolve("sources")
    val kotlinGeneratedDir = kspGeneratedDir.resolve("kotlin")
    val javaGeneratedDir = kspGeneratedDir.resolve("java")
    return kotlinGeneratedDir.walk().toList() + javaGeneratedDir.walk().toList()
}

internal val CompilationResult.workingDir: File
    get() = checkNotNull(outputDirectory.parentFile)

internal fun compileToClasspath(workDir: File, vararg sources: SourceFile): File {
    val result = KotlinCompilation().apply {
        this.sources = sources.toList()
        jvmTarget = "11"
        inheritClassPath = true
        verbose = false
        workingDir = workDir
    }.compile()
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
    return result.outputDirectory
}

