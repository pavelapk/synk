package com.tap.synk.processor.integration

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KmpKspMetadataIncrementalIT {

    @Rule
    @JvmField
    var tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `ksp metadata second build keeps serializers after touching resolver`() {
        val projectDir = tmp.newFolder("kmp-ksp-it").apply { mkdirs() }

        // Locate repo root to include as composite build for dependency substitution.
        val repoRoot = resolveRepoRoot()

        // Write minimal KMP project
        val settingsFile = projectDir.resolve("settings.gradle.kts").apply { parentFile.mkdirs() }
        settingsFile.writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "kmp-ksp-it"
            """.trimIndent(),
        )

        val adapterJar = File(repoRoot, "plugins/ksp-adapter-codegen/build/libs/ksp-adapter-codegen.jar").absolutePath
        fun latestJar(dir: File, prefix: String) =
            dir.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.extension == "jar" }
                ?.maxByOrNull { it.lastModified() }
                ?.absolutePath
                ?: error("Could not find ${'$'}prefix*.jar under ${'$'}dir")

        val synkJar = latestJar(File(repoRoot, "synk/build/libs"), "synk-jvm-")
        val annotationsJar = latestJar(File(repoRoot, "libs/annotations/build/libs"), "annotations-jvm-")

        val buildFile = projectDir.resolve("build.gradle.kts")
        buildFile.writeText(
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") version "2.2.0"
                id("com.google.devtools.ksp") version "2.2.0-2.0.2"
            }

            kotlin { jvm() }

            dependencies {
                // Provide processor jar + its runtime deps explicitly to avoid including the whole repo or private repos
                add("kspJvm", files(${adapterJar.quotedForKotlin()}))
                add("kspJvm", files(${synkJar.quotedForKotlin()}))
                add("kspJvm", files(${annotationsJar.quotedForKotlin()}))
                add("kspJvm", "com.squareup:kotlinpoet:2.2.0")
                add("kspJvm", "com.squareup:kotlinpoet-ksp:2.2.0")
                add("kspJvm", "com.google.devtools.ksp:symbol-processing-api:2.2.0-2.0.2")
            }
            """.trimIndent(),
        )

        println("IT projectDir: ${'$'}{projectDir.absolutePath}")
        println("IT settings.gradle.kts:\n" + settingsFile.readText())
        println("IT build.gradle.kts:\n" + buildFile.readText())

        // Minimal Synk API stubs required by the processor/generation
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/annotation/Annotations.kt",
            """
            package com.tap.synk.annotation
            @Target(AnnotationTarget.CLASS)
            annotation class SynkAdapter
            @Target(AnnotationTarget.CLASS)
            annotation class SynkSerializer
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/resolver/IDResolver.kt",
            """
            package com.tap.synk.resolver
            interface IDResolver<T: Any> { fun resolveId(crdt: T): String }
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/encode/MapEncoder.kt",
            """
            package com.tap.synk.encode
            interface MapEncoder<T> {
                fun encode(crdt: T): Map<String, String>
                fun decode(map: Map<String, String>): T
            }
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/encode/StringEncoder.kt",
            """
            package com.tap.synk.encode
            object StringEncoder : MapEncoder<String> {
                override fun encode(crdt: String) = mapOf("" to crdt)
                override fun decode(map: Map<String, String>) = map.values.first()
            }
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/encode/NullableMapEncoder.kt",
            """
            package com.tap.synk.encode
            class NullableMapEncoder<T>(private val encoder: MapEncoder<T>) : MapEncoder<T?> {
                override fun encode(crdt: T?) = crdt?.let { encoder.encode(it) } ?: emptyMap()
                override fun decode(map: Map<String, String>) = if (map.isEmpty()) null else encoder.decode(map)
            }
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/serialize/StringSerializer.kt",
            """
            package com.tap.synk.serialize
            interface StringSerializer<T> {
                fun serialize(serializable: T): String
                fun deserialize(serialized: String): T
                companion object {
                    fun <T> factory(s: (T)->String, d:(String)->T): StringSerializer<T> =
                        object: StringSerializer<T> {
                            override fun serialize(serializable: T) = s(serializable)
                            override fun deserialize(serialized: String) = d(serialized)
                        }
                }
            }
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/serialize/EnumStringSerializer.kt",
            """
            package com.tap.synk.serialize
            class EnumStringSerializer<T: Enum<T>>(private val values: Array<out T>): StringSerializer<T> {
                override fun serialize(serializable: T) = serializable.ordinal.toString()
                override fun deserialize(serialized: String): T = values.first()
            }
            inline fun <reified T: Enum<T>> EnumStringSerializer(): EnumStringSerializer<T> = EnumStringSerializer(enumValues())
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/tap/synk/adapter/SynkAdapter.kt",
            """
            package com.tap.synk.adapter
            import com.tap.synk.encode.MapEncoder
            import com.tap.synk.resolver.IDResolver
            interface SynkAdapter<T: Any>: IDResolver<T>, MapEncoder<T>
            """.trimIndent(),
        )

        // Sources under test
        write(projectDir, "src/commonMain/kotlin/com/test/Bar.kt",
            """
            package com.test
            @JvmInline value class Bar(val test: Int)
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/test/BarSerializer.kt",
            """
            package com.test
            import com.tap.synk.annotation.SynkSerializer
            import com.tap.synk.serialize.StringSerializer
            @SynkSerializer
            object BarSerializer : StringSerializer<Bar> {
                override fun serialize(serializable: Bar): String = serializable.test.toString()
                override fun deserialize(serialized: String): Bar = Bar(serialized.toInt())
            }
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/test/Baz.kt",
            """
            package com.test
            enum class Baz { BIM, BAM }
            """.trimIndent(),
        )
        write(projectDir, "src/commonMain/kotlin/com/test/Foo.kt",
            """
            package com.test
            data class Foo(val bar: Bar, val barNull: Bar?, val baz: Baz)
            """.trimIndent(),
        )
        val resolverFile = File(projectDir, "src/commonMain/kotlin/com/test/FooResolver.kt").apply {
            parentFile.mkdirs()
            writeText(
            """
            package com.test
            import com.tap.synk.annotation.SynkAdapter
            import com.tap.synk.resolver.IDResolver
            @SynkAdapter
            class FooResolver : IDResolver<Foo> {
                override fun resolveId(crdt: Foo): String = crdt.toString()
            }
            """.trimIndent(),
            )
        }

        // 1) First build
        val r1 = runner(projectDir).withArguments("clean", "build", "--stacktrace").build()
        assertTrue(r1.output.contains(":compileKotlinJvm"))
        val enc1 = findGenerated(projectDir, "FooMapEncoder.kt").readText()
        assertTrue(enc1.contains("barSerializer.serialize"), "First build should use BarSerializer")

        // 2) Touch the resolver to trigger reprocessing without changing serializers
        resolverFile.appendText("\n// touch\n")

        val r2 = runner(projectDir).withArguments("build", "--stacktrace").build()
        // It's OK if tasks are SUCCESS or UP_TO_DATE depending on cache. We only assert outputs.
        val enc2 = findGenerated(projectDir, "FooMapEncoder.kt").readText()

        // If incremental deps are wrong, enc2 may fall back to toString(). Ensure it didn't.
        assertTrue(enc2.contains("barSerializer.serialize"), "Second build should still use BarSerializer")
        assertEquals(normalize(enc1), normalize(enc2), "Generated encoder changed across builds")
    }

    private fun runner(projectDir: File): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath() // for Kotlin plugin DSL usage in tests
            .forwardOutput()

    private fun resolveRepoRoot(): File {
        var dir = File(".").canonicalFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root from " + File(".").absolutePath)
    }

    private fun String.quotedForKotlin(): String = '"' + replace("\\", "\\\\").replace("\"", "\\\"") + '"'

    private fun findGenerated(projectDir: File, name: String): File {
        val candidates = listOf(
            File(projectDir, "build/generated/ksp/metadata/commonMain/kotlin"),
            File(projectDir, "build/generated/ksp/jvm/jvmMain/kotlin"),
            File(projectDir, "build/generated/ksp/jvmMain/kotlin"),
            File(projectDir, "build/generated/ksp/kotlinJvm/kotlin"),
        )
        candidates.forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().firstOrNull { it.name == name }?.let { return it }
            }
        }
        error("Generated file $name not found under ${candidates.joinToString()}")
    }

    private fun normalize(s: String) = s.trim().replace("\t", "    ")

    private fun write(root: File, path: String, content: String) {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(content)
    }
}
