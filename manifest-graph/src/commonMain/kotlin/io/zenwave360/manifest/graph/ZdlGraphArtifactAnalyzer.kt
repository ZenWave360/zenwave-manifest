package io.zenwave360.manifest.graph

import io.zenwave360.language.zdl.ZdlParser
import io.zenwave360.manifest.ResolvedManifestArtifact

internal class ZdlGraphArtifactAnalyzer : ManifestGraphArtifactAnalyzer {
    override fun supports(artifact: ResolvedManifestArtifact): Boolean = artifact.artifact.type == "zdl"

    override suspend fun analyze(context: ManifestGraphArtifactContext): ManifestGraphContribution {
        val model = ZdlParser().parseModel(context.content)
        val artifactId = artifactNodeId(context.artifact)
        val nodes = linkedMapOf<String, ArchitectureNode>()
        val edges = linkedMapOf<String, ArchitectureEdge>()
        val diagnostics = model.getProblems().map { problem ->
            ArchitectureDiagnostic(
                message = problem["message"]?.toString() ?: "Invalid ZDL model",
                severity = ArchitectureDiagnosticSeverity.WARNING,
                code = "zdl-problem",
                source = source(context, problem["path"]?.toString(), problem["location"]),
            )
        }.toMutableList()

        val events = model.getEvents()
        events.forEach { (eventKey, value) ->
            val event = value.asStringMap()
            val asyncApi = event["options"].asStringMap()["asyncapi"].asStringMap()
            val name = event["name"]?.toString() ?: eventKey
            val path = "events.$eventKey"
            val nodeId = semanticId(artifactId, ArchitectureNodeKind.ZDL_EVENT, path)
            nodes[nodeId] = semanticNode(
                context, nodeId, ArchitectureNodeKind.ZDL_EVENT, name, artifactId, path, model.getLocations(),
                attributes(
                    "asyncapiChannel" to asyncApi["channel"]?.toString(),
                    "asyncapiTopic" to asyncApi["topic"]?.toString(),
                ),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, nodeId, ArchitectureProvenanceKind.ARTIFACT)
        }

        model.getEntities().forEach { (entityKey, value) ->
            val entity = value.asStringMap()
            val name = entity["name"]?.toString() ?: entityKey
            val path = "entities.$entityKey"
            val nodeId = semanticId(artifactId, ArchitectureNodeKind.ZDL_ENTITY, path)
            nodes[nodeId] = semanticNode(
                context, nodeId, ArchitectureNodeKind.ZDL_ENTITY, name, artifactId, path, model.getLocations(),
                attributes("aggregate" to entity["options"].asStringMap()["aggregate"]?.toString()),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, nodeId, ArchitectureProvenanceKind.ARTIFACT)
        }

        val apis = model["apis"].asStringMap()
        apis.forEach { (apiKey, value) ->
            val api = value.asStringMap()
            val name = api["name"]?.toString() ?: apiKey
            val path = "apis.$apiKey"
            val nodeId = semanticId(artifactId, ArchitectureNodeKind.ZDL_API, path)
            nodes[nodeId] = semanticNode(
                context, nodeId, ArchitectureNodeKind.ZDL_API, name, artifactId, path, model.getLocations(),
                attributes(
                    "type" to api["type"]?.toString(),
                    "role" to api["role"]?.toString(),
                    "uri" to api["uri"]?.toString(),
                ),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, nodeId, ArchitectureProvenanceKind.ARTIFACT)
            val uri = api["uri"]?.toString()
            if (!uri.isNullOrBlank()) {
                val matches = findReferencedArtifacts(context, uri, api["type"]?.toString())
                if (matches.size == 1) {
                    addEdge(
                        edges, ArchitectureEdgeKind.REFERENCES_API, nodeId, artifactNodeId(matches.single()),
                        ArchitectureProvenanceKind.ARTIFACT, source = source(context, path, model.getLocations()[path]),
                    )
                } else if (matches.size > 1) {
                    diagnostics += ambiguousReference(context, uri, path, matches)
                } else {
                    diagnostics += ArchitectureDiagnostic(
                        message = "ZDL API '$name' references unresolved artifact '$uri'",
                        severity = ArchitectureDiagnosticSeverity.WARNING,
                        code = "unresolved-api-reference",
                        source = source(context, path, model.getLocations()[path]),
                    )
                }
            }
        }

        model["services"].asStringMap().forEach { (serviceKey, value) ->
            val service = value.asStringMap()
            val serviceName = service["name"]?.toString() ?: serviceKey
            val servicePath = "services.$serviceKey"
            val serviceId = semanticId(artifactId, ArchitectureNodeKind.ZDL_SERVICE, servicePath)
            nodes[serviceId] = semanticNode(
                context, serviceId, ArchitectureNodeKind.ZDL_SERVICE, serviceName, artifactId, servicePath, model.getLocations(),
            )
            addEdge(edges, ArchitectureEdgeKind.DEFINES, artifactId, serviceId, ArchitectureProvenanceKind.ARTIFACT)

            val serviceRestPath = restPath(service["options"].asStringMap()["rest"])

            service["methods"].asStringMap().forEach { (methodKey, methodValue) ->
                val method = methodValue.asStringMap()
                val methodOptions = method["options"].asStringMap()
                val asyncApi = methodOptions["asyncapi"].asStringMap()
                val rest = methodRestEvidence(serviceRestPath, methodOptions)
                val methodName = method["name"]?.toString() ?: methodKey
                val methodPath = "$servicePath.methods.$methodKey"
                val methodId = ArchitectureGraphIds.zdlMethod(
                    context.artifact.ownerRef, context.artifact.artifactId, serviceName, methodName,
                )
                nodes[methodId] = semanticNode(
                    context, methodId, ArchitectureNodeKind.ZDL_METHOD, methodName, serviceId, methodPath, model.getLocations(),
                    attributes(
                        "parameter" to method["parameter"]?.toString(),
                        "returnType" to method["returnType"]?.toString(),
                        "asyncapiApi" to asyncApi["api"]?.toString(),
                        "asyncapiChannel" to asyncApi["channel"]?.toString(),
                        "asyncapiTopic" to asyncApi["topic"]?.toString(),
                        "httpMethod" to rest?.first,
                        "restPath" to rest?.second,
                        "intent" to if (rest?.first in setOf("GET", "HEAD")) "query" else "command",
                    ),
                )
                addEdge(edges, ArchitectureEdgeKind.CONTAINS, serviceId, methodId, ArchitectureProvenanceKind.ARTIFACT)

                flattenStrings(method["withEvents"]).forEach { eventName ->
                    val eventKey = events.entries.firstOrNull { (key, eventValue) ->
                        eventValue.asStringMap()["name"]?.toString() == eventName || key == eventName
                    }?.key ?: return@forEach
                    val eventId = semanticId(artifactId, ArchitectureNodeKind.ZDL_EVENT, "events.$eventKey")
                    addEdge(edges, ArchitectureEdgeKind.EMITS, methodId, eventId, ArchitectureProvenanceKind.ARTIFACT)
                }

                referencedApiNames(method["options"]).forEach { apiName ->
                    val apiKey = apis.entries.firstOrNull { (key, apiValue) ->
                        apiValue.asStringMap()["name"]?.toString() == apiName || key == apiName
                    }?.key ?: return@forEach
                    val apiId = semanticId(artifactId, ArchitectureNodeKind.ZDL_API, "apis.$apiKey")
                    addEdge(edges, ArchitectureEdgeKind.REFERENCES_API, methodId, apiId, ArchitectureProvenanceKind.ARTIFACT)
                }
            }
        }

        return ManifestGraphContribution(nodes.values.toList(), edges.values.toList(), diagnostics)
    }
}

private val HTTP_METHODS = listOf("get", "post", "put", "patch", "delete", "head", "options")

private fun methodRestEvidence(basePath: String?, options: Map<String, Any?>): Pair<String, String>? {
    val method = HTTP_METHODS.firstOrNull(options::containsKey) ?: return null
    val methodPath = restPath(options[method])
    val path = joinRestPaths(basePath, methodPath)
    return method.uppercase() to path
}

private fun restPath(value: Any?): String? = when (value) {
    is String -> value
    is Map<*, *> -> value["path"]?.toString()
    else -> null
}

private fun joinRestPaths(base: String?, child: String?): String {
    val segments = listOfNotNull(base, child).map { it.trim('/') }.filter(String::isNotBlank)
    return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
}

private fun referencedApiNames(value: Any?): Set<String> {
    val names = mutableSetOf<String>()
    fun visit(current: Any?) {
        when (current) {
            is Map<*, *> -> current.forEach { (key, child) ->
                if (key?.toString() == "api" && child != null) names += child.toString()
                visit(child)
            }
            is Iterable<*> -> current.forEach(::visit)
            is Array<*> -> current.forEach(::visit)
        }
    }
    visit(value)
    return names
}
