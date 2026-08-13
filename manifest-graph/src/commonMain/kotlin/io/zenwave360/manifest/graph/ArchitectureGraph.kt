package io.zenwave360.manifest.graph

import io.zenwave360.manifest.ManifestConsumptionRules
import io.zenwave360.manifest.ManifestLoadOptions
import io.zenwave360.manifest.ZenWaveManifest
import io.zenwave360.manifest.ZenWaveManifestLoader
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

enum class ArchitectureNodeKind {
    DOMAIN, SUBDOMAIN, SERVICE, ARTIFACT,
    ZDL_API, ZDL_SERVICE, ZDL_METHOD, ZDL_EVENT,
    ZFL_SYSTEM, ZFL_FLOW, ZFL_STEP, ZFL_EVENT,
    ZDL_ENTITY, API_OPERATION, CHANNEL, MESSAGE,
}

enum class ArchitectureEdgeKind {
    CONTAINS, DECLARES_ARTIFACT, DECLARES_DOMAIN, DEFINES,
    INVOKES, EMITS, CONSUMES, REFERENCES_API, TRIGGERS,
}

enum class ArchitectureProvenanceKind { MANIFEST, ARTIFACT, DECLARED_CONSUMER, INFERRED }

enum class ArchitectureDiagnosticSeverity { INFO, WARNING, ERROR }

enum class ArchitectureTraversalDirection { OUTGOING, INCOMING, BOTH }

data class ArchitectureSource(
    val uri: String,
    val artifactOwnerRef: String? = null,
    val artifactId: String? = null,
    val semanticPath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class ArchitectureNode(
    val id: String,
    val kind: ArchitectureNodeKind,
    val label: String,
    val ownerId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val source: ArchitectureSource? = null,
)

data class ArchitectureEdge(
    val id: String,
    val kind: ArchitectureEdgeKind,
    val source: String,
    val target: String,
    val label: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val provenance: ArchitectureProvenanceKind = ArchitectureProvenanceKind.INFERRED,
    val sourceInfo: ArchitectureSource? = null,
)

data class ArchitectureDiagnostic(
    val message: String,
    val severity: ArchitectureDiagnosticSeverity,
    val code: String,
    val source: ArchitectureSource? = null,
)

data class ArchitectureGraphResult(
    val graph: ArchitectureGraph,
    val diagnostics: List<ArchitectureDiagnostic> = emptyList(),
)

data class ArchitectureGraph(
    val nodes: List<ArchitectureNode>,
    val edges: List<ArchitectureEdge>,
) {
    val nodesById: Map<String, ArchitectureNode> = nodes.associateBy { it.id }
    private val outgoingById: Map<String, List<ArchitectureEdge>> = edges.groupBy { it.source }
    private val incomingById: Map<String, List<ArchitectureEdge>> = edges.groupBy { it.target }
    private val edgesByKind: Map<ArchitectureEdgeKind, List<ArchitectureEdge>> = edges.groupBy { it.kind }

    fun node(id: String): ArchitectureNode? = nodesById[id]

    fun outgoing(id: String, kind: ArchitectureEdgeKind? = null): List<ArchitectureEdge> =
        outgoingById[id].orEmpty().filter { kind == null || it.kind == kind }

    fun incoming(id: String, kind: ArchitectureEdgeKind? = null): List<ArchitectureEdge> =
        incomingById[id].orEmpty().filter { kind == null || it.kind == kind }

    fun edges(kind: ArchitectureEdgeKind): List<ArchitectureEdge> = edgesByKind[kind].orEmpty()

    /** Finds the manifest service owning a semantic or artifact node, if one exists. */
    fun owningService(nodeId: String): ArchitectureNode? {
        var current = nodesById[nodeId]
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.id)) {
            if (current.kind == ArchitectureNodeKind.SERVICE) return current
            current = current.ownerId?.let(nodesById::get)
        }
        return null
    }

    /** Direct nodes with a typed CONSUMES edge targeting [nodeId]. */
    fun consumersOf(nodeId: String): List<ArchitectureNode> = incoming(nodeId, ArchitectureEdgeKind.CONSUMES)
        .mapNotNull { nodesById[it.source] }
        .distinctBy { it.id }

    /**
     * Breadth-first projection used by LSP cross references and MCP graph tools.
     */
    @JvmOverloads
    fun subgraphFrom(
        rootId: String,
        direction: ArchitectureTraversalDirection = ArchitectureTraversalDirection.OUTGOING,
        kinds: Set<ArchitectureEdgeKind> = emptySet(),
        maxDepth: Int = Int.MAX_VALUE,
    ): ArchitectureGraph {
        val root = nodesById[rootId] ?: return ArchitectureGraph(emptyList(), emptyList())
        val depthLimit = maxDepth.coerceAtLeast(0)
        val visited = linkedSetOf(root.id)
        val selectedEdges = linkedMapOf<String, ArchitectureEdge>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(root.id to 0)
        while (queue.isNotEmpty()) {
            val (nodeId, depth) = queue.removeFirst()
            if (depth >= depthLimit) continue
            val candidates = when (direction) {
                ArchitectureTraversalDirection.OUTGOING -> outgoingById[nodeId].orEmpty()
                ArchitectureTraversalDirection.INCOMING -> incomingById[nodeId].orEmpty()
                ArchitectureTraversalDirection.BOTH -> outgoingById[nodeId].orEmpty() + incomingById[nodeId].orEmpty()
            }.filter { kinds.isEmpty() || it.kind in kinds }
            candidates.forEach { edge ->
                selectedEdges[edge.id] = edge
                val adjacent = if (edge.source == nodeId) edge.target else edge.source
                if (adjacent in nodesById && visited.add(adjacent)) queue.add(adjacent to depth + 1)
            }
        }
        return ArchitectureGraph(visited.mapNotNull(nodesById::get), selectedEdges.values.toList())
    }

    companion object {
        /** Canonical asynchronous entry point for Kotlin/JVM and Kotlin/JS callers. */
        @JvmStatic
        @JvmOverloads
        suspend fun build(
            manifest: ZenWaveManifest,
            loader: ZenWaveManifestLoader = ZenWaveManifestLoader(),
            options: ArchitectureGraphBuildOptions = ArchitectureGraphBuildOptions(),
        ): ArchitectureGraphResult = ArchitectureGraphBuilder(loader).build(manifest, options)
    }
}

data class ArchitectureGraphBuildOptions(
    val loadOptions: ManifestLoadOptions = ManifestLoadOptions(),
    /** Empty means every artifact type supported by a configured analyzer. */
    val artifactTypes: Set<String> = emptySet(),
    val includeDeclaredConsumers: Boolean = true,
    val includeApiConsumptions: Boolean = true,
    val consumptionRules: Map<String, List<String>> = ManifestConsumptionRules.DEFAULT,
    /** Graphs default to declared, verified evidence rather than global legacy address inference. */
    val legacyAddressMatching: Boolean = false,
)

/** Stable, URI-safe identifiers shared by built-in and third-party graph analyzers. */
object ArchitectureGraphIds {
    @JvmStatic
    fun domain(domainKey: String): String = "domain/${segment(domainKey)}"

    @JvmStatic
    fun subdomain(domainKey: String, subdomainKey: String): String =
        "${domain(domainKey)}/subdomain/${segment(subdomainKey)}"

    @JvmStatic
    fun service(serviceRef: String): String = "service/${segment(serviceRef)}"

    @JvmStatic
    fun artifact(ownerRef: String, artifactId: String): String =
        "artifact/${segment(ownerRef)}/${segment(artifactId)}"

    @JvmStatic
    fun semantic(artifactId: String, kind: ArchitectureNodeKind, path: String): String =
        "$artifactId/${kind.name.lowercase()}/${segment(path)}"

    @JvmStatic
    fun edge(kind: ArchitectureEdgeKind, source: String, target: String, discriminator: String = ""): String =
        "edge/${kind.name.lowercase()}/${segment(source)}/${segment(target)}" +
            discriminator.takeIf { it.isNotBlank() }?.let { "/${segment(it)}" }.orEmpty()

    private fun segment(value: String): String = buildString {
        value.forEach { character ->
            when {
                character.isLetterOrDigit() || character in "-._~" -> append(character)
                else -> {
                    append('%')
                    append(character.code.toString(16).uppercase().padStart(4, '0'))
                }
            }
        }
    }
}
