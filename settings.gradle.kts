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

val localProperties = Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

fun getProperty(key: String): String? {
    return System.getenv("ORG_GRADLE_PROJECT_$key") ?: localProperties.getProperty(key)
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "gitHubPackages"
            url = uri("https://maven.pkg.github.com/pavelapk/hlc")
            credentials {
                username = getProperty("ghPackagesReadUser")
                password = getProperty("ghPackagesReadPassword")
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
