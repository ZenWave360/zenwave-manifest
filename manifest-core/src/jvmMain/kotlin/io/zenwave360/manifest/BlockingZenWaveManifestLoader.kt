package io.zenwave360.manifest

import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * Synchronous JVM facade for Java and other callers that do not use Kotlin coroutines.
 *
 * The multiplatform [ZenWaveManifestLoader] remains the canonical asynchronous API. This
 * facade delegates every blocking operation to the same loader and therefore preserves
 * configured document loaders and archive handling.
 */
@Suppress("unused")
class BlockingZenWaveManifestLoader @JvmOverloads constructor(
    val delegate: ZenWaveManifestLoader = ZenWaveManifestLoader(),
) {
    fun load(uri: URI): ZenWaveManifest = load(uri.toString())

    fun load(uri: String): ZenWaveManifest =
        runBlocking { delegate.load(uri) }

    fun parse(uri: URI, text: String): ZenWaveManifest = parse(uri.toString(), text)

    fun parse(uri: String, text: String): ZenWaveManifest =
        runBlocking { delegate.parse(uri, text) }

    fun loadResourceText(uri: URI): String = loadResourceText(uri.toString())

    fun loadResourceText(uri: String): String =
        runBlocking { delegate.loadResourceText(uri) }

    @JvmOverloads
    fun loadServiceDocs(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<String, String> =
        runBlocking { delegate.loadServiceDocs(manifest, service, options) }

    @JvmOverloads
    fun loadServiceDocResults(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<String, ManifestResourceLoadResult> =
        runBlocking { delegate.loadServiceDocResults(manifest, service, options) }

    @JvmOverloads
    fun loadAvailableServiceDocs(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<String, String> =
        runBlocking { delegate.loadAvailableServiceDocs(manifest, service, options) }

    @JvmOverloads
    fun loadServiceArtifacts(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<ManifestArtifact, String> =
        runBlocking { delegate.loadServiceArtifacts(manifest, service, options) }

    @JvmOverloads
    fun loadArtifactText(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): String =
        runBlocking { delegate.loadArtifactText(manifest, service, artifact, options) }

    @JvmOverloads
    fun loadArtifactText(
        manifest: ZenWaveManifest,
        owner: ManifestArtifactOwner,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): String =
        runBlocking { delegate.loadArtifactText(manifest, owner, artifact, options) }

    @JvmOverloads
    fun loadArtifactResult(
        manifest: ZenWaveManifest,
        owner: ManifestArtifactOwner,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): ManifestResourceLoadResult =
        runBlocking { delegate.loadArtifactResult(manifest, owner, artifact, options) }

    @JvmOverloads
    fun resolveArtifact(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): ManifestResolvedResource =
        runBlocking { delegate.resolveArtifact(manifest, service, artifact, options) }

    @JvmOverloads
    fun resolveArtifact(
        manifest: ZenWaveManifest,
        owner: ManifestArtifactOwner,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): ManifestResolvedResource =
        runBlocking { delegate.resolveArtifact(manifest, owner, artifact, options) }
}
