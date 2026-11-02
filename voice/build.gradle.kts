plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sodafmr.cardo.voice"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(files("$rootDir/third_party/sherpa-onnx-static-2.0.1.aar"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
