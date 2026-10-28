plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = System.getenv("STOHN_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("STOHN_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("STOHN_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("STOHN_RELEASE_KEY_PASSWORD")

val signingReady =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "org.stohncoin.wallet"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (signingReady) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "org.stohncoin.wallet"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.28.2-fullmode"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
}

// Fail fast when a release task runs without the four STOHN_RELEASE_* variables set;
// an unsigned release APK must never silently leave the CI/build machine.
gradle.taskGraph.whenReady {
    val hasReleaseTask = allTasks.any { it.name.endsWith("Release") }
    if (hasReleaseTask) {
        require(signingReady) {
            "Release signing requires STOHN_RELEASE_STORE_FILE, STOHN_RELEASE_STORE_PASSWORD, " +
                "STOHN_RELEASE_KEY_ALIAS and STOHN_RELEASE_KEY_PASSWORD to be set. " +
                "Refusing to produce an unsigned release build."
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
}
