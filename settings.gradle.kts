import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    includeBuild("gradle/plugins/kotlinter-conventions")
    includeBuild("gradle/plugins/versions-conventions")
}

plugins {
    id("com.gradle.enterprise") version ("3.19.2")
}

val localProperties = Properties().apply {
    load(file("local.properties").inputStream())
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "gitHubPackages"
            url = uri("https://maven.pkg.github.com/pavelapk/hlc")
            credentials {
                username = localProperties.getProperty("ghPackagesReadUser")
                password = localProperties.getProperty("ghPackagesReadPassword")
            }
        }
    }
}

include(":libs:annotations")
include(":libs:concurrent-map")
include(":synk")
include(":extension:kotlin-serialization")
include(":metastores:delightful-metastore")
include(":plugins:ksp-adapter-codegen")

rootProject.name = "synk-multiplatform"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
