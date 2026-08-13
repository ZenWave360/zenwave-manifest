package io.zenwave360.manifest.graph

import io.zenwave360.manifest.ManifestArtifactCatalog
import io.zenwave360.manifest.ApiConsumptionOptions
import io.zenwave360.manifest.ManifestApiConsumptions
import io.zenwave360.manifest.ManifestConsumerIndex
import io.zenwave360.manifest.ManifestDiagnosticSeverity
import io.zenwave360.manifest.ManifestDomain
import io.zenwave360.manifest.ManifestService
import io.zenwave360.manifest.ResolvedManifestArtifact
import io.zenwave360.manifest.ZenWaveManifest
import io.zenwave360.manifest.ZenWaveManifestLoader
import kotlin.jvm.JvmOverloads

class ArchitectureGraphBuilder @JvmOverloads constructor(
    private val loader: ZenWaveManifestLoader = ZenWaveManifestLoader(),
    private val analyzers: List<ManifestGraphArtifactAnalyzer> = defaultArtifactAnalyzers(),
) {
    suspend fun build(
        manifest: ZenWaveManifest,
        options: ArchitectureGraphBuildOptions = ArchitectureGraphBuildOptions(),
    ): ArchitectureGraphResult {
        val nodes = linkedMapOf<String, ArchitectureNode>()
        val edges = linkedMapOf<String, ArchitectureEdge>()
        val diagnostics = manifest.diagnostics.mapTo(mutableListOf()) { diagnostic ->
            ArchitectureDiagnostic(
                message = diagnostic.message,
                severity = when (diagnostic.severity) {
                    ManifestDiagnosticSeverity.ERROR -> ArchitectureDiagnosticSeverity.ERROR
                    ManifestDiagnosticSeverity.WARNING -> ArchitectureDiagnosticSeverity.WARNING
                },
                code = diagnostic.code ?: "manifest-diagnostic",
                source = ArchitectureSource(manifest.uri, semanticPath = diagnostic.location),
            )
        }

        addManifestStructure(manifest, nodes, edges)

        val catalog = try {
            ManifestArtifactCatalog.resolve(manifest, loader)
        } catch (error: Exception) {
            diagnostics += ArchitectureDiagnostic(
                message = error.message ?: "Cannot resolve manifest artifacts",
                severity = ArchitectureDiagnosticSeverity.ERROR,
                code = "artifact-catalog-failed",
                source = ArchitectureSource(manifest.uri),
            )
            return ArchitectureGraphResult(ArchitectureGraph(nodes.values.toList(), edges.values.toList()), diagnostics)
        }

        catalog.artifacts.forEach { artifact -> addArtifact(artifact, manifest, nodes, edges) }

        val apiConsumptions = if (options.includeDeclaredConsumers || options.includeApiConsumptions) {
            ManifestApiConsumptions.build(
                manifest,
                loader,
                ApiConsumptionOptions(
                    loadOptions = options.loadOptions,
                    rules = options.consumptionRules,
                    legacyAddressMatching = options.legacyAddressMatching,
                ),
            ).also { result -> diagnostics += result.diagnostics.map { it.toArchitectureDiagnostic(manifest.uri) } }
        } else {
            null
        }

        if (options.includeDeclaredConsumers && apiConsumptions != null) {
            addDeclaredConsumers(apiConsumptions.consumerIndex, manifest, nodes, edges)
        }
        if (options.includeApiConsumptions && apiConsumptions != null) {
            merge(
                ApiConsumptionGraphContributor.contribute(apiConsumptions, catalog, manifest.uri),
                nodes,
                edges,
                diagnostics,
            )
        }

        for (artifact in catalog.artifacts) {
            if (options.artifactTypes.isNotEmpty() && artifact.artifact.type !in options.artifactTypes) continue
            val matchingAnalyzers = analyzers.filter { it.supports(artifact) }
            if (matchingAnalyzers.isEmpty()) continue
            val loaded = loader.loadArtifactResult(manifest, artifact.owner, artifact.artifact, options.loadOptions)
            val content = loaded.content
            val resource = loaded.resource
            if (content == null || resource == null) {
                diagnostics += ArchitectureDiagnostic(
                    message = loaded.errorMessage ?: "Cannot load artifact '${artifact.artifact.path}'",
                    severity = ArchitectureDiagnosticSeverity.WARNING,
                    code = "artifact-load-failed",
                    source = artifactSource(manifest.uri, artifact),
                )
                continue
            }
            val context = ManifestGraphArtifactContext(
                manifest = manifest,
                catalog = catalog,
                artifact = artifact,
                content = content,
                sourceUri = resource.referenceUri(),
            )
            matchingAnalyzers.forEach { analyzer ->
                val contribution = try {
                    analyzer.analyze(context)
                } catch (error: Exception) {
                    diagnostics += ArchitectureDiagnostic(
                        message = error.message ?: "Analyzer failed for '${artifact.artifact.path}'",
                        severity = ArchitectureDiagnosticSeverity.WARNING,
                        code = "artifact-analysis-failed",
                        source = artifactSource(resource.referenceUri(), artifact),
                    )
                    return@forEach
                }
                merge(contribution, nodes, edges, diagnostics)
            }
        }

        linkCrossArtifactEvidence(nodes, edges, diagnostics)

        val unresolvedSemanticInvocations = edges.values.filter { edge ->
            edge.kind == ArchitectureEdgeKind.INVOKES &&
                edge.provenance == ArchitectureProvenanceKind.INFERRED &&
                edge.target !in nodes
        }
        unresolvedSemanticInvocations.forEach { edge ->
            diagnostics += ArchitectureDiagnostic(
                message = "ZFL invocation '${edge.source}' cannot resolve ZDL method '${edge.target}'",
                severity = ArchitectureDiagnosticSeverity.WARNING,
                code = "unresolved-zdl-method-reference",
                source = edge.sourceInfo,
            )
            edges.remove(edge.id)
        }

        val missingTargets = edges.values.filter { it.source !in nodes || it.target !in nodes }
        missingTargets.forEach { edge ->
            diagnostics += ArchitectureDiagnostic(
                message = "Graph edge '${edge.id}' refers to a missing node",
                severity = ArchitectureDiagnosticSeverity.WARNING,
                code = "dangling-graph-edge",
                source = edge.sourceInfo,
            )
            edges.remove(edge.id)
        }

        return ArchitectureGraphResult(
            graph = ArchitectureGraph(nodes.values.toList(), edges.values.toList()),
            diagnostics = diagnostics,
        )
    }

    private fun addManifestStructure(
        manifest: ZenWaveManifest,
        nodes: MutableMap<String, ArchitectureNode>,
        edges: MutableMap<String, ArchitectureEdge>,
    ) {
        manifest.domains.forEach { domain ->
            val domainId = ArchitectureGraphIds.domain(domain.key)
            nodes[domainId] = ArchitectureNode(
                id = domainId,
                kind = ArchitectureNodeKind.DOMAIN,
                label = domain.name ?: domain.id,
                attributes = attributes("key" to domain.key, "version" to domain.version),
                source = ArchitectureSource(manifest.uri, semanticPath = "domains.${domain.key}"),
            )
            domain.subdomains.forEach { subdomain ->
                val subdomainId = ArchitectureGraphIds.subdomain(domain.key, subdomain.key)
                nodes[subdomainId] = ArchitectureNode(
                    id = subdomainId,
                    kind = ArchitectureNodeKind.SUBDOMAIN,
                    label = subdomain.name ?: subdomain.id,
                    ownerId = domainId,
                    attributes = attributes("key" to subdomain.key, "version" to subdomain.version),
                    source = ArchitectureSource(
                        manifest.uri,
                        semanticPath = "domains.${domain.key}.subdomains.${subdomain.key}",
                    ),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, domainId, subdomainId, ArchitectureProvenanceKind.MANIFEST)
                subdomain.services.forEach { service -> addService(manifest, service, subdomainId, nodes, edges) }
            }
            domain.services.forEach { service ->
                val parent = service.subdomainKey?.let { ArchitectureGraphIds.subdomain(domain.key, it) } ?: domainId
                addService(manifest, service, parent, nodes, edges)
            }
        }
        manifest.services.forEach { service ->
            val serviceId = ArchitectureGraphIds.service(service.serviceRef)
            if (serviceId !in nodes) addService(manifest, service, null, nodes, edges)
        }
    }

    private fun addService(
        manifest: ZenWaveManifest,
        service: ManifestService,
        parentId: String?,
        nodes: MutableMap<String, ArchitectureNode>,
        edges: MutableMap<String, ArchitectureEdge>,
    ) {
        val serviceId = ArchitectureGraphIds.service(service.serviceRef)
        nodes[serviceId] = ArchitectureNode(
            id = serviceId,
            kind = ArchitectureNodeKind.SERVICE,
            label = service.name ?: service.id,
            ownerId = parentId,
            attributes = attributes(
                "serviceRef" to service.serviceRef,
                "repository" to service.repository,
                "version" to service.version,
            ),
            source = ArchitectureSource(manifest.uri, semanticPath = service.serviceRef),
        )
        if (parentId != null) addEdge(edges, ArchitectureEdgeKind.CONTAINS, parentId, serviceId, ArchitectureProvenanceKind.MANIFEST)
    }

    private fun addArtifact(
        artifact: ResolvedManifestArtifact,
        manifest: ZenWaveManifest,
        nodes: MutableMap<String, ArchitectureNode>,
        edges: MutableMap<String, ArchitectureEdge>,
    ) {
        val id = artifactNodeId(artifact)
        val ownerId = when (val owner = artifact.owner) {
            is ManifestDomain -> ArchitectureGraphIds.domain(owner.key)
            is ManifestService -> ArchitectureGraphIds.service(owner.serviceRef)
        }
        nodes[id] = ArchitectureNode(
            id = id,
            kind = ArchitectureNodeKind.ARTIFACT,
            label = artifact.artifact.name ?: artifact.artifactId,
            ownerId = ownerId,
            attributes = attributes(
                "artifactId" to artifact.artifactId,
                "type" to artifact.artifact.type,
                "path" to artifact.artifact.path,
                "version" to artifact.artifact.resolvedVersion,
                "repository" to artifact.repository,
            ),
            source = artifactSource(manifest.uri, artifact),
        )
        addEdge(edges, ArchitectureEdgeKind.DECLARES_ARTIFACT, ownerId, id, ArchitectureProvenanceKind.MANIFEST)
    }

    private fun addDeclaredConsumers(
        index: ManifestConsumerIndex,
        manifest: ZenWaveManifest,
        nodes: Map<String, ArchitectureNode>,
        edges: MutableMap<String, ArchitectureEdge>,
    ) {
        index.edges.forEach { consumption ->
            val consumerId = artifactNodeId(consumption.consumerArtifact)
            consumption.providerArtifacts.forEach { provider ->
                val providerId = artifactNodeId(provider)
                if (consumerId in nodes && providerId in nodes) {
                    addEdge(
                        edges,
                        ArchitectureEdgeKind.CONSUMES,
                        consumerId,
                        providerId,
                        ArchitectureProvenanceKind.DECLARED_CONSUMER,
                        discriminator = consumption.reference.raw,
                        attributes = mapOf("consumerReference" to consumption.reference.raw),
                        source = ArchitectureSource(
                            manifest.uri,
                            semanticPath = "${consumption.providerService.serviceRef}.consumers",
                        ),
                    )
                }
            }
        }
    }

    private fun linkCrossArtifactEvidence(
        nodes: MutableMap<String, ArchitectureNode>,
        edges: MutableMap<String, ArchitectureEdge>,
        diagnostics: MutableList<ArchitectureDiagnostic>,
    ) {
        val artifactNodes = nodes.values.filter { it.kind == ArchitectureNodeKind.ARTIFACT }
        val channels = nodes.values.filter { it.kind == ArchitectureNodeKind.CHANNEL }

        nodes.values.filter { it.kind == ArchitectureNodeKind.ZDL_METHOD }.forEach { method ->
            val apiName = method.attributes["asyncapiApi"] ?: return@forEach
            val channelKey = method.attributes["asyncapiChannel"]
            val topic = method.attributes["asyncapiTopic"]
            val apiNode = outgoingEdgeTargets(method.id, ArchitectureEdgeKind.REFERENCES_API, nodes, edges)
                .firstOrNull { it.kind == ArchitectureNodeKind.ZDL_API && (it.label == apiName || it.attributes["name"] == apiName) }
                ?: return@forEach
            val providerArtifact = outgoingEdgeTargets(apiNode.id, ArchitectureEdgeKind.REFERENCES_API, nodes, edges)
                .singleOrNull { it.kind == ArchitectureNodeKind.ARTIFACT }
            val channel = providerArtifact?.let { artifact ->
                channels.filter { it.ownerId == artifact.id }.singleOrNull {
                    (channelKey != null && it.attributes["channelKey"] == channelKey) ||
                        (topic != null && it.attributes["address"] == topic)
                }
            }
            if (channel == null) {
                diagnostics += ArchitectureDiagnostic(
                    message = "ZDL method '${method.label}' references unresolved AsyncAPI channel '${channelKey ?: topic}'",
                    severity = ArchitectureDiagnosticSeverity.WARNING,
                    code = "unresolved-channel-reference",
                    source = method.source,
                )
                return@forEach
            }
            val kind = if (apiNode.attributes["role"] == "client") {
                ArchitectureEdgeKind.CONSUMES
            } else {
                ArchitectureEdgeKind.EMITS
            }
            addEdge(
                edges, kind, method.id, channel.id, ArchitectureProvenanceKind.INFERRED,
                discriminator = apiNode.id,
                attributes = attributes(
                    "api" to apiName,
                    "channel" to channelKey,
                    "topic" to topic,
                ),
                source = method.source,
            )
        }

        nodes.values.filter { it.kind == ArchitectureNodeKind.ZDL_EVENT }.forEach { event ->
            val channelKey = event.attributes["asyncapiChannel"]
            val topic = event.attributes["asyncapiTopic"]
            if (channelKey == null && topic == null) return@forEach
            val zdlArtifact = event.ownerChain(nodes).firstOrNull { it.kind == ArchitectureNodeKind.ARTIFACT }
                ?: return@forEach
            val manifestOwnerId = zdlArtifact.ownerId ?: return@forEach
            val asyncApiArtifactIds = artifactNodes.filter {
                it.ownerId == manifestOwnerId && it.attributes["type"] == "asyncapi"
            }.mapTo(mutableSetOf()) { it.id }
            val channel = channels.filter { it.ownerId in asyncApiArtifactIds }.singleOrNull {
                (channelKey != null && it.attributes["channelKey"] == channelKey) ||
                    (topic != null && it.attributes["address"] == topic)
            }
            if (channel == null) {
                diagnostics += ArchitectureDiagnostic(
                    message = "ZDL event '${event.label}' references unresolved AsyncAPI channel '${channelKey ?: topic}'",
                    severity = ArchitectureDiagnosticSeverity.WARNING,
                    code = "unresolved-channel-reference",
                    source = event.source,
                )
                return@forEach
            }
            addEdge(
                edges, ArchitectureEdgeKind.EMITS, event.id, channel.id, ArchitectureProvenanceKind.INFERRED,
                discriminator = "zdl-event-channel",
                attributes = attributes("channel" to channelKey, "topic" to topic),
                source = event.source,
            )
        }
    }

    private fun merge(
        contribution: ManifestGraphContribution,
        nodes: MutableMap<String, ArchitectureNode>,
        edges: MutableMap<String, ArchitectureEdge>,
        diagnostics: MutableList<ArchitectureDiagnostic>,
    ) {
        contribution.nodes.forEach { node ->
            val existing = nodes[node.id]
            if (existing != null && existing != node) {
                diagnostics += ArchitectureDiagnostic(
                    message = "Analyzer produced conflicting node id '${node.id}'",
                    severity = ArchitectureDiagnosticSeverity.WARNING,
                    code = "duplicate-graph-node",
                    source = node.source,
                )
            } else {
                nodes[node.id] = node
            }
        }
        contribution.edges.forEach { edge ->
            val existing = edges[edge.id]
            if (existing != null && existing != edge) {
                diagnostics += ArchitectureDiagnostic(
                    message = "Analyzer produced conflicting edge id '${edge.id}'",
                    severity = ArchitectureDiagnosticSeverity.WARNING,
                    code = "duplicate-graph-edge",
                    source = edge.sourceInfo,
                )
            } else {
                edges[edge.id] = edge
            }
        }
        diagnostics += contribution.diagnostics
    }
}

private fun io.zenwave360.manifest.ManifestDiagnostic.toArchitectureDiagnostic(manifestUri: String): ArchitectureDiagnostic =
    ArchitectureDiagnostic(
        message = message,
        severity = when (severity) {
            ManifestDiagnosticSeverity.ERROR -> ArchitectureDiagnosticSeverity.ERROR
            ManifestDiagnosticSeverity.WARNING -> ArchitectureDiagnosticSeverity.WARNING
        },
        code = code ?: "api-consumption-diagnostic",
        source = ArchitectureSource(manifestUri, semanticPath = location),
    )

private fun outgoingEdgeTargets(
    sourceId: String,
    kind: ArchitectureEdgeKind,
    nodes: Map<String, ArchitectureNode>,
    edges: Map<String, ArchitectureEdge>,
): List<ArchitectureNode> = edges.values.filter { it.source == sourceId && it.kind == kind }
    .mapNotNull { nodes[it.target] }

private fun ArchitectureNode.ownerChain(nodes: Map<String, ArchitectureNode>): Sequence<ArchitectureNode> = sequence {
    var current: ArchitectureNode? = this@ownerChain
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current.id)) {
        yield(current)
        current = current.ownerId?.let(nodes::get)
    }
}

internal fun artifactNodeId(artifact: ResolvedManifestArtifact): String =
    ArchitectureGraphIds.artifact(artifact.ownerRef, artifact.artifactId)

internal fun artifactSource(uri: String, artifact: ResolvedManifestArtifact, path: String? = null): ArchitectureSource =
    ArchitectureSource(uri, artifact.ownerRef, artifact.artifactId, path)

internal fun addEdge(
    edges: MutableMap<String, ArchitectureEdge>,
    kind: ArchitectureEdgeKind,
    sourceId: String,
    targetId: String,
    provenance: ArchitectureProvenanceKind,
    discriminator: String = "",
    label: String? = null,
    attributes: Map<String, String> = emptyMap(),
    source: ArchitectureSource? = null,
) {
    val id = ArchitectureGraphIds.edge(kind, sourceId, targetId, discriminator)
    edges[id] = ArchitectureEdge(id, kind, sourceId, targetId, label, attributes, provenance, source)
}

internal fun attributes(vararg values: Pair<String, String?>): Map<String, String> =
    values.mapNotNull { (key, value) -> value?.takeIf(String::isNotBlank)?.let { key to it } }.toMap()

private fun defaultArtifactAnalyzers(): List<ManifestGraphArtifactAnalyzer> =
    listOf(ZdlGraphArtifactAnalyzer(), ZflGraphArtifactAnalyzer())
