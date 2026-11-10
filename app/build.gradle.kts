plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    jacoco
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

    testOptions {
        // Robolectric needs the merged manifest and compiled resources to build a simulated
        // device; without this it runs against a stub with no theme, no resources and no manifest.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom(files("src/main/java", "src/test/java"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

// ---- Unit-test coverage -------------------------------------------------------------------
// JaCoCo over the debug unit tests. The Android plugin wires coverage for instrumented tests
// only, so the report and the verification task are declared here and scoped to the two
// packages whose behaviour holds the wallet together.
jacoco { toolVersion = "0.8.12" }

tasks.withType<Test>().configureEach {
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val coveragePackages = listOf("wallet", "security")

fun coverageClasses(): FileCollection =
    // AGP 9's built-in Kotlin compiler reports its output under built_in_kotlinc rather than
    // the tmp/kotlin-classes directory older setups use; the javac tree is listed too so any
    // Java sources added later are measured rather than silently skipped.
    files(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
        layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
    ).asFileTree.matching {
        coveragePackages.forEach { include("org/stohncoin/wallet/$it/**") }
        exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.class")
    }

val jacocoDebugUnitTestReport =
    tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoDebugUnitTestReport") {
        dependsOn("testDebugUnitTest")
        classDirectories.setFrom(coverageClasses())
        sourceDirectories.setFrom(files("src/main/java"))
        executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

tasks.register<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>(
    "jacocoDebugUnitTestCoverage"
) {
    group = "verification"
    description = "Fails when wallet/ or security/ coverage drops below its floor."
    dependsOn(jacocoDebugUnitTestReport)
    classDirectories.setFrom(coverageClasses())
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    // The floors are a ratchet, not a target: they sit just under what the suites measure
    // today (wallet 84.0%, security 82.8% instruction) so the number can only go up. A new
    // untested class in either package trips this; a refactor of covered code does not.
    violationRules {
        isFailOnViolation = true
        rule {
            element = "PACKAGE"
            includes = listOf("org.stohncoin.wallet.wallet")
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("org.stohncoin.wallet.security")
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
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
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.tukaani:xz:1.10")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    // Unit tests run against the mockable android.jar, whose org.json throws "not mocked" on
    // every call. WalletRpc speaks JSON-RPC, so its tests need a real implementation on the
    // test classpath; this is the reference org.json that Android's own copy derives from.
    testImplementation("org.json:json:20240303")
    // Robolectric runs the app's Android code paths on the JVM against a simulated framework, so
    // the parts that only make sense with a real Context - the app-lock store, Compose screens -
    // can be exercised by the same `test` task as everything else. Pinned to API 35 in
    // src/test/resources/robolectric.properties because CI runs JDK 17 and API 36 needs JDK 21.
    // It fetches its android-all runtime jars itself at test time, so the first run is slow and
    // needs network access; Gradle's dependency verification does not cover those downloads.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
