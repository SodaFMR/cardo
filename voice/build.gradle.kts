plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sodafmr.cardo.voice"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":third-party:sherpa-onnx"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
