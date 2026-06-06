package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.io.defaultLoaders

class ZenWaveManifestLoader(
    private val documentLoaders: List<DocumentLoader> = defaultLoaders(),
) {
    suspend fun load(uri: String): ZenWaveManifest {
        val normalizedUri = ManifestReferenceResolver.normalizeUri(uri)
        val text = loadText(normalizedUri)
        return parse(normalizedUri, text)
    }

    suspend fun parse(uri: String, text: String): ZenWaveManifest {
        val normalizedUri = ManifestReferenceResolver.normalizeUri(uri)
        val normalizedText = shieldConsumerRefs(text)
        val parsed = RefParser.fromText(normalizedText, baseUri = normalizedUri, loaders = documentLoaders)
            .parse()
            .getParsedDocument()
        val root = parsed.schema
        val configNode = root.mapAt("config")
        val config = ManifestConfig(
            title = configNode["title"]?.toString(),
            version = configNode["version"]?.toString(),
            properties = configNode.mapAt("properties").stringMap(),
            sourcePriority = configNode.listAt("sourcePriority").mapNotNull { it?.toString() }.ifEmpty {
                listOf("file", "http", "apicurio")
            },
            naming = parseNaming(configNode.mapAt("naming")),
            sources = parseSources(configNode.mapAt("sources"))
        )
        val diagnostics = mutableListOf<ManifestDiagnostic>()
        val allServices = mutableListOf<ManifestService>()
        val domains = root.mapAt("domains").map { (domainKey, domainValue) ->
            parseDomain(
                manifestUri = normalizedUri,
                properties = config.properties,
                diagnostics = diagnostics,
                allServices = allServices,
                domainKey = domainKey,
                value = domainValue.asMap()
            )
        }
        return ZenWaveManifest(
            uri = normalizedUri,
            config = config,
            domains = domains,
            services = allServices,
            diagnostics = diagnostics
        )
    }

    suspend fun loadResourceText(uri: String): String {
        val normalizedUri = ManifestReferenceResolver.normalizeUri(uri)
        return loadText(normalizedUri)
    }

    suspend fun loadServiceDocs(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<String, String> =
        service.docs.mapValues { (name, path) ->
            loadOwnedResourceText(
                manifest = manifest,
                service = service,
                pathExpression = path,
                artifact = null,
                location = "${service.serviceRef}.docs.$name",
                options = options
            ).second
        }

    suspend fun loadServiceArtifacts(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<ManifestArtifact, String> =
        service.artifacts.associateWith { artifact ->
            loadArtifactText(manifest, service, artifact, options)
        }

    suspend fun loadArtifactText(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): String =
        loadOwnedResourceText(
            manifest = manifest,
            service = service,
            pathExpression = artifact.pathExpression,
            artifact = artifact,
            location = "${service.serviceRef}.artifacts.${artifact.name}",
            options = options
        ).second

    suspend fun resolveArtifact(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): ManifestResolvedResource =
        loadOwnedResourceText(
            manifest = manifest,
            service = service,
            pathExpression = artifact.pathExpression,
            artifact = artifact,
            location = "${service.serviceRef}.artifacts.${artifact.name}",
            options = options
        ).first

    private suspend fun loadOwnedResourceText(
        manifest: ZenWaveManifest,
        service: ManifestService,
        pathExpression: String,
        artifact: ManifestArtifact?,
        location: String,
        options: ManifestLoadOptions,
    ): Pair<ManifestResolvedResource, String> {
        val directPath = expand(pathExpression, location, manifest.config.properties, manifest.diagnostics.toMutableList())
        if (ManifestReferenceResolver.hasScheme(directPath)) {
            val normalizedUri = ManifestReferenceResolver.normalizeUri(directPath)
            return ManifestResolvedResource(source = normalizedUri.substringBefore(':'), uri = normalizedUri) to loadText(normalizedUri)
        }

        val candidates = buildResourceCandidates(manifest, service, artifact, directPath, options)
        val attempted = mutableListOf<String>()
        var lastError: Throwable? = null
        candidates.forEach { candidate ->
            attempted += "${candidate.source}:${candidate.uri}"
            try {
                return candidate to loadText(candidate.uri)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        error(
            buildString {
                append("Unable to load resource for ")
                append(location)
                if (attempted.isNotEmpty()) {
                    append(". Tried: ")
                    append(attempted.joinToString(", "))
                }
                lastError?.message?.let {
                    append(". Last error: ")
                    append(it)
                }
            }
        )
    }

    private fun buildResourceCandidates(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact?,
        resourcePath: String,
        options: ManifestLoadOptions,
    ): List<ManifestResolvedResource> {
        val sourceOrder = resolveSourceOrder(manifest.config.sourcePriority, options)
        val serviceResourcePath = resolveServiceResourcePath(service.path, resourcePath)
        return sourceOrder.flatMap { source ->
            when (source) {
                "file" -> fileCandidates(manifest.uri, serviceResourcePath, options)
                "http" -> httpCandidates(manifest.config.sources.http, serviceResourcePath)
                "apicurio" -> apicurioCandidates(manifest, service, artifact, resourcePath)
                else -> emptyList()
            }
        }
    }

    private fun resolveSourceOrder(priority: List<String>, options: ManifestLoadOptions): List<String> {
        val preferred = options.preferredSource?.trim()?.takeIf { it.isNotEmpty() } ?: return priority
        return if (options.allowFallback) {
            listOf(preferred) + priority.filterNot { it == preferred }
        } else {
            listOf(preferred)
        }
    }

    private fun fileCandidates(
        manifestUri: String,
        serviceResourcePath: String,
        options: ManifestLoadOptions,
    ): List<ManifestResolvedResource> {
        val roots = options.localRoots.ifEmpty { defaultLocalRoots(manifestUri) }
        return roots.map { root ->
            ManifestResolvedResource(
                source = "file",
                uri = joinRootAndPath(root, serviceResourcePath)
            )
        }
    }

    private fun httpCandidates(
        source: ManifestHttpSource?,
        serviceResourcePath: String,
    ): List<ManifestResolvedResource> {
        if (source == null || !source.enabled) return emptyList()
        return source.roots.map { root ->
            ManifestResolvedResource(
                source = "http",
                uri = joinRootAndPath(root, serviceResourcePath)
            )
        }
    }

    private fun apicurioCandidates(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact?,
        resourcePath: String,
    ): List<ManifestResolvedResource> {
        val source = manifest.config.sources.apicurio ?: return emptyList()
        if (!source.enabled) return emptyList()
        val registryUrl = source.registryUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val variables = resolutionVariables(manifest, service, artifact, resourcePath)
        val groupIdExpression = manifest.config.naming.groupIdExpression ?: "\${service.id}"
        val artifactIdExpression = manifest.config.naming.artifactIdExpression ?: "\${artifactName}"
        val groupId = interpolateRequired(groupIdExpression, variables)
        val artifactId = interpolateRequired(artifactIdExpression, variables)
        val contentPath = interpolateRequired(
            source.contentUrlExpression,
            variables + mapOf(
                "groupId" to groupId,
                "artifactId" to artifactId,
                "branch" to source.branch
            )
        )
        return listOf(
            ManifestResolvedResource(
                source = "apicurio",
                uri = ManifestReferenceResolver.appendPath(registryUrl, contentPath)
            )
        )
    }

    private fun resolutionVariables(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact?,
        resourcePath: String,
    ): Map<String, String> {
        val fileName = resourcePath.substringAfterLast('/').substringAfterLast('\\')
        val artifactBaseName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        val domainPath = listOfNotNull(service.domainKey, service.subdomainKey, service.serviceKey).joinToString(".")
        return manifest.config.properties +
            mapOf(
                "domain" to service.domainKey,
                "subdomain" to (service.subdomainKey ?: ""),
                "service" to service.serviceKey,
                "domainPath" to domainPath,
                "servicePath" to service.path,
                "artifactPath" to resourcePath,
                "artifactFileName" to fileName,
                "artifactBaseName" to artifactBaseName,
                "artifactName" to (artifact?.name ?: artifactBaseName),
                "service.id" to (service.id ?: domainPath),
                "service.version" to (service.version ?: ""),
                "service.name" to (service.name ?: ""),
                "service.path" to service.path,
                "artifact.name" to (artifact?.name ?: artifactBaseName),
                "artifact.type" to (artifact?.type ?: ""),
                "artifact.path" to resourcePath,
                "artifact.version" to (artifact?.version ?: ""),
                "groupId" to "",
                "artifactId" to "",
                "branch" to ""
            ) +
            listOfNotNull(artifact?.version?.let { "artifactVersion" to it }, service.version?.let { "serviceVersion" to it }).toMap()
    }

    private fun interpolateRequired(expression: String, variables: Map<String, String>): String {
        val interpolation = ManifestVariableInterpolator.interpolate(expression, variables)
        if (interpolation.unresolvedVariables.isNotEmpty()) {
            error("Unresolved variables in expression '$expression': ${interpolation.unresolvedVariables.joinToString(", ")}")
        }
        return interpolation.value
    }

    private fun resolveServiceResourcePath(servicePath: String, resourcePath: String): String =
        resourcePath.takeIf { it.startsWith("/") }
            ?: "${servicePath.trimEnd('/')}/${resourcePath.trimStart('/')}"

    private fun defaultLocalRoots(manifestUri: String): List<String> =
        listOf(ManifestReferenceResolver.resolveReference(manifestUri, "../.."))

    private fun joinRootAndPath(root: String, childPath: String): String =
        "${root.trimEnd('/')}/${childPath.trimStart('/')}"

    private suspend fun loadText(uri: String): String {
        val loader = documentLoaders.firstOrNull { it.canLoad(uri) }
            ?: error("No document loader available for URI: $uri")
        return loader.load(uri)
    }

    private fun parseDomain(
        manifestUri: String,
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
        allServices: MutableList<ManifestService>,
        domainKey: String,
        value: Map<String, Any?>,
    ): ManifestDomain {
        val directServices = value.mapAt("services").map { (serviceKey, serviceValue) ->
            parseService(
                manifestUri = manifestUri,
                properties = properties,
                diagnostics = diagnostics,
                domainKey = domainKey,
                subdomainKey = null,
                serviceKey = serviceKey,
                value = serviceValue.asMap()
            ).also(allServices::add)
        }
        val subdomains = value.mapAt("subdomains").map { (subdomainKey, subdomainValue) ->
            parseSubdomain(
                manifestUri = manifestUri,
                properties = properties,
                diagnostics = diagnostics,
                allServices = allServices,
                domainKey = domainKey,
                subdomainKey = subdomainKey,
                value = subdomainValue.asMap()
            )
        }
        return ManifestDomain(
            key = domainKey,
            id = value["id"]?.toString(),
            name = value["name"]?.toString(),
            description = value["description"]?.toString(),
            services = directServices,
            subdomains = subdomains
        )
    }

    private fun parseSubdomain(
        manifestUri: String,
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
        allServices: MutableList<ManifestService>,
        domainKey: String,
        subdomainKey: String,
        value: Map<String, Any?>,
    ): ManifestSubdomain {
        val services = value.mapAt("services").map { (serviceKey, serviceValue) ->
            parseService(
                manifestUri = manifestUri,
                properties = properties,
                diagnostics = diagnostics,
                domainKey = domainKey,
                subdomainKey = subdomainKey,
                serviceKey = serviceKey,
                value = serviceValue.asMap()
            ).also(allServices::add)
        }
        return ManifestSubdomain(
            key = subdomainKey,
            id = value["id"]?.toString(),
            name = value["name"]?.toString(),
            description = value["description"]?.toString(),
            services = services
        )
    }

    private fun parseService(
        manifestUri: String,
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
        domainKey: String,
        subdomainKey: String?,
        serviceKey: String,
        value: Map<String, Any?>,
    ): ManifestService {
        val serviceRef = listOfNotNull(domainKey, subdomainKey, serviceKey).joinToString("/")
        val servicePath = expand(
            rawValue = value["path"]?.toString() ?: "/$serviceKey",
            location = "$serviceRef.path",
            properties = properties,
            diagnostics = diagnostics
        )
        val docs = value.mapAt("docs").mapValuesNotNull { rawDocValue ->
            (rawDocValue as? String)?.let {
                expand(
                    rawValue = it,
                    location = "$serviceRef.docs",
                    properties = properties,
                    diagnostics = diagnostics
                )
            }
        }
        val artifacts = value.listAt("artifacts").mapNotNull { rawArtifact ->
            val artifact = rawArtifact.asMap()
            val type = artifact["type"] as? String ?: return@mapNotNull null
            val pathExpression = artifact["path"] as? String ?: return@mapNotNull null
            val expandedPath = expand(
                rawValue = pathExpression,
                location = "$serviceRef.artifacts",
                properties = properties,
                diagnostics = diagnostics
            )
            ManifestArtifact(
                name = artifactNameOrDefault(artifact["name"] as? String, expandedPath),
                type = type,
                pathExpression = expandedPath,
                version = artifact["version"]?.toString()
            )
        }
        detectArtifactNameCollisions(serviceRef, artifacts, diagnostics)
        val consumers = value.listAt("consumers").mapNotNull { rawConsumer ->
            normalizeConsumer(domainKey, rawConsumer)
        }
        return ManifestService(
            domainKey = domainKey,
            subdomainKey = subdomainKey,
            serviceKey = serviceKey,
            id = value["id"]?.toString(),
            version = value["version"]?.toString(),
            name = value["name"]?.toString(),
            description = value["description"]?.toString(),
            serviceRef = serviceRef,
            path = servicePath,
            docs = docs,
            artifacts = artifacts,
            consumers = consumers
        )
    }

    private fun artifactNameOrDefault(explicitName: String?, path: String): String {
        val trimmed = explicitName?.trim()
        if (!trimmed.isNullOrEmpty()) return trimmed
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    }

    private fun detectArtifactNameCollisions(
        serviceRef: String,
        artifacts: List<ManifestArtifact>,
        diagnostics: MutableList<ManifestDiagnostic>,
    ) {
        artifacts.groupBy { it.name }
            .filterValues { it.size > 1 }
            .forEach { (name, duplicated) ->
                diagnostics += ManifestDiagnostic(
                    message = "Duplicate artifact name '$name' resolved from paths: ${duplicated.joinToString(", ") { it.pathExpression }}",
                    code = "duplicate-artifact-name",
                    location = "$serviceRef.artifacts"
                )
            }
    }

    private fun expand(
        rawValue: String,
        location: String,
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
    ): String {
        val interpolation = ManifestVariableInterpolator.interpolate(rawValue, properties)
        interpolation.unresolvedVariables.forEach { variable ->
            diagnostics += ManifestDiagnostic(
                message = "Unresolved variable: $variable",
                code = "unresolved-variable",
                location = location
            )
        }
        return interpolation.value
    }

    private fun parseNaming(value: Map<String, Any?>): ManifestNaming =
        ManifestNaming(
            groupIdExpression = value["groupIdExpression"]?.toString(),
            artifactIdExpression = value["artifactIdExpression"]?.toString()
        )

    private fun parseSources(value: Map<String, Any?>): ManifestSources =
        ManifestSources(
            http = value["http"]?.asMap()?.let { http ->
                ManifestHttpSource(
                    enabled = http["enabled"] as? Boolean ?: true,
                    roots = http.listAt("roots").mapNotNull { it?.toString() }
                )
            },
            apicurio = value["apicurio"]?.asMap()?.let { apicurio ->
                ManifestApicurioSource(
                    enabled = apicurio["enabled"] as? Boolean ?: true,
                    registryUrl = apicurio["registryUrl"]?.toString(),
                    branch = apicurio["branch"]?.toString() ?: "latest",
                    contentUrlExpression = apicurio["contentUrlExpression"]?.toString()
                        ?: "/groups/\${groupId}/artifacts/\${artifactId}/branches/\${branch}"
                )
            }
        )

    private fun normalizeConsumer(currentDomain: String, value: Any?): String? =
        when (value) {
            is String -> {
                if (value.trim().startsWith("#/")) normalizeJsonPointerReference(value)
                else normalizeServiceRef(currentDomain, value)
            }
            is Map<*, *> -> normalizeConsumerMap(currentDomain, value)
            else -> null
        }

    private fun normalizeConsumerMap(currentDomain: String, value: Map<*, *>): String? {
        val serviceRef = value["service"] as? String
        if (serviceRef != null) {
            return if (serviceRef.trim().startsWith("#/")) {
                normalizeJsonPointerReference(serviceRef)
            } else {
                normalizeServiceRef(currentDomain, serviceRef)
            }
        }
        val pointerRef = value["\$ref"] as? String
        return normalizeJsonPointerReference(pointerRef)
    }

    private fun normalizeJsonPointerReference(pointer: String?): String? {
        val raw = pointer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!raw.startsWith("#/")) return raw
        val tokens = raw.removePrefix("#/")
            .split('/')
            .map(::decodeJsonPointerToken)
        return when {
            tokens.size >= 4 &&
                tokens[0] == "domains" &&
                tokens[2] == "services" -> "${tokens[1]}/${tokens[3]}"
            tokens.size >= 6 &&
                tokens[0] == "domains" &&
                tokens[2] == "subdomains" &&
                tokens[4] == "services" -> "${tokens[1]}/${tokens[3]}/${tokens[5]}"
            else -> raw
        }
    }

    private fun decodeJsonPointerToken(token: String): String =
        token.replace("~1", "/").replace("~0", "~")

    private fun normalizeServiceRef(currentDomain: String, value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (raw.count { it == '/' }) {
            0 -> "$currentDomain/$raw"
            else -> raw
        }
    }

    private fun shieldConsumerRefs(text: String): String =
        text.replace(Regex("""(^[ \t]*-[ \t]*)\${'$'}ref:""", RegexOption.MULTILINE), "$1service:")
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> =
    this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
private fun Map<*, *>.mapAt(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: emptyMap()

private fun Map<String, Any?>.listAt(key: String): List<Any?> =
    this[key] as? List<Any?> ?: emptyList()

private fun Map<String, Any?>.stringMap(): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        val stringValue = value as? String ?: return@mapNotNull null
        key to stringValue
    }.toMap()

private fun Map<String, Any?>.mapValuesNotNull(transform: (Any?) -> String?): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        transform(value)?.let { key to it }
    }.toMap()
