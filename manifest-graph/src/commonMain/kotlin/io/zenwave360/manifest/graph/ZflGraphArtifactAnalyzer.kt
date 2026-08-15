package io.zenwave360.manifest.graph

import io.zenwave360.language.source.SourceRef
import io.zenwave360.language.zfl.ZflParser
import io.zenwave360.language.zfl.semantic.ZflCallStep
import io.zenwave360.language.zfl.semantic.ZflCommandOccurrence
import io.zenwave360.language.zfl.semantic.ZflSemanticAnalyzer
import io.zenwave360.language.zfl.semantic.ZflServiceStep
import io.zenwave360.manifest.ResolvedManifestArtifact

internal class ZflGraphArtifactAnalyzer : ManifestGraphArtifactAnalyzer {
    override fun supports(artifact: ResolvedManifestArtifact): Boolean = artifact.artifact.type == "zfl"

    override suspend fun analyze(context: ManifestGraphArtifactContext): ManifestGraphContribution {
        val model = ZflParser().parseModel(context.content, context.sourceUri)
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
                source = diagnostic.sourceRef?.toArchitectureSource(context),
            )
        }

        val systemArtifacts = mutableMapOf<String, ResolvedManifestArtifact>()
        val systemServices = mutableMapOf<String, Set<String>>()
        model.getSystems().forEach { (systemKey, rawValue) ->
            val raw = rawValue.asStringMap()
            val name = raw["name"]?.toString() ?: systemKey
            val path = "systems.$systemKey"
            val systemId = semanticId(artifactId, ArchitectureNodeKind.ZFL_SYSTEM, path)
            nodes[systemId] = semanticNode(
                context, systemId, ArchitectureNodeKind.ZFL_SYSTEM, name, artifactId, path, model.getLocations(),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, systemId, ArchitectureProvenanceKind.ARTIFACT)
            systemServices[name] = raw["services"].asStringMap().values.mapNotNull {
                it.asStringMap()["name"]?.toString()
            }.toSet()

            val zdlReference = raw["zdl"]?.toString() ?: raw["options"].asStringMap()["zdl"]?.toString()
            if (!zdlReference.isNullOrBlank()) {
                val matches = findReferencedArtifacts(context, zdlReference, "zdl")
                if (matches.size == 1) {
                    val target = matches.single()
                    systemArtifacts[name] = target
                    systemArtifacts[systemKey] = target
                    addEdge(
                        edges, ArchitectureEdgeKind.DECLARES_DOMAIN, systemId, artifactNodeId(target),
                        ArchitectureProvenanceKind.ARTIFACT,
                        source = source(context, path, model.getLocations()[path]),
                        attributes = mapOf("reference" to zdlReference),
                    )
                } else {
                    diagnostics += ArchitectureDiagnostic(
                        message = if (matches.isEmpty()) {
                            "ZFL system '$name' references unresolved ZDL artifact '$zdlReference'"
                        } else {
                            "ZFL system '$name' references ambiguous ZDL artifact '$zdlReference'"
                        },
                        severity = ArchitectureDiagnosticSeverity.WARNING,
                        code = if (matches.isEmpty()) "unresolved-zfl-zdl-reference" else "ambiguous-zfl-zdl-reference",
                        source = source(context, path, model.getLocations()[path]),
                    )
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
                description = flow.description,
                source = flow.sourceRef?.toArchitectureSource(context),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, flowId, ArchitectureProvenanceKind.ARTIFACT)

            val operationIds = flow.commands.associate { command ->
                command.name to semanticId(artifactId, ArchitectureNodeKind.ZFL_OPERATION, "$flowPath.operations.${command.name}")
            }
            val eventIds = flow.events.associate { event ->
                event.name to semanticId(artifactId, ArchitectureNodeKind.ZFL_EVENT, "$flowPath.events.${event.name}")
            }.toMutableMap()

            flow.events.forEach { event ->
                val eventId = eventIds.getValue(event.name)
                nodes[eventId] = ArchitectureNode(
                    id = eventId,
                    kind = ArchitectureNodeKind.ZFL_EVENT,
                    label = event.name,
                    ownerId = flowId,
                    description = event.description,
                    attributes = attributes(
                        "system" to event.system,
                        "service" to event.service,
                        "isStart" to event.isStart.toString(),
                        "isError" to event.isError.toString(),
                    ),
                    source = event.sourceRef.toArchitectureSource(context),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, eventId, ArchitectureProvenanceKind.ARTIFACT)
                event.system?.let(systemArtifacts::get)?.let { zdlArtifact ->
                    val targetId = semanticId(
                        artifactNodeId(zdlArtifact), ArchitectureNodeKind.ZDL_EVENT, "events.${event.name}",
                    )
                    addEdge(edges, ArchitectureEdgeKind.RESOLVES_TO, eventId, targetId,
                        ArchitectureProvenanceKind.INFERRED, source = event.sourceRef.toArchitectureSource(context))
                }
            }

            flow.starts.forEach { start ->
                val eventId = eventIds.getOrPut(start.name) {
                    semanticId(artifactId, ArchitectureNodeKind.ZFL_EVENT, "$flowPath.events.${start.name}")
                }
                if (eventId !in nodes) {
                    nodes[eventId] = ArchitectureNode(
                        id = eventId,
                        kind = ArchitectureNodeKind.ZFL_EVENT,
                        label = start.name,
                        ownerId = flowId,
                        description = start.description,
                        attributes = attributes("system" to start.system, "isStart" to "true", "isError" to "false"),
                        source = start.sourceRef.toArchitectureSource(context),
                    )
                    addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, eventId, ArchitectureProvenanceKind.ARTIFACT)
                }
                val startId = semanticId(artifactId, ArchitectureNodeKind.ZFL_STEP, "$flowPath.starts.${start.name}")
                nodes[startId] = ArchitectureNode(
                    id = startId,
                    kind = ArchitectureNodeKind.ZFL_STEP,
                    label = start.name,
                    ownerId = flowId,
                    description = start.description,
                    attributes = attributes(
                        "role" to "start", "actor" to start.actor, "timer" to start.timer, "system" to start.system,
                    ),
                    source = start.sourceRef.toArchitectureSource(context),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, startId, ArchitectureProvenanceKind.ARTIFACT)
                addEdge(edges, ArchitectureEdgeKind.TRIGGERS, startId, eventId, ArchitectureProvenanceKind.ARTIFACT,
                    discriminator = "start:${start.name}")
            }

            flow.commands.forEach { command ->
                val operationId = operationIds.getValue(command.name)
                nodes[operationId] = ArchitectureNode(
                    id = operationId,
                    kind = ArchitectureNodeKind.ZFL_OPERATION,
                    label = command.name,
                    ownerId = flowId,
                    description = command.description,
                    attributes = attributes(
                        "system" to command.system, "service" to command.service, "servicePath" to command.servicePath,
                    ),
                    source = command.sourceRef.toArchitectureSource(context),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, operationId, ArchitectureProvenanceKind.ARTIFACT)

                val occurrences = command.occurrences.ifEmpty {
                    listOf(ZflCommandOccurrence(
                        key = "${command.name}@definition", index = 0, description = command.description,
                        system = command.system, service = command.service, servicePath = command.servicePath,
                        steps = command.steps, emissions = command.emits,
                        responses = command.responses.map { io.zenwave360.language.zfl.semantic.ZflResponse(it) },
                        sourceRef = command.sourceRef,
                    ))
                }

                val declarations = occurrences.mapNotNull { occurrence ->
                    occurrence.system?.let { system -> system to occurrence.service }
                }.distinct()
                if (declarations.size > 1) {
                    diagnostics += ArchitectureDiagnostic(
                        message = "ZFL operation '${command.name}' has conflicting service declarations: $declarations",
                        severity = ArchitectureDiagnosticSeverity.WARNING,
                        code = "conflicting-zfl-service-declaration",
                        source = command.sourceRef.toArchitectureSource(context),
                    )
                }

                occurrences.forEach { occurrence ->
                    val occurrencePath = "$flowPath.occurrences.${occurrence.key}"
                    val occurrenceId = semanticId(artifactId, ArchitectureNodeKind.ZFL_STEP, occurrencePath)
                    nodes[occurrenceId] = ArchitectureNode(
                        id = occurrenceId,
                        kind = ArchitectureNodeKind.ZFL_STEP,
                        label = command.name,
                        ownerId = operationId,
                        description = occurrence.description,
                        attributes = attributes(
                            "role" to "operation-occurrence",
                            "operation" to command.name,
                            "occurrenceKey" to occurrence.key,
                            "occurrenceIndex" to occurrence.index.toString(),
                            "system" to occurrence.system,
                            "service" to occurrence.service,
                            "servicePath" to occurrence.servicePath,
                            "actor" to occurrence.actor,
                            "timer" to occurrence.timer,
                        ),
                        source = occurrence.sourceRef.toArchitectureSource(context),
                    )
                    addEdge(edges, ArchitectureEdgeKind.CONTAINS, operationId, occurrenceId, ArchitectureProvenanceKind.ARTIFACT)
                    addEdge(edges, ArchitectureEdgeKind.OCCURRENCE_OF, occurrenceId, operationId,
                        ArchitectureProvenanceKind.ARTIFACT, discriminator = occurrence.key)

                    occurrence.triggers.forEach { trigger ->
                        eventIds[trigger]?.let { triggerId ->
                            addEdge(edges, ArchitectureEdgeKind.TRIGGERS, triggerId, occurrenceId,
                                ArchitectureProvenanceKind.ARTIFACT, discriminator = occurrence.key,
                                attributes = attributes("condition" to occurrence.options["if"]))
                        }
                    }

                    occurrence.emissions.forEachIndexed { index, emission ->
                        eventIds[emission.eventName]?.let { eventId ->
                            addEdge(edges, ArchitectureEdgeKind.EMITS, occurrenceId, eventId,
                                ArchitectureProvenanceKind.ARTIFACT,
                                discriminator = "signal:$index:${emission.eventName}",
                                attributes = attributes(
                                    "outcome" to emission.outcome,
                                    "failure" to emission.failure.toString(),
                                ))
                        }
                    }
                    occurrence.responses.forEachIndexed { index, response ->
                        eventIds[response.name]?.let { eventId ->
                            addEdge(edges, ArchitectureEdgeKind.RESPONDS, occurrenceId, eventId,
                                ArchitectureProvenanceKind.ARTIFACT,
                                discriminator = "response:$index:${response.name}",
                                attributes = attributes("outcome" to response.outcome) + response.options.filterValues { it != null }.mapValues { it.value!! })
                        }
                    }

                    var activeSystem = occurrence.system
                    var activeService = occurrence.service
                    occurrence.steps.forEachIndexed { stepIndex, step ->
                        when (step) {
                            is ZflServiceStep -> {
                                activeSystem = step.system
                                activeService = step.service
                                if (step.system in systemArtifacts && step.service != null &&
                                    systemServices[step.system]?.let { it.isNotEmpty() && step.service !in it } == true) {
                                    diagnostics += ArchitectureDiagnostic(
                                        message = "ZFL operation '${command.name}' references unknown service '${step.service}' in system '${step.system}'",
                                        severity = ArchitectureDiagnosticSeverity.WARNING,
                                        code = "unresolved-zfl-service",
                                        source = occurrence.sourceRef.toArchitectureSource(context),
                                    )
                                }
                            }
                            is ZflCallStep -> {
                                val targetId = operationIds[step.action]
                                if (targetId == null) {
                                    diagnostics += ArchitectureDiagnostic(
                                        message = "ZFL operation '${command.name}' calls unknown operation '${step.action}'",
                                        severity = ArchitectureDiagnosticSeverity.WARNING,
                                        code = "unresolved-zfl-operation",
                                        source = occurrence.sourceRef.toArchitectureSource(context),
                                    )
                                } else {
                                    addEdge(edges, ArchitectureEdgeKind.INVOKES, occurrenceId, targetId,
                                        ArchitectureProvenanceKind.ARTIFACT,
                                        discriminator = "${occurrence.key}:call:$stepIndex:${step.action}",
                                        attributes = attributes(
                                            "async" to step.async.toString(), "system" to activeSystem, "service" to activeService,
                                        ))
                                }
                                step.handlers.forEachIndexed { handlerIndex, handler ->
                                    handler.action?.let { action -> operationIds[action]?.let { target ->
                                        addEdge(edges, ArchitectureEdgeKind.INVOKES, occurrenceId, target,
                                            ArchitectureProvenanceKind.ARTIFACT,
                                            discriminator = "${occurrence.key}:handler:$handlerIndex:${handler.endOutcome}:$action",
                                            attributes = attributes(
                                                "outcome" to handler.endOutcome, "role" to "handler",
                                                "system" to activeSystem, "service" to activeService,
                                            ))
                                    } }
                                    handler.signal?.events.orEmpty().forEach { eventName ->
                                        eventIds[eventName]?.let { target ->
                                            val kind = if (handler.signal?.response == true) ArchitectureEdgeKind.RESPONDS else ArchitectureEdgeKind.EMITS
                                            addEdge(edges, kind, occurrenceId, target, ArchitectureProvenanceKind.ARTIFACT,
                                                discriminator = "${occurrence.key}:handler:$handlerIndex:${handler.endOutcome}:$eventName",
                                                attributes = attributes(
                                                    "outcome" to (handler.signal?.outcome ?: handler.endOutcome),
                                                    "role" to "handler",
                                                    "failure" to handler.signal?.options?.containsKey("failure")?.toString(),
                                                ))
                                        }
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }

                    occurrence.compensates?.let { targetName ->
                        operationIds[targetName]?.let { target ->
                            addEdge(edges, ArchitectureEdgeKind.COMPENSATES, occurrenceId, target,
                                ArchitectureProvenanceKind.ARTIFACT, discriminator = occurrence.key)
                        } ?: run {
                            diagnostics += ArchitectureDiagnostic(
                                message = "ZFL operation '${command.name}' compensates unknown operation '$targetName'",
                                severity = ArchitectureDiagnosticSeverity.WARNING,
                                code = "unknown-compensation-target",
                                source = occurrence.sourceRef.toArchitectureSource(context),
                            )
                        }
                    }
                }

                val systemName = command.system ?: occurrences.firstNotNullOfOrNull { it.system }
                val serviceName = command.service ?: occurrences.firstNotNullOfOrNull { it.service }
                val zdlArtifact = systemName?.let(systemArtifacts::get)
                when {
                    zdlArtifact == null && systemName != null && systemName in systemServices -> diagnostics += ArchitectureDiagnostic(
                        message = "ZFL system '$systemName' has no ZDL declaration",
                        severity = ArchitectureDiagnosticSeverity.WARNING,
                        code = "zfl-service-missing-zdl",
                        source = command.sourceRef.toArchitectureSource(context),
                    )
                    serviceName == null -> diagnostics += ArchitectureDiagnostic(
                        message = "ZFL operation '${command.name}' has no service declaration",
                        severity = ArchitectureDiagnosticSeverity.WARNING,
                        code = "zfl-operation-missing-service",
                        source = command.sourceRef.toArchitectureSource(context),
                    )
                    zdlArtifact != null -> {
                        val targetId = ArchitectureGraphIds.zdlMethod(
                            zdlArtifact.ownerRef, zdlArtifact.artifactId, serviceName, command.name,
                        )
                        addEdge(edges, ArchitectureEdgeKind.RESOLVES_TO, operationId, targetId,
                            ArchitectureProvenanceKind.INFERRED, source = command.sourceRef.toArchitectureSource(context))
                    }
                }
            }

            flow.end.endOutcomes.forEach { (outcome, eventNames) ->
                val outcomeId = semanticId(artifactId, ArchitectureNodeKind.ZFL_OUTCOME, "$flowPath.outcomes.$outcome")
                val failure = outcome.lowercase() in setOf("failed", "failure", "error", "rejected", "cancelled")
                nodes[outcomeId] = ArchitectureNode(
                    id = outcomeId,
                    kind = ArchitectureNodeKind.ZFL_OUTCOME,
                    label = outcome,
                    ownerId = flowId,
                    description = flow.end.description,
                    attributes = mapOf("outcome" to outcome, "failure" to failure.toString()),
                    source = flow.end.sourceRef.toArchitectureSource(context),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, flowId, outcomeId, ArchitectureProvenanceKind.ARTIFACT)
                eventNames.forEach { eventName -> eventIds[eventName]?.let { eventId ->
                    addEdge(edges, ArchitectureEdgeKind.RESULTS_IN, eventId, outcomeId,
                        ArchitectureProvenanceKind.ARTIFACT, discriminator = "$eventName:$outcome")
                } }
            }
        }

        return ManifestGraphContribution(nodes.values.toList(), edges.values.toList(), diagnostics)
    }
}

private fun SourceRef.toArchitectureSource(context: ManifestGraphArtifactContext): ArchitectureSource = ArchitectureSource(
    uri = context.sourceUri,
    artifactOwnerRef = context.artifact.ownerRef,
    artifactId = context.artifact.artifactId,
    semanticPath = file,
    line = line,
    column = column,
)
