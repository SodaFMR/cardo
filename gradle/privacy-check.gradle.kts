// Cardo's core promise: no INTERNET permission. Applied from app/build.gradle.kts, which feeds in each
// variant's merged manifest (after every library manifest has been merged in) via
// `extra["registerNoInternetCheck"]`. A script plugin cannot see the AGP classes, so the variant-API
// glue lives in app/build.gradle.kts and everything else lives here.

import java.util.Locale

abstract class VerifyNoInternetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun verify() {
        val offending = mergedManifest.get().asFile.readLines().filter { line ->
            "android.permission.INTERNET" in line && "tools:node=\"remove\"" !in line
        }
        if (offending.isNotEmpty()) {
            throw GradleException(
                "INTERNET permission found in merged manifest of ${variantName.get()}: " +
                    offending.joinToString { it.trim() }
            )
        }
    }
}

val registerNoInternetCheck: (String, Provider<RegularFile>) -> Unit = { variantName, manifest ->
    val cap = variantName.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val verify = tasks.register("verifyNoInternet$cap", VerifyNoInternetTask::class.java) {
        group = "verification"
        description = "Fails if the merged manifest of $variantName requests INTERNET"
        mergedManifest.set(manifest)
        this.variantName.set(variantName)
    }
    tasks.named("check") { dependsOn(verify) }
    // The APK must not be produced (nor assembled/installed) unless the check passes.
    tasks.matching { it.name == "package$cap" || it.name == "assemble$cap" }
        .configureEach { dependsOn(verify) }
}
extra["registerNoInternetCheck"] = registerNoInternetCheck
