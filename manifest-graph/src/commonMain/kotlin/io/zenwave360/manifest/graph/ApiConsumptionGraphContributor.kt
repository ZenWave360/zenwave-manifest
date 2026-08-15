package io.zenwave360.manifest.graph

import io.zenwave360.manifest.AsyncApiAction
import io.zenwave360.manifest.ManifestApiConsumptions
import io.zenwave360.manifest.ManifestArtifactCatalog

internal object ApiConsumptionGraphContributor {
    fun contribute(
        consumptions: ManifestApiConsumptions,
        catalog: ManifestArtifactCatalog,
        manifestUri: String,
    ): ManifestGraphContribution {
        val nodes = linkedMapOf<String, ArchitectureNode>()
        val edges = linkedMapOf<String, ArchitectureEdge>()

        catalog.artifacts.filter { it.artifact.type == "asyncapi" }.forEach { artifact ->
            val index = consumptions.channelIndex(artifact) ?: return@forEach
            val artifactId = artifactNodeId(artifact)
            index.channels.values.forEach { channel ->
                val channelPath = "channels.${channel.channelKey}"
                val channelId = ArchitectureGraphIds.channel(artifact.ownerRef, artifact.artifactId, channel.channelKey)
                val channelSource = artifactSource(manifestUri, artifact, channelPath)
                nodes[channelId] = ArchitectureNode(
                    id = channelId,
                    kind = ArchitectureNodeKind.CHANNEL,
                    label = channel.summary ?: channel.channelKey,
                    ownerId = artifactId,
                    description = channel.description,
                    attributes = attributes(
                        "channelKey" to channel.channelKey,
                        "address" to channel.address,
                        "messageKind" to channel.messageKind.name.lowercase(),
                        "summary" to channel.summary,
                        "description" to channel.description,
                        "version" to index.version,
                    ),
                    source = channelSource,
                )
                addEdge(
                    edges, ArchitectureEdgeKind.DEFINES, artifactId, channelId,
                    ArchitectureProvenanceKind.ARTIFACT, source = channelSource,
                )

                channel.operations.forEach { operation ->
                    val operationPath = "operations.${operation.operationId}"
                    val operationId = ArchitectureGraphIds.apiOperation(
                        artifact.ownerRef, artifact.artifactId, operation.operationId,
                    )
                    val operationSource = artifactSource(manifestUri, artifact, operationPath)
                    nodes[operationId] = ArchitectureNode(
                        id = operationId,
                        kind = ArchitectureNodeKind.API_OPERATION,
                        label = operation.operationId,
                        ownerId = artifactId,
                        attributes = mapOf("action" to operation.action.name.lowercase()),
                        source = operationSource,
                    )
                    addEdge(
                        edges, ArchitectureEdgeKind.DEFINES, artifactId, operationId,
                        ArchitectureProvenanceKind.ARTIFACT, source = operationSource,
                    )
                    addEdge(
                        edges,
                        if (operation.action == AsyncApiAction.SEND) ArchitectureEdgeKind.EMITS else ArchitectureEdgeKind.CONSUMES,
                        operationId,
                        channelId,
                        ArchitectureProvenanceKind.ARTIFACT,
                        discriminator = operation.operationId,
                        attributes = mapOf("action" to operation.action.name.lowercase()),
                        source = operationSource,
                    )
                }
            }
        }

        catalog.artifacts.filter { it.artifact.type == "openapi" }.forEach { artifact ->
            val index = consumptions.openApiIndex(artifact) ?: return@forEach
            val artifactId = artifactNodeId(artifact)
            index.operations.forEach { operation ->
                val operationPath = "operations.${operation.operationId}"
                val operationId = ArchitectureGraphIds.apiOperation(
                    artifact.ownerRef, artifact.artifactId, operation.operationId,
                )
                val operationSource = artifactSource(manifestUri, artifact, operationPath)
                nodes[operationId] = ArchitectureNode(
                    id = operationId,
                    kind = ArchitectureNodeKind.API_OPERATION,
                    label = operation.operationId,
                    ownerId = artifactId,
                    description = operation.description ?: operation.summary,
                    attributes = attributes(
                        "operationId" to operation.operationId,
                        "method" to operation.method,
                        "path" to operation.path,
                        "normalizedPath" to operation.normalizedPath,
                        "intent" to operation.intent.name.lowercase(),
                        "version" to index.version,
                        "summary" to operation.summary,
                        "description" to operation.description,
                    ),
                    source = operationSource,
                )
                addEdge(
                    edges, ArchitectureEdgeKind.DEFINES, artifactId, operationId,
                    ArchitectureProvenanceKind.ARTIFACT, source = operationSource,
                )
            }
        }

        consumptions.matches.forEach { match ->
            val consumerArtifactId = artifactNodeId(match.edge.consumerArtifact)
            val operationPath = "operations.${match.consumerOperationId}"
            val operationId = semanticId(consumerArtifactId, ArchitectureNodeKind.API_OPERATION, operationPath)
            val operationSource = artifactSource(manifestUri, match.edge.consumerArtifact, operationPath)
            nodes[operationId] = ArchitectureNode(
                id = operationId,
                kind = ArchitectureNodeKind.API_OPERATION,
                label = match.consumerOperationId,
                ownerId = consumerArtifactId,
                attributes = mapOf("action" to match.consumerAction.name.lowercase()),
                source = operationSource,
            )
            addEdge(
                edges, ArchitectureEdgeKind.DEFINES, consumerArtifactId, operationId,
                ArchitectureProvenanceKind.ARTIFACT, source = operationSource,
            )
            val providerArtifactId = artifactNodeId(match.providerArtifact)
            val channelId = semanticId(
                providerArtifactId,
                ArchitectureNodeKind.CHANNEL,
                "channels.${match.channel.channelKey}",
            )
            addEdge(
                edges,
                ArchitectureEdgeKind.CONSUMES,
                operationId,
                channelId,
                ArchitectureProvenanceKind.INFERRED,
                discriminator = "${match.providerArtifact.ownerRef}#${match.providerArtifact.artifactId}:${match.channel.channelKey}",
                attributes = mapOf(
                    "matchKind" to match.matchKind.name.lowercase().replace('_', '-'),
                    "action" to match.consumerAction.name.lowercase(),
                    "providerOperationId" to match.providerOperationId,
                    "providerAction" to match.providerAction.name.lowercase(),
                ),
                source = operationSource,
            )
        }

        return ManifestGraphContribution(nodes.values.toList(), edges.values.toList())
    }
}
