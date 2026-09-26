import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9's built-in Kotlin support means org.jetbrains.kotlin.android is
    // no longer applied separately (matches haze-mobile's app/build.gradle.kts).
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Release signing material lives outside git (see .gitignore). Without it
// the release build still assembles, just unsigned — so a fresh clone can
// build and test without needing the key.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.mazeconnect.app"
    compileSdk = 37

    defaultConfig {
        // Reverse-DNS of the project domain, matching this user's other
        // apps' convention (tr.com.berkkucukk.<app>); in-code package stays
        // com.mazeconnect.app.
        applicationId = "tr.com.berkkucukk.mazeconnect"
        // BiometricPrompt's unified API and StrongBox availability checks
        // land at 28 — chosen over this user's usual minSdk 26 baseline
        // given this app's higher security bar (gates re-pairing/wipe).
        minSdk = 28
        targetSdk = 37
        // Must never go backwards or repeat: Android refuses to install an
        // update whose versionCode is not higher than the installed one.
        versionCode = 47
        versionName = "0.15.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The F-Droid build (`-Pmazeconnect.fdroid=true`, set in fdroiddata's
        // metadata) leaves the update reminder out entirely. F-Droid delivers
        // updates itself, and its inclusion policy does not accept an app
        // that points users at a download outside it. Everything else is the
        // same APK — a flag rather than a flavour, because the flavours this
        // project once had were removed for good reasons (see README).
        val fdroid = providers.gradleProperty("mazeconnect.fdroid")
            .map { it.toBoolean() }
            .getOrElse(false)
        buildConfigField("boolean", "UPDATE_CHECKER", (!fdroid).toString())
    }

    signingConfigs {
        create("release") {
            val store = keystoreProperties.getProperty("storeFile")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                // minSdk is 28, so the v1 JAR signature buys nothing and
                // only slows verification. v3 is worth having: it is what
                // allows the signing key to be rotated later without
                // orphaning everyone's install.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP otherwise embeds a dependency list in the APK signing block,
    // encrypted with a Google key nobody else can read. F-Droid's scanner
    // refuses it — a blob in the package that cannot be inspected is exactly
    // what a reproducible, auditable build must not contain.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

}

dependencies {
    implementation(projects.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}

// Copy the release artifacts to stable, shareable names under
// app/build/outputs/maze-connect/. AGP 9 dropped the variant-rename API, so
// a copy step wired to the release tasks is the reliable way to get names
// that do not change between builds.
val distDir = rootProject.layout.projectDirectory.dir("dist")

val version = android.defaultConfig.versionName

val copyReleaseApk = tasks.register<Copy>("copyReleaseApk") {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(distDir)
    rename { "maze-connect-$version.apk" }
}

val copyReleaseAab = tasks.register<Copy>("copyReleaseAab") {
    from(layout.buildDirectory.dir("outputs/bundle/release")) { include("*.aab") }
    into(distDir)
    rename { "maze-connect-$version.aab" }
}

// AGP registers these lazily, so match by name rather than named().
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApk) }
tasks.matching { it.name == "bundleRelease" }.configureEach { finalizedBy(copyReleaseAab) }
