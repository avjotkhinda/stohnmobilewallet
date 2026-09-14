plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.stohncoin.wallet"
    compileSdk = 36
signingConfigs {
    create("release") {
        val storeFilePath = System.getenv("STOHN_RELEASE_STORE_FILE")
        val storePasswordValue = System.getenv("STOHN_RELEASE_STORE_PASSWORD")
        val keyAliasValue = System.getenv("STOHN_RELEASE_KEY_ALIAS")
        val keyPasswordValue = System.getenv("STOHN_RELEASE_KEY_PASSWORD")

        if (
            !storeFilePath.isNullOrBlank() &&
            !storePasswordValue.isNullOrBlank() &&
            !keyAliasValue.isNullOrBlank() &&
            !keyPasswordValue.isNullOrBlank()
        ) {
            storeFile = file(storeFilePath)
            storePassword = storePasswordValue
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
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
        signingConfig = signingConfigs.getByName("release")
    }
}
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
}
