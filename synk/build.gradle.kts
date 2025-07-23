import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.lib)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.atomic.fu)
//    alias(libs.plugins.kotlinter)
    alias(libs.plugins.maven.publish)
}

group = "com.tap"
version = libs.versions.version.name.get()

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
        groupId = group.toString(),
        artifactId = "synk",
        version = version.toString(),
    )
}

kotlin {

    androidTarget {
        publishLibraryVariants("release")
    }

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
//        allWarningsAsErrors.set(false)
        freeCompilerArgs.addAll("-Xcontext-receivers")
    }


    sourceSets {
        commonMain {
            dependencies {
                api(projects.libs.annotations)
                implementation(projects.libs.concurrentMap)
                implementation(libs.kotlin.reflection)
                implementation(libs.kotlinx.atomic.fu)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.hlc)
                api(libs.okio)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.okio.fakefilesystem)
                implementation(libs.faker)
            }
        }
    }
}

android {

    namespace = "com.tap.synk"
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    compileSdk = libs.versions.android.compile.sdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.bytecode.version.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.bytecode.version.get().toInt())
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    androidComponents {
        beforeVariants { builder ->
            if (builder.buildType == "debug") {
                builder.enable = false
            } else {
                builder.enableUnitTest = false
                builder.enableAndroidTest = false
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.fromTarget(libs.versions.java.bytecode.version.get())
}
