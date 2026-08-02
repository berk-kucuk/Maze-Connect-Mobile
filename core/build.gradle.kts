import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9's built-in Kotlin support means org.jetbrains.kotlin.android is
    // no longer applied separately (matches haze-mobile's app/build.gradle.kts).
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.mazeconnect.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // org.json ships as stubs in the Android SDK jar, which throw on the
    // JVM. Robolectric's reimplementation is what lets the message-layer
    // tests exercise the real parsing code in a plain unit test.
    testImplementation(libs.json)
}
