pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CoachAndroid"
include(":app")

// Local development against the kit checkout (CI never uses this: it resolves the JitPack tag): put `kitPath=../dugcanlift-kit-android` in local.properties.
val localProps = java.util.Properties().apply { val f = file("local.properties"); if (f.exists()) f.inputStream().use { load(it) } }
localProps.getProperty("kitPath")?.let { includeBuild(it) {
    dependencySubstitution {
        substitute(module("com.github.dougray.dugcanlift-kit-android:liftcore")).using(project(":liftcore"))
        substitute(module("com.github.dougray.dugcanlift-kit-android:liftmap")).using(project(":liftmap"))
    }
} }
