package io.zenwave360.manifest

import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Default JVM reader for local files and HTTP(S) manifest documents. */
class JvmManifestDocumentReader : ManifestDocumentReader {
    override fun canRead(uri: String): Boolean = runCatching {
        URI.create(uri).scheme?.lowercase() in SUPPORTED_SCHEMES
    }.getOrDefault(false)

    override suspend fun read(uri: String): String {
        val parsed = URI.create(uri)
        return when (parsed.scheme?.lowercase()) {
            "file" -> Files.readString(Path.of(parsed), StandardCharsets.UTF_8)
            "http", "https" -> parsed.toURL().openStream().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            else -> throw ManifestEditException("Unsupported manifest document URI: $uri")
        }
    }

    private companion object {
        val SUPPORTED_SCHEMES = setOf("file", "http", "https")
    }
}

/** Synchronous JVM facade for source-aware manifest editing. */
@Suppress("unused")
class BlockingZenWaveManifestEditor @JvmOverloads constructor(
    val delegate: ZenWaveManifestEditor = ZenWaveManifestEditor(JvmManifestDocumentReader()),
) {
    fun updateArtifactVersions(
        rootManifestUri: URI,
        updates: List<ManifestArtifactVersionUpdate>,
    ): ManifestArtifactVersionEditResult = updateArtifactVersions(rootManifestUri.toString(), updates)

    fun updateArtifactVersions(
        rootManifestUri: String,
        updates: List<ManifestArtifactVersionUpdate>,
    ): ManifestArtifactVersionEditResult = runBlocking {
        delegate.updateArtifactVersions(rootManifestUri, updates)
    }

    fun updateScalars(
        rootManifestUri: URI,
        updates: List<ManifestScalarUpdate>,
    ): ManifestScalarEditResult = updateScalars(rootManifestUri.toString(), updates)

    fun updateScalars(
        rootManifestUri: String,
        updates: List<ManifestScalarUpdate>,
    ): ManifestScalarEditResult = runBlocking {
        delegate.updateScalars(rootManifestUri, updates)
    }
}
