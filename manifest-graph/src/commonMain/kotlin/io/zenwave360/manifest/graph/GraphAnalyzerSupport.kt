package io.zenwave360.manifest.graph

import io.zenwave360.manifest.ResolvedManifestArtifact

internal fun semanticId(artifactId: String, kind: ArchitectureNodeKind, path: String): String =
    ArchitectureGraphIds.semantic(artifactId, kind, path)

internal fun semanticNode(
    context: ManifestGraphArtifactContext,
    id: String,
    kind: ArchitectureNodeKind,
    label: String,
    ownerId: String,
    path: String,
    locations: Map<String, Any?>,
    attributes: Map<String, String> = emptyMap(),
): ArchitectureNode = ArchitectureNode(
    id = id,
    kind = kind,
    label = label,
    ownerId = ownerId,
    attributes = attributes,
    source = source(context, path, locations[path]),
)

internal fun source(context: ManifestGraphArtifactContext, path: String?, location: Any?): ArchitectureSource {
    val coordinates = when (location) {
        is IntArray -> location.toList()
        is List<*> -> location.mapNotNull { (it as? Number)?.toInt() }
        else -> emptyList()
    }
    return ArchitectureSource(
        uri = context.sourceUri,
        artifactOwnerRef = context.artifact.ownerRef,
        artifactId = context.artifact.artifactId,
        semanticPath = path,
        line = coordinates.getOrNull(2),
        column = coordinates.getOrNull(3),
    )
}

internal fun findReferencedArtifacts(
    context: ManifestGraphArtifactContext,
    reference: String,
    type: String? = null,
): List<ResolvedManifestArtifact> {
    val normalized = reference.referencePath()
    return context.catalog.artifacts.filter { candidate ->
        (type == null || candidate.artifact.type == type) && candidate !== context.artifact &&
            matchesArtifactReference(normalized, candidate)
    }
}

private fun matchesArtifactReference(reference: String, artifact: ResolvedManifestArtifact): Boolean {
    val path = artifact.artifact.path.normalizedPath()
    val repository = artifact.repository?.normalizedPath()?.trim('/')
    if (reference == path) return true
    val repositoryPath = repository?.takeIf(String::isNotBlank)?.let { "$it/$path" } ?: return false
    if (reference == repositoryPath || reference.endsWith("/$repositoryPath")) return true
    val boundedReference = "/${reference.trim('/')}/"
    return boundedReference.contains("/${repository.trim('/')}/") && reference.endsWith("/$path")
}

private fun String.normalizedPath(): String = replace('\\', '/').removePrefix("./").trim('/')

private fun String.referencePath(): String {
    val withoutQuery = substringBefore('#').substringBefore('?').replace('\\', '/')
    val schemeIndex = withoutQuery.indexOf("://")
    val path = if (schemeIndex >= 0) {
        withoutQuery.substring(schemeIndex + 3).substringAfter('/', missingDelimiterValue = "")
    } else {
        withoutQuery
    }
    return path.removePrefix("./").trim('/')
}

internal fun ambiguousReference(
    context: ManifestGraphArtifactContext,
    reference: String,
    path: String,
    matches: List<ResolvedManifestArtifact>,
): ArchitectureDiagnostic = ArchitectureDiagnostic(
    message = "Artifact reference '$reference' is ambiguous (${matches.size} matches)",
    severity = ArchitectureDiagnosticSeverity.WARNING,
    code = "ambiguous-artifact-reference",
    source = source(context, path, null),
)

internal fun Any?.asStringMap(): Map<String, Any?> = when (this) {
    is Map<*, *> -> entries.associate { it.key.toString() to it.value }
    else -> emptyMap()
}

internal fun flattenStrings(value: Any?): List<String> = when (value) {
    is String -> listOf(value)
    is Iterable<*> -> value.flatMap(::flattenStrings)
    is Array<*> -> value.flatMap(::flattenStrings)
    else -> emptyList()
}
