package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.ResolvedRef
import io.zenwave360.jsonrefparser.model.SourceLocation

/** Identifies an existing scalar owned by a manifest artifact owner or one of its artifacts. */
sealed interface ManifestScalarTarget {
    data class Owner(val ownerRef: String) : ManifestScalarTarget

    data class Artifact(val ownerRef: String, val path: String) : ManifestScalarTarget
}

/** Replaces the existing scalar [field] on [target] with [value]. */
data class ManifestScalarUpdate(
    val target: ManifestScalarTarget,
    val field: String,
    val value: String,
) {
    init {
        require(field.isNotBlank()) { "field is required" }
        require(value.isSingleLine()) { "scalar value must be a single line" }
    }
}

data class ManifestArtifactVersionUpdate(
    val selector: ManifestArtifactSelector,
    val version: String,
) {
    init {
        require(version.isNotBlank()) { "version is required" }
        require(version.isSingleLine()) { "version must be a single-line scalar" }
    }
}

/** Source document mutation returned only after the complete edited graph is valid. */
data class ManifestDocumentTextUpdate(
    val uri: String,
    val originalText: String,
    val updatedText: String,
)

data class ManifestScalarChange(
    val target: ManifestScalarTarget,
    val field: String,
    val previousValue: String?,
    val value: String,
    val sourceUri: String,
) {
    val changed: Boolean
        get() = previousValue != value
}

data class ManifestScalarEditResult(
    val manifest: ZenWaveManifest,
    val documents: List<ManifestDocumentTextUpdate>,
    val changes: List<ManifestScalarChange>,
)

data class ManifestArtifactVersionChange(
    val selector: ManifestArtifactSelector,
    val artifact: ResolvedManifestArtifact,
    val previousVersion: String,
    val version: String,
    val sourceUri: String,
) {
    val changed: Boolean
        get() = previousVersion != version
}

data class ManifestArtifactVersionEditResult(
    val manifest: ZenWaveManifest,
    val documents: List<ManifestDocumentTextUpdate>,
    val changes: List<ManifestArtifactVersionChange>,
)

/** KMP document source used to read a root manifest and any local external `$ref` documents. */
interface ManifestDocumentReader {
    fun canRead(uri: String): Boolean

    suspend fun read(uri: String): String
}

class ManifestEditException(message: String) : IllegalArgumentException(message)

/**
 * KMP, source-aware editor for an existing manifest graph.
 *
 * All documents are read through a per-operation snapshot. Updates change only existing scalar
 * source ranges and the complete staged graph is parsed and validated before edits are returned.
 */
class ZenWaveManifestEditor(
    private val documentReader: ManifestDocumentReader,
) {
    suspend fun updateArtifactVersions(
        rootManifestUri: String,
        updates: List<ManifestArtifactVersionUpdate>,
    ): ManifestArtifactVersionEditResult {
        require(updates.isNotEmpty()) { "at least one artifact version update is required" }
        require(updates.distinctBy { it.selector }.size == updates.size) {
            "duplicate artifact version update selector"
        }

        val snapshot = SnapshotManifestDocumentReader(documentReader)
        val rootUri = ManifestReferenceResolver.normalizeUri(rootManifestUri)
        val manifestLoader = loader(snapshot)
        val originalRoot = snapshot.read(rootUri)
        val parsed = manifestLoader.parseDocument(rootUri, originalRoot)
        parsed.manifest.requireValid()
        val catalog = ManifestArtifactCatalog.resolve(parsed.manifest, manifestLoader)
        val origins = ManifestOrigins(parsed)

        val replacements = updates.flatMap { update ->
            catalog.resolve(update.selector).artifacts.map { artifact ->
                val selection = origins.selectArtifact(artifact)
                val location = origins.scalarLocation(selection.origin, "version")
                ArtifactVersionReplacement(
                    update = update,
                    artifact = artifact,
                    scalar = ScalarReplacement(
                        field = "version",
                        value = update.version,
                        location = location,
                        previousValue = artifact.version,
                    ),
                )
            }
        }
        requireUniqueLocations(replacements.map { it.scalar })

        val documents = buildDocumentUpdates(snapshot, replacements.map { it.scalar })
        val overlay = OverlayManifestDocumentReader(snapshot, documents.associate { it.uri to it.updatedText })
        val updatedRoot = overlay.read(rootUri)
        val updated = loader(overlay).parseDocument(rootUri, updatedRoot).manifest.requireValid()
        return ManifestArtifactVersionEditResult(
            manifest = updated,
            documents = documents,
            changes = replacements.map {
                ManifestArtifactVersionChange(
                    selector = it.update.selector,
                    artifact = it.artifact,
                    previousVersion = it.scalar.previousValue.orEmpty(),
                    version = it.update.version,
                    sourceUri = it.scalar.location.file,
                )
            },
        )
    }

    suspend fun updateScalars(
        rootManifestUri: String,
        updates: List<ManifestScalarUpdate>,
    ): ManifestScalarEditResult {
        require(updates.isNotEmpty()) { "at least one scalar update is required" }
        require(updates.distinctBy { it.target to it.field }.size == updates.size) {
            "duplicate scalar update target"
        }

        val snapshot = SnapshotManifestDocumentReader(documentReader)
        val rootUri = ManifestReferenceResolver.normalizeUri(rootManifestUri)
        val manifestLoader = loader(snapshot)
        val originalRoot = snapshot.read(rootUri)
        val parsed = manifestLoader.parseDocument(rootUri, originalRoot)
        parsed.manifest.requireValid()
        val origins = ManifestOrigins(parsed)
        val replacements = updates.map { update ->
            val selection = origins.select(parsed.manifest, update.target)
            ScalarUpdateReplacement(
                update,
                ScalarReplacement(
                    field = update.field,
                    value = update.value,
                    location = origins.scalarLocation(selection.origin, update.field),
                    previousValue = selection.value(update.field),
                ),
            )
        }
        requireUniqueLocations(replacements.map { it.scalar })

        val documents = buildDocumentUpdates(snapshot, replacements.map { it.scalar })
        val overlay = OverlayManifestDocumentReader(snapshot, documents.associate { it.uri to it.updatedText })
        val updatedRoot = overlay.read(rootUri)
        val updated = loader(overlay).parseDocument(rootUri, updatedRoot).manifest.requireValid()
        return ManifestScalarEditResult(
            manifest = updated,
            documents = documents,
            changes = replacements.map {
                ManifestScalarChange(
                    target = it.update.target,
                    field = it.update.field,
                    previousValue = it.scalar.previousValue,
                    value = it.update.value,
                    sourceUri = it.scalar.location.file,
                )
            },
        )
    }

    private fun loader(reader: ManifestDocumentReader): ZenWaveManifestLoader =
        ZenWaveManifestLoader(documentLoaders = listOf(ReaderDocumentLoader(reader)))
}

private class ReaderDocumentLoader(
    private val reader: ManifestDocumentReader,
) : DocumentLoader {
    override fun canLoad(uri: String): Boolean = reader.canRead(uri)

    override suspend fun load(uri: String): String = reader.read(uri)
}

private class SnapshotManifestDocumentReader(
    private val delegate: ManifestDocumentReader,
) : ManifestDocumentReader {
    private val documents = mutableMapOf<String, String>()

    override fun canRead(uri: String): Boolean = delegate.canRead(ManifestReferenceResolver.normalizeUri(uri))

    override suspend fun read(uri: String): String {
        val normalized = ManifestReferenceResolver.normalizeUri(uri)
        documents[normalized]?.let { return it }
        return delegate.read(normalized).also { documents[normalized] = it }
    }
}

private class OverlayManifestDocumentReader(
    private val delegate: ManifestDocumentReader,
    private val updates: Map<String, String>,
) : ManifestDocumentReader {
    override fun canRead(uri: String): Boolean =
        ManifestReferenceResolver.normalizeUri(uri) in updates || delegate.canRead(uri)

    override suspend fun read(uri: String): String =
        updates[ManifestReferenceResolver.normalizeUri(uri)] ?: delegate.read(uri)
}

private data class ManifestOrigin(val uri: String, val pointer: String) {
    fun child(key: String): ManifestOrigin = copy(pointer = "$pointer/${escapePointerToken(key)}")
}

private data class SelectedManifestNode(
    val origin: ManifestOrigin,
    val fields: Map<String, Any?>,
) {
    fun value(field: String): String? = fields[field]?.toString()
}

private class ManifestOrigins(
    private val parsed: ParsedManifestDocument,
) {
    private val referenceOrigins: List<Pair<Any?, ManifestOrigin>> = parsed.document.resolvedRefs.mapNotNull { ref ->
        ref.externalOrigin()?.let { origin -> ref.replacedValue to origin }
    }

    @Suppress("UNCHECKED_CAST")
    fun select(manifest: ZenWaveManifest, target: ManifestScalarTarget): SelectedManifestNode {
        val ownerRef = when (target) {
            is ManifestScalarTarget.Owner -> target.ownerRef
            is ManifestScalarTarget.Artifact -> target.ownerRef
        }
        val owner = manifest.artifactOwners.filter { it.artifactOwnerRef == ownerRef }.singleOrNull()
            ?: throw ManifestEditException("expected exactly one artifact owner with ref '$ownerRef'")
        val ownerNode = ownerNode(owner)
        return when (target) {
            is ManifestScalarTarget.Owner -> ownerNode
            is ManifestScalarTarget.Artifact -> {
                val indexes = owner.artifacts.mapIndexedNotNull { index, artifact ->
                    index.takeIf { artifact.path == target.path }
                }
                if (indexes.size != 1) {
                    throw ManifestEditException(
                        "expected exactly one artifact at '${target.path}' for owner '$ownerRef'",
                    )
                }
                artifactNode(ownerNode, indexes.single(), target.path)
            }
        }
    }

    fun selectArtifact(artifact: ResolvedManifestArtifact): SelectedManifestNode =
        artifactNode(ownerNode(artifact.owner), artifact.artifactIndex, artifact.artifact.path)

    fun scalarLocation(origin: ManifestOrigin, field: String): SourceLocation =
        parsed.document.documentLocations[origin.uri]?.get(origin.child(field).pointer)
            ?: throw ManifestEditException("source location for '$field' at ${origin.uri}${origin.pointer} was not found")

    @Suppress("UNCHECKED_CAST")
    private fun artifactNode(ownerNode: SelectedManifestNode, index: Int, path: String): SelectedManifestNode {
        val artifacts = ownerNode.fields["artifacts"] as? List<*>
            ?: throw ManifestEditException("owner has no artifacts source mapping")
        val rawArtifact = artifacts.getOrNull(index) as? Map<String, Any?>
            ?: throw ManifestEditException("artifact '$path' has no source mapping")
        val artifactsOrigin = child(ownerNode.origin, "artifacts", artifacts)
        return SelectedManifestNode(child(artifactsOrigin, index.toString(), rawArtifact), rawArtifact)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ownerNode(owner: ManifestArtifactOwner): SelectedManifestNode = when (owner) {
        is ManifestDomain -> domainNode(owner)
        is ManifestService -> serviceNode(owner)
    }

    @Suppress("UNCHECKED_CAST")
    private fun domainNode(domain: ManifestDomain): SelectedManifestNode {
        val root = parsed.document.schema
        val domains = root["domains"] as? Map<String, Any?>
            ?: throw ManifestEditException("manifest has no domains source mapping")
        val rawDomain = domains[domain.key] as? Map<String, Any?>
            ?: throw ManifestEditException("domain '${domain.key}' has no source mapping")
        val domainsOrigin = child(ManifestOrigin(parsed.manifest.uri, ""), "domains", domains)
        return SelectedManifestNode(child(domainsOrigin, domain.key, rawDomain), rawDomain)
    }

    @Suppress("UNCHECKED_CAST")
    private fun serviceNode(service: ManifestService): SelectedManifestNode {
        val domainNode = domainNode(parsed.manifest.domains.first { it.key == service.domainKey })
        val serviceContainer: Map<String, Any?>
        val serviceContainerOrigin: ManifestOrigin
        if (service.subdomainKey == null) {
            serviceContainer = domainNode.fields["services"] as? Map<String, Any?>
                ?: throw ManifestEditException("domain '${service.domainKey}' has no services source mapping")
            serviceContainerOrigin = child(domainNode.origin, "services", serviceContainer)
        } else {
            val subdomains = domainNode.fields["subdomains"] as? Map<String, Any?>
                ?: throw ManifestEditException("domain '${service.domainKey}' has no subdomains source mapping")
            val subdomain = subdomains[service.subdomainKey] as? Map<String, Any?>
                ?: throw ManifestEditException("subdomain '${service.subdomainKey}' has no source mapping")
            val subdomainOrigin = child(child(domainNode.origin, "subdomains", subdomains), service.subdomainKey, subdomain)
            serviceContainer = subdomain["services"] as? Map<String, Any?>
                ?: throw ManifestEditException("subdomain '${service.subdomainKey}' has no services source mapping")
            serviceContainerOrigin = child(subdomainOrigin, "services", serviceContainer)
        }
        val rawService = serviceContainer[service.serviceKey] as? Map<String, Any?>
            ?: throw ManifestEditException("service '${service.serviceKey}' has no source mapping")
        return SelectedManifestNode(child(serviceContainerOrigin, service.serviceKey, rawService), rawService)
    }

    private fun child(parent: ManifestOrigin, key: String, value: Any?): ManifestOrigin =
        referenceOrigins.firstOrNull { (resolved, _) -> resolved === value }?.second ?: parent.child(key)
}

private data class ScalarReplacement(
    val field: String,
    val value: String,
    val location: SourceLocation,
    val previousValue: String?,
)

private data class ScalarUpdateReplacement(
    val update: ManifestScalarUpdate,
    val scalar: ScalarReplacement,
)

private data class ArtifactVersionReplacement(
    val update: ManifestArtifactVersionUpdate,
    val artifact: ResolvedManifestArtifact,
    val scalar: ScalarReplacement,
)

private data class SourceLocationKey(val file: String, val line: Int, val column: Int)

private fun requireUniqueLocations(replacements: List<ScalarReplacement>) {
    val keys = replacements.map { SourceLocationKey(it.location.file, it.location.line, it.location.column) }
    if (keys.toSet().size != keys.size) throw ManifestEditException("multiple updates resolve to the same scalar")
}

private suspend fun buildDocumentUpdates(
    reader: ManifestDocumentReader,
    replacements: List<ScalarReplacement>,
): List<ManifestDocumentTextUpdate> = replacements.groupBy { it.location.file }.map { (uri, documentReplacements) ->
    val original = reader.read(uri)
    ManifestDocumentTextUpdate(
        uri = uri,
        originalText = original,
        updatedText = patchDocument(original, documentReplacements),
    )
}.filter { it.originalText != it.updatedText }

private fun patchDocument(text: String, replacements: List<ScalarReplacement>): String {
    val spans = replacements.map { replacement ->
        if (replacement.location.line != replacement.location.endLine) {
            throw ManifestEditException("${replacement.field} must be a single-line scalar")
        }
        val start = textOffset(text, replacement.location.line, replacement.location.column)
        val end = textOffset(text, replacement.location.endLine, replacement.location.endColumn)
        TextSpan(start, end, replacement.value.renderLike(text.substring(start, end)))
    }.sortedByDescending { it.start }
    return spans.fold(text) { result, span -> result.replaceRange(span.start, span.end, span.value) }
}

private data class TextSpan(val start: Int, val end: Int, val value: String)

private fun textOffset(text: String, line: Int, column: Int): Int {
    var offset = 0
    repeat(line) {
        val newline = text.indexOf('\n', offset)
        if (newline < 0) throw ManifestEditException("source location line $line is outside its document")
        offset = newline + 1
    }
    return offset + column
}

private fun String.renderLike(original: String): String = when {
    original.startsWith('"') -> "\"${escapeDoubleQuoted()}\""
    original.startsWith('\'') -> "'${replace("'", "''")}'"
    matches(Regex("[A-Za-z0-9._+-]+")) -> this
    else -> "\"${escapeDoubleQuoted()}\""
}

private fun String.escapeDoubleQuoted(): String = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\t", "\\t")

private fun String.isSingleLine(): Boolean = '\n' !in this && '\r' !in this

private fun ResolvedRef.externalOrigin(): ManifestOrigin? {
    val uri = targetUri ?: return null
    return ManifestOrigin(uri, refString.substringAfter('#', ""))
}

private fun escapePointerToken(token: String): String = token.replace("~", "~0").replace("/", "~1")
