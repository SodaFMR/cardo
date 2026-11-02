// Cardo's core promise: nothing leaves the phone. Applied from app/build.gradle.kts, which feeds in each
// variant's merged manifest (after every library manifest has been merged in) via
// `extra["registerPrivacyCheck"]`. A script plugin cannot see the AGP classes, so the variant-API glue
// lives in app/build.gradle.kts and everything else lives here.
//
// Three checks per variant:
//  1. the merged manifest holds no network permission;
//  2. the merged manifest declares no telemetry/analytics/crash-reporting component;
//  3. the manifest-merger report shows no library that asked for a network permission and was silently
//     stripped by our `tools:node="remove"`, unless that library is on the allowlist below. Silent stripping
//     would otherwise hide a new dependency that wants the network.

import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

val forbiddenPermissions = listOf(
    "INTERNET",
    "ACCESS_NETWORK_STATE",
    "ACCESS_WIFI_STATE",
    "CHANGE_NETWORK_STATE",
    "CHANGE_WIFI_STATE",
).map { "android.permission.$it" }

val telemetryComponentPattern = "(?i)(telemetry|analytics|crashlytics|firebase|sentry|bugsnag|appcenter)"

// group:artifact -> why its stripped network permission is acceptable.
val allowlistedStrippers = mapOf(
    "com.microsoft.onnxruntime:onnxruntime-android" to
        "requests INTERNET only for its telemetry client, which we remove",
)

abstract class VerifyPrivacyTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergerReport: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val networkPermissions: ListProperty<String>

    @get:Input
    abstract val telemetryPattern: Property<String>

    @get:Input
    abstract val allowlistedLibraries: SetProperty<String>

    /** Network permissions a library asked for that the merger REJECTED, as (library, permission) pairs. */
    fun rejectedNetworkPermissions(report: List<String>, networkPermissions: List<String>): List<Pair<String, String>> {
        val rejected = mutableListOf<Pair<String, String>>()
        var permission: String? = null
        for (line in report) {
            if (line.isEmpty() || line[0].isWhitespace()) continue
            // Report layout: an unindented element header such as `uses-permission#android.permission.INTERNET`,
            // then unindented `ADDED from ...` / `MERGED from ...` / `REJECTED from [lib] path` lines.
            if (line.startsWith("REJECTED from")) {
                val current = permission ?: continue
                val library = Regex("^REJECTED from \\[([^\\]]+)]").find(line)?.groupValues?.get(1)
                    ?: line.removePrefix("REJECTED from").trim()
                rejected += library to current
            } else if (Regex("^[a-z][a-z0-9-]*(#.*)?$").matches(line)) {
                permission = Regex("^uses-permission[^#]*#(.+)$").find(line)?.groupValues?.get(1)
                    ?.takeIf { it in networkPermissions }
            }
        }
        return rejected
    }

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        val manifest = mergedManifest.get().asFile

        val text = manifest.readText()
        for (permission in networkPermissions.get()) {
            if (permission in text) problems += "network permission $permission in merged manifest"
        }

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val pattern = Regex(telemetryPattern.get())
        for (tag in listOf("provider", "service", "receiver")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as org.w3c.dom.Element).getAttribute("android:name")
                if (pattern.containsMatchIn(name)) problems += "<$tag> $name looks like telemetry/analytics"
            }
        }

        val allowed = allowlistedLibraries.get()
        for ((library, permission) in rejectedNetworkPermissions(mergerReport.get().asFile.readLines(), networkPermissions.get())) {
            // `library` is `group:artifact:version`; compare on `group:artifact`.
            val coordinates = library.split(':').take(2).joinToString(":")
            if (coordinates !in allowed) {
                problems += "library $library requested $permission (stripped by the merger, but not on the privacy allowlist)"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Privacy check failed for ${variantName.get()}:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
    }
}

val registerPrivacyCheck: (String, Provider<RegularFile>) -> Unit = { variantName, manifest ->
    val cap = variantName.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val verify = tasks.register("verifyPrivacy$cap", VerifyPrivacyTask::class.java) {
        group = "verification"
        description = "Fails if $variantName requests network permissions or bundles telemetry components"
        mergedManifest.set(manifest)
        // Written by the same manifest-merge task that produces the merged manifest.
        mergerReport.set(layout.buildDirectory.file("outputs/logs/manifest-merger-$variantName-report.txt"))
        this.variantName.set(variantName)
        networkPermissions.set(forbiddenPermissions)
        telemetryPattern.set(telemetryComponentPattern)
        allowlistedLibraries.set(allowlistedStrippers.keys)
    }
    tasks.named("check") { dependsOn(verify) }
    // No APK/AAB is produced (nor assembled/installed) unless the check passes.
    tasks.matching { it.name == "package$cap" || it.name == "assemble$cap" || it.name == "bundle$cap" }
        .configureEach { dependsOn(verify) }
}
extra["registerPrivacyCheck"] = registerPrivacyCheck
