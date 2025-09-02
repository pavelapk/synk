import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.symbol.processing)
//    id("kotlinter-conventions")
    alias(libs.plugins.maven.publish)
    id("java-gradle-plugin")
}

ksp {
    arg("autoserviceKsp.verify", "true")
    arg("autoserviceKsp.verbose", "true")
}

publishing {
    repositories {
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/pavelapk/synk")
            credentials(PasswordCredentials::class)
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "com.tap.synk.plugins",
        artifactId = "adapter-codegen",
        version = libs.versions.version.name.get()
    )
}

dependencies {
    compileOnly(libs.kotlin.symbol.processing.api)

    implementation(projects.synk)
    implementation(libs.kotlin.poet.core)
    implementation(libs.kotlin.poet.ksp)
    implementation(libs.autoservice.annotations)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.junit)
    testImplementation(libs.kotlin.compile.testing.core)
    testImplementation(libs.kotlin.compile.testing.ksp)
    testImplementation(gradleTestKit())

    ksp(libs.autoservice.ksp)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(libs.versions.java.bytecode.version.get().toInt())
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.bytecode.version.get()))
        freeCompilerArgs.addAll(
            "-Xcontext-receivers",
            "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
        )
    }
}
