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

fun download(name: String, url: String, target: File) {
    val connection = java.net.URI(url).toURL().openConnection()
    connection.connectTimeout = 30_000
    connection.readTimeout = 120_000
    try {
        connection.getInputStream().use { Files.copy(it, target.toPath(), REPLACE_EXISTING) }
    } catch (e: java.io.IOException) {
        throw GradleException("Failed to download $name from $url: ${e.message}", e)
    }
}

fun fetchArtifact(name: String, url: String, sha256: String, dest: File, cacheDir: File) {
    if (dest.isFile && sha256Of(dest) == sha256) return

    val cached = File(cacheDir, sha256)
    // Unique per process and call, so concurrent builds never write the same temp file.
    val unique = ".part-${ProcessHandle.current().pid()}-${System.nanoTime()}"
    val temp = File(dest.parentFile, dest.name + unique)
    try {
        dest.parentFile.mkdirs()
        var fromCache = false
        if (cached.isFile) {
            cached.copyTo(temp, overwrite = true)
            if (sha256Of(temp) == sha256) {
                fromCache = true
            } else {
                // A corrupt cache entry must not wedge the build: drop it and download once.
                logger.warn("Cached copy of $name does not match its checksum; deleting it and re-downloading")
                cached.delete()
                temp.delete()
            }
        }
        if (!fromCache) {
            logger.lifecycle("Downloading $name from $url")
            download(name, url, temp)
            val actual = sha256Of(temp)
            if (actual != sha256) {
                throw GradleException("Checksum mismatch for $name: expected $sha256, got $actual")
            }
            cacheDir.mkdirs()
            val cacheTemp = File(cacheDir, "$sha256$unique")
            try {
                temp.copyTo(cacheTemp, overwrite = true)
                Files.move(cacheTemp.toPath(), cached.toPath(), REPLACE_EXISTING)
            } finally {
                cacheTemp.delete()
            }
        }
        Files.move(temp.toPath(), dest.toPath(), REPLACE_EXISTING)
    } catch (e: java.io.IOException) {
        throw GradleException("Failed to fetch $name: ${e.message}", e)
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
