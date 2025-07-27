import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.lib)
    alias(libs.plugins.sqldelight)
//    id("kotlinter-conventions")
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
        artifactId = "delightful-metastore",
        version = version.toString(),
    )
}

sqldelight {
    databases {
        create("DelightfulDatabase") {
            packageName.set("com.tap.delight.metastore")
        }
    }
}

kotlin {

    jvm()
    androidTarget()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.synk)
                implementation(projects.libs.concurrentMap)
                implementation(libs.murmurhash)
                implementation(libs.androidx.collections.kmp)
                api(libs.sqldelight.runtime)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.sqldelight.jvm.driver)
            }
        }
    }
}

android {

    namespace = "com.tap.delight.metastore"
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    compileSdk = libs.versions.android.compile.sdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.bytecode.version.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.bytecode.version.get().toInt())
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
