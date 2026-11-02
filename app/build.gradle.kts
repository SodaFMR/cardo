import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

apply(from = rootProject.file("gradle/privacy-check.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val registerNoInternetCheck = extra["registerNoInternetCheck"] as (String, Provider<RegularFile>) -> Unit
androidComponents {
    onVariants { variant ->
        registerNoInternetCheck(variant.name, variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
}

android {
    namespace = "io.github.sodafmr.cardo"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sodafmr.cardo"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    packaging {
        jniLibs {
            // onnxruntime-android and the sherpa-onnx static AAR both ship their own lib/x86/libonnxruntime.so
            // (different builds, so pickFirsts would be wrong). We only ship arm64-v8a and x86_64, so drop
            // the ABIs we do not target before the native-lib merge sees them.
            excludes += setOf("lib/x86/**", "lib/armeabi-v7a/**")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":brain"))
    implementation(project(":voice"))
    implementation(project(":actions"))
    implementation(libs.onnxruntime.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
