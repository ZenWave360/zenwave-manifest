package io.zenwave360.manifest.graph

import io.zenwave360.language.zfl.ZflParser
import io.zenwave360.language.zfl.semantic.ZflCallStep
import io.zenwave360.language.zfl.semantic.ZflSemanticAnalyzer
import io.zenwave360.language.zfl.semantic.ZflServiceStep
import io.zenwave360.manifest.ResolvedManifestArtifact

internal class ZflGraphArtifactAnalyzer : ManifestGraphArtifactAnalyzer {
    override fun supports(artifact: ResolvedManifestArtifact): Boolean = artifact.artifact.type == "zfl"

    override suspend fun analyze(context: ManifestGraphArtifactContext): ManifestGraphContribution {
        val model = ZflParser().parseModel(context.content)
        val semantic = ZflSemanticAnalyzer().analyze(model)
        val artifactId = artifactNodeId(context.artifact)
        val nodes = linkedMapOf<String, ArchitectureNode>()
        val edges = linkedMapOf<String, ArchitectureEdge>()
        val diagnostics = model.getProblems().map { problem ->
            ArchitectureDiagnostic(
                message = problem["message"]?.toString() ?: "Invalid ZFL model",
                severity = ArchitectureDiagnosticSeverity.WARNING,
                code = "zfl-problem",
                source = source(context, problem["path"]?.toString(), problem["location"]),
            )
        }.toMutableList()
        diagnostics += semantic.diagnostics.map { diagnostic ->
            ArchitectureDiagnostic(
                message = diagnostic.message,
                severity = when (diagnostic.severity.toString()) {
                    "INFO" -> ArchitectureDiagnosticSeverity.INFO
                    "WARNING" -> ArchitectureDiagnosticSeverity.WARNING
                    else -> ArchitectureDiagnosticSeverity.ERROR
                },
                code = "zfl-semantic",
                source = ArchitectureSource(
                    context.sourceUri,
                    context.artifact.ownerRef,
                    context.artifact.artifactId,
                    diagnostic.sourceRef?.file,
                    diagnostic.sourceRef?.line,
                    diagnostic.sourceRef?.column,
                ),
            )
        }

        val systemArtifacts = mutableMapOf<String, ResolvedManifestArtifact>()
        model.getSystems().forEach { (systemKey, rawValue) ->
            val raw = rawValue.asStringMap()
            val name = raw["name"]?.toString() ?: systemKey
            val path = "systems.$systemKey"
            val systemId = semanticId(artifactId, ArchitectureNodeKind.ZFL_SYSTEM, path)
            nodes[systemId] = semanticNode(
                context, systemId, ArchitectureNodeKind.ZFL_SYSTEM, name, artifactId, path, model.getLocations(),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, systemId, ArchitectureProvenanceKind.ARTIFACT)

            val zdlReference = raw["zdl"]?.toString()
                ?: raw["options"].asStringMap()["zdl"]?.toString()
            if (!zdlReference.isNullOrBlank()) {
                val matches = findReferencedArtifacts(context, zdlReference, "zdl")
                if (matches.size == 1) {
                    val target = matches.single()
                    systemArtifacts[name] = target
                    systemArtifacts[systemKey] = target
                    addEdge(
                        edges, ArchitectureEdgeKind.DECLARES_DOMAIN, systemId, artifactNodeId(target),
                        ArchitectureProvenanceKind.ARTIFACT, source = source(context, path, model.getLocations()[path]),
                        attributes = mapOf("reference" to zdlReference),
                    )
                } else {
                    diagnostics += if (matches.isEmpty()) {
                        ArchitectureDiagnostic(
                            message = "ZFL system '$name' references unresolved ZDL artifact '$zdlReference'",
                            severity = ArchitectureDiagnosticSeverity.WARNING,
                            code = "unresolved-zdl-reference",
                            source = source(context, path, model.getLocations()[path]),
                        )
                    } else ambiguousReference(context, zdlReference, path, matches)
                }
            }
        }

        semantic.flows.forEach { flow ->
            val flowPath = "flows.${flow.name}"
            val flowId = semanticId(artifactId, ArchitectureNodeKind.ZFL_FLOW, flowPath)
            nodes[flowId] = ArchitectureNode(
                id = flowId,
                kind = ArchitectureNodeKind.ZFL_FLOW,
                label = flow.name,
                ownerId = artifactId,
                attributes = attributes(
                    "endOutcomes" to flow.end.endOutcomes.entries.joinToString(";") { (outcome, events) ->
                        "$outcome=${events.joinToString(",")}" 
                    },
                ),
                source = source(context, flowPath, model.getLocations()[flowPath]),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, flowId, ArchitectureProvenanceKind.ARTIFACT)

            val commandIds = flow.commands.associate { command ->
                command.name to semanticId(artifactId, ArchitectureNodeKind.ZFL_STEP, "$flowPath.actions.${command.name}")
            }
            val eventIds = flow.events.associate { event ->
                event.name to semanticId(artifactId, ArchitectureNodeKind.ZFL_EVENT, "$flowPath.events.${event.name}")
            }.toMutableMap()

            flow.events.forEach { event ->
                val eventPath = "$flowPath.events.${event.name}"
                val eventId = eventIds.getValue(event.name)
                nodes[eventId] = ArchitectureNode(
                    id = eventId,
                    kind = ArchitectureNodeKind.ZFL_EVENT,
                    label = event.name,
                    ownerId = flowId,
                    attributes = attributes(
                        "system" to event.system,
                        "service" to event.service,
                        "isStart" to event.isStart.toString(),
                        "isError" to event.isError.toString(),
                    ),
                    source = source(context, eventPath, model.getLocations()[eventPath]),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, eventId, ArchitectureProvenanceKind.ARTIFACT)
            }

            flow.starts.forEach { start ->
                val eventPath = "$flowPath.events.${start.name}"
                val eventId = eventIds.getOrPut(start.name) {
                    semanticId(artifactId, ArchitectureNodeKind.ZFL_EVENT, eventPath)
                }
                if (eventId !in nodes) {
                    nodes[eventId] = ArchitectureNode(
                        id = eventId,
                        kind = ArchitectureNodeKind.ZFL_EVENT,
                        label = start.name,
                        ownerId = flowId,
                        attributes = attributes(
                            "system" to start.system,
                            "isStart" to "true",
                            "isError" to "false",
                        ),
                        source = source(context, eventPath, model.getLocations()[eventPath]),
                    )
                    addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, eventId, ArchitectureProvenanceKind.ARTIFACT)
                }
                val startPath = "$flowPath.starts.${start.name}"
                val startId = semanticId(artifactId, ArchitectureNodeKind.ZFL_STEP, startPath)
                nodes[startId] = ArchitectureNode(
                    id = startId,
                    kind = ArchitectureNodeKind.ZFL_STEP,
                    label = start.name,
                    ownerId = flowId,
                    attributes = attributes(
                        "role" to "start",
                        "actor" to start.actor,
                        "timer" to start.timer,
                        "system" to start.system,
                    ),
                    source = source(context, startPath, model.getLocations()[startPath]),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, startId, ArchitectureProvenanceKind.ARTIFACT)
                addEdge(
                    edges,
                    ArchitectureEdgeKind.TRIGGERS,
                    startId,
                    eventId,
                    ArchitectureProvenanceKind.ARTIFACT,
                    discriminator = "start:${start.name}",
                )
            }

            flow.commands.forEach { command ->
                val commandPath = "$flowPath.actions.${command.name}"
                val commandId = commandIds.getValue(command.name)
                nodes[commandId] = ArchitectureNode(
                    id = commandId,
                    kind = ArchitectureNodeKind.ZFL_STEP,
                    label = command.name,
                    ownerId = flowId,
                    attributes = attributes(
                        "system" to command.system,
                        "service" to command.service,
                        "servicePath" to command.servicePath,
                    ),
                    source = source(context, commandPath, model.getLocations()[commandPath]),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, commandId, ArchitectureProvenanceKind.ARTIFACT)

                command.emits.forEach { emission ->
                    eventIds[emission.eventName]?.let { eventId ->
                        addEdge(
                            edges, ArchitectureEdgeKind.EMITS, commandId, eventId, ArchitectureProvenanceKind.ARTIFACT,
                            discriminator = emission.outcome.orEmpty(), attributes = attributes("outcome" to emission.outcome),
                        )
                    }
                }
                command.steps.filterIsInstance<ZflCallStep>().forEach { call ->
                    commandIds[call.action]?.let { targetId ->
                        addEdge(
                            edges, ArchitectureEdgeKind.INVOKES, commandId, targetId, ArchitectureProvenanceKind.ARTIFACT,
                            attributes = mapOf("async" to call.async.toString()),
                        )
                    }
                    call.handlers.forEach { handler ->
                        handler.action?.let { action ->
                            commandIds[action]?.let { targetId ->
                                addEdge(
                                    edges,
                                    ArchitectureEdgeKind.INVOKES,
                                    commandId,
                                    targetId,
                                    ArchitectureProvenanceKind.ARTIFACT,
                                    discriminator = "handler:${handler.endOutcome}:$action",
                                    attributes = mapOf("outcome" to handler.endOutcome, "role" to "handler"),
                                )
                            }
                        }
                        handler.signal?.takeIf { it.emits }?.events.orEmpty().forEach { eventName ->
                            eventIds[eventName]?.let { targetId ->
                                addEdge(
                                    edges,
                                    ArchitectureEdgeKind.EMITS,
                                    commandId,
                                    targetId,
                                    ArchitectureProvenanceKind.ARTIFACT,
                                    discriminator = "handler:${handler.endOutcome}:$eventName",
                                    attributes = attributes(
                                        "outcome" to (handler.signal?.outcome ?: handler.endOutcome),
                                        "role" to "handler",
                                    ),
                                )
                            }
                        }
                    }
                }

                val serviceStep = command.steps.filterIsInstance<ZflServiceStep>().firstOrNull()
                val systemName = serviceStep?.system ?: command.system
                val serviceName = serviceStep?.service ?: command.service
                val zdlArtifact = systemName?.let(systemArtifacts::get)
                if (zdlArtifact != null && serviceName != null) {
                    val targetKind = ArchitectureNodeKind.ZDL_METHOD
                    val targetPath = "services.$serviceName.methods.${command.name}"
                    val targetId = semanticId(artifactNodeId(zdlArtifact), targetKind, targetPath)
                    addEdge(
                        edges, ArchitectureEdgeKind.INVOKES, commandId, targetId, ArchitectureProvenanceKind.INFERRED,
                        source = source(context, commandPath, model.getLocations()[commandPath]),
                    )
                }
            }

            flow.policies.forEach { policy ->
                val targetId = commandIds[policy.command] ?: return@forEach
                policy.triggers.forEach { trigger ->
                    val triggerId = eventIds[trigger] ?: return@forEach
                    addEdge(
                        edges,
                        ArchitectureEdgeKind.TRIGGERS,
                        triggerId,
                        targetId,
                        ArchitectureProvenanceKind.ARTIFACT,
                        discriminator = "policy:${policy.command}:$trigger",
                        attributes = attributes("condition" to policy.condition),
                    )
                }
            }

        }

        return ManifestGraphContribution(nodes.values.toList(), edges.values.toList(), diagnostics)
    }
}
