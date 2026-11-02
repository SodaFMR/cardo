import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

apply(from = rootProject.file("gradle/privacy-check.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val registerPrivacyCheck = extra["registerPrivacyCheck"] as (String, Provider<RegularFile>) -> Unit
androidComponents {
    onVariants { variant ->
        registerPrivacyCheck(variant.name, variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
}

android {
    namespace = "io.github.sodafmr.cardo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.sodafmr.cardo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // scripts/release.sh passes these on the command line; plain builds get the dev defaults.
        versionCode = (providers.gradleProperty("cardoVersionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("cardoVersionName").orNull ?: "0.1.0-dev"
    }

    // Release signing comes from ~/.cardo-keys/keystore.properties (created by scripts/release.sh, never
    // committed). Without that file the release variant stays unsigned, which is what CI builds.
    val keystoreProps = File(System.getProperty("user.home"), ".cardo-keys/keystore.properties")
    val releaseSigning = if (keystoreProps.isFile) {
        val props = java.util.Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            signingConfig = releaseSigning
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
