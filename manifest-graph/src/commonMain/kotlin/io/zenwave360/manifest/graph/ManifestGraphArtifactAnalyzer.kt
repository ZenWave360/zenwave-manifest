package io.zenwave360.manifest.graph

import io.zenwave360.manifest.ManifestArtifactCatalog
import io.zenwave360.manifest.ResolvedManifestArtifact
import io.zenwave360.manifest.ZenWaveManifest

/** Extension point for artifact formats that contribute semantic nodes and edges to a graph. */
interface ManifestGraphArtifactAnalyzer {
    fun supports(artifact: ResolvedManifestArtifact): Boolean

    suspend fun analyze(context: ManifestGraphArtifactContext): ManifestGraphContribution
}

data class ManifestGraphArtifactContext(
    val manifest: ZenWaveManifest,
    val catalog: ManifestArtifactCatalog,
    val artifact: ResolvedManifestArtifact,
    val content: String,
    val sourceUri: String,
)

data class ManifestGraphContribution(
    val nodes: List<ArchitectureNode> = emptyList(),
    val edges: List<ArchitectureEdge> = emptyList(),
    val diagnostics: List<ArchitectureDiagnostic> = emptyList(),
)
