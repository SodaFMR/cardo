// Applied from settings.gradle.kts. Fetches every artifact in artifacts.lock.json into place before
// project evaluation (AGP resolves `files(...)` AAR dependencies at configuration time), verifying
// each against its pinned SHA-256. Verified downloads are cached in ~/.cache/cardo-artifacts/<sha256>.

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun fetchArtifact(name: String, url: String, sha256: String, dest: File, cacheDir: File) {
    if (dest.isFile && sha256Of(dest) == sha256) return

    val cached = File(cacheDir, sha256)
    val temp = File(dest.parentFile, dest.name + ".part")
    dest.parentFile.mkdirs()
    try {
        if (cached.isFile) {
            cached.copyTo(temp, overwrite = true)
        } else {
            logger.lifecycle("Downloading $name from $url")
            java.net.URI(url).toURL().openStream().use { Files.copy(it, temp.toPath(), REPLACE_EXISTING) }
        }
        val actual = sha256Of(temp)
        if (actual != sha256) {
            throw GradleException("Checksum mismatch for $name: expected $sha256, got $actual")
        }
        if (!cached.isFile) {
            cacheDir.mkdirs()
            val cacheTemp = File(cacheDir, "$sha256.part")
            temp.copyTo(cacheTemp, overwrite = true)
            Files.move(cacheTemp.toPath(), cached.toPath(), REPLACE_EXISTING)
        }
        Files.move(temp.toPath(), dest.toPath(), REPLACE_EXISTING)
    } finally {
        temp.delete()
    }
}

val artifactsRoot = settingsDir
val artifactsCache = File(System.getProperty("user.home"), ".cache/cardo-artifacts")
val artifactsLock = JsonSlurper().parse(File(artifactsRoot, "artifacts.lock.json")) as Map<*, *>
for (entry in artifactsLock["artifacts"] as List<*>) {
    val artifact = entry as Map<*, *>
    fetchArtifact(
        name = artifact["name"] as String,
        url = artifact["url"] as String,
        sha256 = artifact["sha256"] as String,
        dest = File(artifactsRoot, artifact["dest"] as String),
        cacheDir = artifactsCache,
    )
}
