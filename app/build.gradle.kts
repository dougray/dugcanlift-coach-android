import java.util.Properties

// Signing mirrors LIFT Android's: the keystore path and passwords live in a
// git-ignored keystore.properties at the repo root, never in the build script.
// When that file is absent — a fresh clone, or a build server with its own key —
// release builds are simply left unsigned rather than failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasSigningConfig = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasSigningConfig) load(keystorePropertiesFile.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dugcanlift.coach"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dugcanlift.coach"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // A debug build installs as its own application id, so it can sit on the
        // same phone as a release build without replacing it. It also means the
        // debug signing key can be named in the site's assetlinks.json against
        // `com.dugcanlift.coach.debug` without ever being able to claim links on
        // behalf of the real app — which is why the release entry there names
        // only the release key.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Coach (debug)")
        }
        release {
            resValue("string", "app_name", "Coach")
            optimization {
                enable = false
            }
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // The debug and release build types each set their own app_name, so the
        // launcher shows which one you are looking at.
        resValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.window)
    implementation("com.github.dougray:dugcanlift-kit-android:1.4.0")
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
