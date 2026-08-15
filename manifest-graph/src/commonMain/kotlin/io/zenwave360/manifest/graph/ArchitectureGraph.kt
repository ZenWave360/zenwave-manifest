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
    ZFL_SYSTEM, ZFL_FLOW, ZFL_OPERATION, ZFL_STEP, ZFL_EVENT, ZFL_OUTCOME,
    ZDL_ENTITY, API_OPERATION, CHANNEL, MESSAGE,
}

enum class ArchitectureEdgeKind {
    CONTAINS, DECLARES_ARTIFACT, DECLARES_DOMAIN, DEFINES,
    INVOKES, EMITS, CONSUMES, REFERENCES_API, TRIGGERS,
    OCCURRENCE_OF, RESOLVES_TO, BINDS_TO, RESPONDS, COMPENSATES, RESULTS_IN,
}

object ArchitectureBindingAttributes {
    const val ROLE = "role"
    const val TRANSPORT = "transport"
    const val MESSAGE_KIND = "messageKind"
    const val DIRECTION = "direction"
    const val OPERATION_ID = "operationId"
    const val METHOD = "method"
    const val PATH = "path"
    const val CHANNEL_KEY = "channelKey"
    const val ADDRESS = "address"
}

object ArchitectureBindingValues {
    const val ROLE_INVOCATION = "invocation"
    const val ROLE_TRIGGER = "trigger"
    const val ROLE_EMISSION = "emission"
    const val ROLE_RESPONSE = "response"
    const val TRANSPORT_OPENAPI = "openapi"
    const val TRANSPORT_ASYNCAPI = "asyncapi"
    const val KIND_COMMAND = "command"
    const val KIND_QUERY = "query"
    const val KIND_EVENT = "event"
    const val DIRECTION_SEND = "send"
    const val DIRECTION_RECEIVE = "receive"
}

enum class ArchitectureBindingRole(val wireValue: String) {
    INVOCATION(ArchitectureBindingValues.ROLE_INVOCATION),
    TRIGGER(ArchitectureBindingValues.ROLE_TRIGGER),
    EMISSION(ArchitectureBindingValues.ROLE_EMISSION),
    RESPONSE(ArchitectureBindingValues.ROLE_RESPONSE),
}

enum class ArchitectureBindingTransport(val wireValue: String) {
    OPENAPI(ArchitectureBindingValues.TRANSPORT_OPENAPI),
    ASYNCAPI(ArchitectureBindingValues.TRANSPORT_ASYNCAPI),
}

enum class ArchitectureBindingMessageKind(val wireValue: String) {
    COMMAND(ArchitectureBindingValues.KIND_COMMAND),
    QUERY(ArchitectureBindingValues.KIND_QUERY),
    EVENT(ArchitectureBindingValues.KIND_EVENT),
}

enum class ArchitectureBindingDirection(val wireValue: String) {
    SEND(ArchitectureBindingValues.DIRECTION_SEND),
    RECEIVE(ArchitectureBindingValues.DIRECTION_RECEIVE),
}

data class ArchitectureOperationBinding(
    val edge: ArchitectureEdge,
    val role: ArchitectureBindingRole,
    val transport: ArchitectureBindingTransport,
    val messageKind: ArchitectureBindingMessageKind,
    val direction: ArchitectureBindingDirection,
    val operationId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val channelKey: String? = null,
    val address: String? = null,
) {
    companion object {
        @JvmStatic
        fun from(edge: ArchitectureEdge): ArchitectureOperationBinding? {
            if (edge.kind != ArchitectureEdgeKind.BINDS_TO) return null
            val attributes = edge.attributes
            val role = ArchitectureBindingRole.entries.find {
                it.wireValue == attributes[ArchitectureBindingAttributes.ROLE]
            } ?: return null
            val transport = ArchitectureBindingTransport.entries.find {
                it.wireValue == attributes[ArchitectureBindingAttributes.TRANSPORT]
            } ?: return null
            val messageKind = ArchitectureBindingMessageKind.entries.find {
                it.wireValue == attributes[ArchitectureBindingAttributes.MESSAGE_KIND]
            } ?: return null
            val direction = ArchitectureBindingDirection.entries.find {
                it.wireValue == attributes[ArchitectureBindingAttributes.DIRECTION]
            } ?: return null
            return ArchitectureOperationBinding(
                edge, role, transport, messageKind, direction,
                attributes[ArchitectureBindingAttributes.OPERATION_ID],
                attributes[ArchitectureBindingAttributes.METHOD],
                attributes[ArchitectureBindingAttributes.PATH],
                attributes[ArchitectureBindingAttributes.CHANNEL_KEY],
                attributes[ArchitectureBindingAttributes.ADDRESS],
            )
        }
    }
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
    val description: String? = null,
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

    fun resolvedMethod(zflOperationId: String): ArchitectureNode? =
        outgoing(zflOperationId, ArchitectureEdgeKind.RESOLVES_TO)
            .mapNotNull { nodesById[it.target] }
            .singleOrNull { it.kind == ArchitectureNodeKind.ZDL_METHOD }

    @JvmOverloads
    fun operationBindings(methodNodeId: String, role: String? = null): List<ArchitectureEdge> {
        val sources = buildSet {
            add(methodNodeId)
            outgoing(methodNodeId, ArchitectureEdgeKind.EMITS).forEach { add(it.target) }
        }
        return sources.flatMap { outgoing(it, ArchitectureEdgeKind.BINDS_TO) }
            .filter { edge ->
                val binding = ArchitectureOperationBinding.from(edge) ?: return@filter false
                role == null || binding.role.wireValue == role
            }
            .distinctBy { it.id }
    }

    fun operationOccurrences(zflOperationId: String): List<ArchitectureNode> =
        incoming(zflOperationId, ArchitectureEdgeKind.OCCURRENCE_OF)
            .mapNotNull { nodesById[it.source] }
            .sortedBy { it.attributes["occurrenceIndex"]?.toIntOrNull() ?: Int.MAX_VALUE }

    fun flowOutcomes(flowId: String): List<ArchitectureNode> =
        outgoing(flowId, ArchitectureEdgeKind.CONTAINS)
            .mapNotNull { nodesById[it.target] }
            .filter { it.kind == ArchitectureNodeKind.ZFL_OUTCOME }

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
    fun channel(ownerRef: String, artifactId: String, channelKey: String): String =
        semantic(artifact(ownerRef, artifactId), ArchitectureNodeKind.CHANNEL, "channels.$channelKey")

    @JvmStatic
    fun apiOperation(ownerRef: String, artifactId: String, operationId: String): String =
        semantic(artifact(ownerRef, artifactId), ArchitectureNodeKind.API_OPERATION, "operations.$operationId")

    @JvmStatic
    fun zdlMethod(ownerRef: String, artifactId: String, serviceName: String, methodName: String): String =
        semantic(artifact(ownerRef, artifactId), ArchitectureNodeKind.ZDL_METHOD,
            "services.$serviceName.methods.$methodName")

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
