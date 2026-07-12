package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.io.defaultLoaders

class ManifestResolutionException(message: String) : IllegalArgumentException(message)

class ManifestResourceLoadException(
    message: String,
    val candidates: List<ManifestResolvedResource>,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Suppress("unused")
class ZenWaveManifestLoader(
    private val documentLoaders: List<DocumentLoader> = defaultLoaders(),
    private val archiveEntryLoader: ManifestArchiveEntryLoader? = defaultManifestArchiveEntryLoader(),
) {
    suspend fun load(uri: String): ZenWaveManifest {
        val normalizedUri = ManifestReferenceResolver.normalizeUri(uri)
        return parse(normalizedUri, loadText(normalizedUri))
    }

    suspend fun parse(uri: String, text: String): ZenWaveManifest {
        val normalizedUri = ManifestReferenceResolver.normalizeUri(uri)
        val root = RefParser.fromText(
            shieldConsumerRefs(text),
            baseUri = normalizedUri,
            loaders = documentLoaders,
        ).parse().getParsedDocument().schema
        val configNode = root.mapAt("config")
        val diagnostics = mutableListOf<ManifestDiagnostic>()
        val properties = parseProperties(configNode.mapAt("properties"), diagnostics)
        val contentResolution = (configNode["contentResolution"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?: listOf(ManifestSourceName.WORKSPACE)
        val groupIdExpression = expandStatic(
            configNode["groupIdExpression"]?.toString() ?: "\${service.id}",
            "config.groupIdExpression",
            properties,
            diagnostics,
            allowRuntime = true,
        )
        val artifactIdExpression = expandStatic(
            configNode["artifactIdExpression"]?.toString() ?: "\${artifact.fileNameWithoutExtension}",
            "config.artifactIdExpression",
            properties,
            diagnostics,
            allowRuntime = true,
        )
        validateCoordinateExpression(groupIdExpression, "config.groupIdExpression", diagnostics)
        validateCoordinateExpression(artifactIdExpression, "config.artifactIdExpression", diagnostics)
        val sources = parseSources(configNode.mapAt("sources"), properties, diagnostics)
        validateContentResolution(contentResolution, sources, diagnostics)
        val config = ManifestConfig(
            title = configNode["title"]?.toString(),
            version = configNode["version"].stringValue(),
            groupIdExpression = groupIdExpression,
            artifactIdExpression = artifactIdExpression,
            properties = properties,
            contentResolution = contentResolution,
            sources = sources,
        )

        val allServices = mutableListOf<ManifestService>()
        val domains = root.mapAt("domains").map { (domainKey, domainValue) ->
            parseDomain(properties, diagnostics, allServices, domainKey, domainValue.asMap())
        }
        return ZenWaveManifest(normalizedUri, config, domains, allServices, diagnostics)
    }

    suspend fun loadResourceText(uri: String): String =
        loadText(ManifestReferenceResolver.normalizeUri(uri))

    suspend fun loadServiceDocs(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<String, String> = service.docs.mapValues { (key, path) ->
        loadOwnedResourceText(
            manifest,
            service,
            path,
            artifact = null,
            location = "${service.serviceRef}.docs.$key",
            options = options,
        ).second
    }

    suspend fun loadServiceArtifacts(
        manifest: ZenWaveManifest,
        service: ManifestService,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): Map<ManifestArtifact, String> = service.artifacts.associateWith {
        loadArtifactText(manifest, service, it, options)
    }

    suspend fun loadArtifactText(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): String = loadOwnedResourceText(
        manifest,
        service,
        artifact.path,
        artifact,
        "${service.serviceRef}.artifacts.${artifact.name ?: artifact.path}",
        options,
    ).second

    suspend fun resolveArtifact(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): ManifestResolvedResource = loadOwnedResourceText(
        manifest,
        service,
        artifact.path,
        artifact,
        "${service.serviceRef}.artifacts.${artifact.name ?: artifact.path}",
        options,
    ).first

    fun artifactResolutionContext(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
    ): ManifestResolutionContext {
        val base = baseContext(service, artifact, artifact.path)
        val coordinates = resolveCoordinates(manifest, base, service, artifact)
        return base.copy(groupId = coordinates.groupId, artifactId = coordinates.artifactId)
    }

    fun documentResolutionContext(
        @Suppress("UNUSED_PARAMETER") manifest: ZenWaveManifest,
        service: ManifestService,
        path: String,
    ): ManifestResolutionContext = baseContext(service, null, path)

    fun buildArtifactCandidates(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): List<ManifestResolvedResource> {
        if (ManifestReferenceResolver.hasScheme(artifact.path)) return listOf(directResource(artifact.path))
        return buildResourceCandidates(
            manifest,
            service,
            artifact,
            artifact.path,
            baseContext(service, artifact, artifact.path),
            options,
        )
    }

    fun buildDocumentCandidates(
        manifest: ZenWaveManifest,
        service: ManifestService,
        documentKey: String,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): List<ManifestResolvedResource> {
        val path = service.docs[documentKey]
            ?: throw ManifestResolutionException("Service document key '$documentKey' is not declared in '${service.serviceRef}'")
        if (ManifestReferenceResolver.hasScheme(path)) return listOf(directResource(path))
        return buildResourceCandidates(
            manifest,
            service,
            artifact = null,
            resourcePath = path,
            context = documentResolutionContext(manifest, service, path),
            options = options,
        )
    }

    private suspend fun loadOwnedResourceText(
        manifest: ZenWaveManifest,
        service: ManifestService,
        resourcePath: String,
        artifact: ManifestArtifact?,
        location: String,
        options: ManifestLoadOptions,
    ): Pair<ManifestResolvedResource, String> {
        if (ManifestReferenceResolver.hasScheme(resourcePath)) {
            val direct = directResource(resourcePath)
            return direct to loadText(direct.uri)
        }
        val context = baseContext(service, artifact, resourcePath)
        val candidates = buildResourceCandidates(manifest, service, artifact, resourcePath, context, options)
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val text = candidate.archiveEntry?.let { entry ->
                    val loader = archiveEntryLoader
                        ?: error("No archive-entry loader is available for ${redactUri(candidate.uri)}")
                    loader.loadEntry(candidate.uri, entry)
                } ?: loadText(candidate.uri)
                return candidate to text
            } catch (error: Throwable) {
                lastError = error
            }
        }
        val safeCandidates = candidates.map { it.copy(uri = redactUri(it.uri)) }
        val finalError = lastError?.message?.let(::redactErrorMessage) ?: "no candidates were produced"
        throw ManifestResourceLoadException(
            buildString {
                append("Unable to load resource for $location. Ordered candidates: ")
                append(safeCandidates.joinToString(", ") { "${it.source}:${it.uri}" }.ifEmpty { "<none>" })
                append(". Final load error: $finalError")
            },
            safeCandidates,
            cause = null,
        )
    }

    private fun buildResourceCandidates(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact?,
        resourcePath: String,
        context: ManifestResolutionContext,
        options: ManifestLoadOptions,
    ): List<ManifestResolvedResource> = resolveSourceOrder(manifest.config.contentResolution, options).flatMap { source ->
        validateSourceReady(source, manifest.config.sources)
        when (source) {
            ManifestSourceName.WORKSPACE -> workspaceCandidates(manifest, resourcePath, context)
            ManifestSourceName.GIT -> manifest.config.sources.git!!.let { configured ->
                val expression = gitExpression(configured)
                gitCandidates(
                    configured,
                    expression,
                    addSelectedCoordinates(manifest, service, artifact, context, expression),
                )
            }
            ManifestSourceName.APICURIO -> if (artifact == null) emptyList() else {
                val configured = manifest.config.sources.apicurio!!
                val expression = configured.contentUrlExpression ?: APICURIO_DEFAULT_EXPRESSION
                apicurioCandidates(
                    configured,
                    expression,
                    addSelectedCoordinates(manifest, service, artifact, context, expression),
                )
            }
            ManifestSourceName.ARTIFACTORY -> manifest.config.sources.artifactory!!.let { configured ->
                artifactoryCandidates(
                    configured,
                    addSelectedCoordinates(manifest, service, artifact, context, configured.contentUrlExpression),
                )
            }
            ManifestSourceName.MAVEN -> if (artifact == null) emptyList() else {
                val coordinates = resolveCoordinates(manifest, context, service, artifact)
                mavenCandidates(
                    manifest.config.sources.maven!!,
                    artifact,
                    context.copy(groupId = coordinates.groupId, artifactId = coordinates.artifactId),
                )
            }
            else -> throw ManifestResolutionException("Invalid content-resolution source '$source'")
        }
    }

    private fun resolveSourceOrder(contentResolution: List<String>, options: ManifestLoadOptions): List<String> {
        val preferred = options.preferredSource.nonBlankOrNull() ?: return contentResolution
        if (preferred !in contentResolution) {
            throw ManifestResolutionException("Preferred source '$preferred' is not active in config.contentResolution")
        }
        return if (options.allowFallback) listOf(preferred) + contentResolution.filterNot { it == preferred } else listOf(preferred)
    }

    private fun workspaceCandidates(
        manifest: ZenWaveManifest,
        resourcePath: String,
        context: ManifestResolutionContext,
    ): List<ManifestResolvedResource> {
        val base = interpolateRuntime(
            ManifestSourceName.WORKSPACE,
            manifest.config.sources.workspace.basePathExpression,
            context,
            encode = false,
        )
        val relative = listOf(base, resourcePath).filter { it.isNotBlank() }.joinToString("/")
        val manifestDirectory = ManifestReferenceResolver.resolveReference(manifest.uri, ".")
        return listOf(
            ManifestResolvedResource(
                ManifestSourceName.WORKSPACE,
                ManifestReferenceResolver.resolveReference("${manifestDirectory.trimEnd('/')}/placeholder", relative),
            ),
        )
    }

    private fun gitExpression(source: ManifestGitSource): String =
        source.contentUrlExpression ?: when (source.provider) {
            GIT_GITHUB -> "\${server}/\${domain.id}/\${service.id}/raw/v\${version}/\${content.path}"
            GIT_GITLAB -> "\${server}/\${domain.id}/\${subdomain.id}/\${service.id}/-/raw/v\${version}/\${content.path}"
            GIT_BITBUCKET -> "\${server}/\${domain.id}/\${service.id}/src/v\${version}/\${content.path}"
            else -> throw ManifestResolutionException("Git provider '${source.provider}' requires contentUrlExpression")
        }

    private fun gitCandidates(
        source: ManifestGitSource,
        expression: String,
        context: ManifestResolutionContext,
    ): List<ManifestResolvedResource> {
        val result = interpolateRuntime(ManifestSourceName.GIT, expression, context, source.server)
        return listOf(ManifestResolvedResource(ManifestSourceName.GIT, ManifestReferenceResolver.normalizeEmptySegments(result)))
    }

    private fun apicurioCandidates(
        source: ManifestApicurioSource,
        expression: String,
        context: ManifestResolutionContext,
    ): List<ManifestResolvedResource> {
        val result = interpolateRuntime(ManifestSourceName.APICURIO, expression, context, source.server)
        return listOf(ManifestResolvedResource(ManifestSourceName.APICURIO, ManifestReferenceResolver.normalizeEmptySegments(result)))
    }

    private fun artifactoryCandidates(
        source: ManifestArtifactorySource,
        context: ManifestResolutionContext,
    ): List<ManifestResolvedResource> {
        val result = interpolateRuntime(
            ManifestSourceName.ARTIFACTORY,
            source.contentUrlExpression,
            context,
            source.server,
        )
        return listOf(ManifestResolvedResource(ManifestSourceName.ARTIFACTORY, ManifestReferenceResolver.normalizeEmptySegments(result)))
    }

    private fun mavenCandidates(
        source: ManifestMavenSource,
        artifact: ManifestArtifact,
        context: ManifestResolutionContext,
    ): List<ManifestResolvedResource> {
        val groupId = context.groupId.requireCoordinate("groupId")
        val artifactId = context.artifactId.requireCoordinate("artifactId")
        val version = context.version.requireCoordinate("version")
        val groupPath = groupId.split('.').joinToString("/") { ManifestReferenceResolver.encodePathSegment(it) }
        val encodedArtifactId = ManifestReferenceResolver.encodePathSegment(artifactId)
        val encodedVersion = ManifestReferenceResolver.encodePathSegment(version)
        val jarPath = "$groupPath/$encodedArtifactId/$encodedVersion/$encodedArtifactId-$encodedVersion.jar"
        val base = "${source.server.trimEnd('/')}/${ManifestReferenceResolver.encodePath(source.repository.trim('/'))}/$jarPath"
        return when (source.provider) {
            MAVEN_ARTIFACTORY -> listOf(
                ManifestResolvedResource(
                    ManifestSourceName.MAVEN,
                    "$base!/${ManifestReferenceResolver.encodePath(artifact.path.trimStart('/'))}",
                ),
            )
            MAVEN_CENTRAL -> listOf(
                ManifestResolvedResource(ManifestSourceName.MAVEN, base, archiveEntry = artifact.path),
            )
            else -> throw ManifestResolutionException("Invalid Maven provider '${source.provider}'")
        }
    }

    internal fun interpolateRuntime(
        source: String,
        expression: String,
        context: ManifestResolutionContext,
        server: String? = null,
        encode: Boolean = true,
    ): String {
        val invalidDocLookup = PLACEHOLDER.findAll(expression).map { it.groupValues[1] }
            .firstOrNull { it.startsWith("service.docs") && !DOC_LOOKUP.matches(it) }
        if (invalidDocLookup != null) {
            throw ManifestResolutionException("Source '$source' expression '$expression' has invalid document lookup: $invalidDocLookup")
        }
        val variables = context.variables().mapValues { (name, value) ->
            if (!encode) value else encodeRuntimeValue(source, name, value)
        }.toMutableMap()
        server.nonBlankOrNull()?.let { variables["server"] = it.trimEnd('/') }
        val interpolation = ManifestVariableInterpolator.interpolate(expression, variables)
        if (interpolation.unresolvedVariables.isNotEmpty()) {
            throw ManifestResolutionException(
                "Source '$source' expression '$expression' has unresolved runtime variables: " +
                    interpolation.unresolvedVariables.joinToString(", "),
            )
        }
        return interpolation.value
    }

    private fun encodeRuntimeValue(source: String, name: String, value: String): String = when {
        name == "server" -> value
        source == ManifestSourceName.APICURIO && (name == "artifact.path" || name == "content.path") ->
            ManifestReferenceResolver.encodePathSegment(value)
        name == "artifact.path" || name == "content.path" || name.startsWith("service.docs[") ->
            ManifestReferenceResolver.encodePath(value)
        else -> ManifestReferenceResolver.encodePathSegment(value)
    }

    private fun resolveCoordinates(
        manifest: ZenWaveManifest,
        context: ManifestResolutionContext,
        service: ManifestService,
        artifact: ManifestArtifact,
    ): ManifestCoordinates {
        return ManifestCoordinates(
            resolveGroupId(manifest, service, context),
            resolveArtifactId(manifest, artifact, context),
        )
    }

    private fun addSelectedCoordinates(
        manifest: ZenWaveManifest,
        service: ManifestService,
        artifact: ManifestArtifact?,
        context: ManifestResolutionContext,
        expression: String,
    ): ManifestResolutionContext {
        val needsGroupId = expressionSelects(expression, "groupId")
        val needsArtifactId = expressionSelects(expression, "artifactId")
        return context.copy(
            groupId = if (needsGroupId) resolveGroupId(manifest, service, context) else context.groupId,
            artifactId = if (needsArtifactId) {
                artifact?.let { resolveArtifactId(manifest, it, context) }
                    ?: throw ManifestResolutionException("Required coordinate 'artifactId' is unresolved")
            } else {
                context.artifactId
            },
        )
    }

    private fun resolveGroupId(
        manifest: ZenWaveManifest,
        service: ManifestService,
        context: ManifestResolutionContext,
    ): String = (
        service.groupId.nonBlankOrNull()
            ?: interpolateRuntime("coordinates", manifest.config.groupIdExpression, context, encode = false)
        ).requireCoordinate("groupId")

    private fun resolveArtifactId(
        manifest: ZenWaveManifest,
        artifact: ManifestArtifact,
        context: ManifestResolutionContext,
    ): String = (
        artifact.artifactId.nonBlankOrNull()
            ?: interpolateRuntime("coordinates", manifest.config.artifactIdExpression, context, encode = false)
        ).requireCoordinate("artifactId")

    private fun expressionSelects(expression: String, name: String): Boolean =
        PLACEHOLDER.findAll(expression).any { it.groupValues[1] == name }

    private fun baseContext(service: ManifestService, artifact: ManifestArtifact?, contentPath: String) =
        ManifestResolutionContext(
            domainId = service.domainId,
            subdomainId = service.subdomainId,
            serviceId = service.id,
            repository = service.repository,
            domainVersion = service.domainVersion,
            subdomainVersion = service.subdomainVersion,
            serviceVersion = service.version,
            artifact = artifact,
            contentPath = contentPath,
            docs = service.docs,
            version = service.resolvedVersion(artifact),
        )

    private fun String?.requireCoordinate(name: String): String =
        nonBlankOrNull() ?: throw ManifestResolutionException("Required coordinate '$name' is unresolved")

    private fun validateSourceReady(source: String, sources: ManifestSources) {
        when {
            source !in ManifestSourceName.all -> throw ManifestResolutionException("Invalid content-resolution source '$source'")
            source == ManifestSourceName.GIT && sources.git == null -> unconfiguredSourceError(source)
            source == ManifestSourceName.APICURIO && sources.apicurio == null -> unconfiguredSourceError(source)
            source == ManifestSourceName.ARTIFACTORY && sources.artifactory == null -> unconfiguredSourceError(source)
            source == ManifestSourceName.MAVEN && sources.maven == null -> unconfiguredSourceError(source)
        }
        when (source) {
            ManifestSourceName.GIT -> sources.git!!.let {
                if (it.provider !in GIT_PROVIDERS) {
                    throw ManifestResolutionException("Invalid Git provider '${it.provider}'")
                }
                if (it.provider == GIT_GENERIC && it.contentUrlExpression.nonBlankOrNull() == null) {
                    throw ManifestResolutionException("Git provider 'generic' requires contentUrlExpression")
                }
            }
            ManifestSourceName.APICURIO -> if (sources.apicurio!!.server.isBlank()) {
                throw ManifestResolutionException("Source 'apicurio' requires server")
            }
            ManifestSourceName.ARTIFACTORY -> sources.artifactory!!.let {
                if (it.server.isBlank()) throw ManifestResolutionException("Source 'artifactory' requires server")
                if (it.contentUrlExpression.isBlank()) {
                    throw ManifestResolutionException("Source 'artifactory' contentUrlExpression must not be blank")
                }
            }
            ManifestSourceName.MAVEN -> sources.maven!!.let {
                if (it.provider !in MAVEN_PROVIDERS) {
                    throw ManifestResolutionException("Invalid Maven provider '${it.provider}'")
                }
                if (it.server.isBlank()) throw ManifestResolutionException("Source 'maven' requires server")
                if (it.repository.isBlank()) throw ManifestResolutionException("Source 'maven' requires repository")
            }
        }
    }

    private fun unconfiguredSourceError(source: String): Nothing =
        throw ManifestResolutionException("Content-resolution source '$source' is not configured in config.sources")

    private fun directResource(path: String): ManifestResolvedResource {
        val uri = ManifestReferenceResolver.normalizeUri(path)
        return ManifestResolvedResource(uri.substringBefore(':'), uri)
    }

    private suspend fun loadText(uri: String): String {
        val loader = documentLoaders.firstOrNull { it.canLoad(uri) }
            ?: error("No document loader available for URI: ${redactUri(uri)}")
        return loader.load(uri)
    }

    private fun parseDomain(
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
        allServices: MutableList<ManifestService>,
        domainKey: String,
        value: Map<String, Any?>,
    ): ManifestDomain {
        val domainId = value["id"]?.toString().nonBlankOrNull() ?: domainKey
        val domainVersion = value["version"].stringValue()
        val directServices = value.mapAt("services").map { (serviceKey, serviceValue) ->
            parseService(
                properties, diagnostics, domainKey, domainId, domainVersion,
                null, "", null, serviceKey, serviceValue.asMap(),
            ).also(allServices::add)
        }
        val subdomains = value.mapAt("subdomains").map { (subdomainKey, subdomainValue) ->
            parseSubdomain(
                properties, diagnostics, allServices, domainKey, domainId, domainVersion,
                subdomainKey, subdomainValue.asMap(),
            )
        }
        return ManifestDomain(
            domainKey,
            domainId,
            domainVersion,
            value["name"]?.toString(),
            value["description"]?.toString(),
            directServices,
            subdomains,
        )
    }

    private fun parseSubdomain(
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
        allServices: MutableList<ManifestService>,
        domainKey: String,
        domainId: String,
        domainVersion: String?,
        subdomainKey: String,
        value: Map<String, Any?>,
    ): ManifestSubdomain {
        val subdomainId = value["id"]?.toString().nonBlankOrNull() ?: subdomainKey
        val subdomainVersion = value["version"].stringValue()
        val services = value.mapAt("services").map { (serviceKey, serviceValue) ->
            parseService(
                properties, diagnostics, domainKey, domainId, domainVersion,
                subdomainKey, subdomainId, subdomainVersion, serviceKey, serviceValue.asMap(),
            ).also(allServices::add)
        }
        return ManifestSubdomain(
            subdomainKey,
            subdomainId,
            subdomainVersion,
            value["name"]?.toString(),
            value["description"]?.toString(),
            services,
        )
    }

    private fun parseService(
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
        domainKey: String,
        domainId: String,
        domainVersion: String?,
        subdomainKey: String?,
        subdomainId: String,
        subdomainVersion: String?,
        serviceKey: String,
        value: Map<String, Any?>,
    ): ManifestService {
        val serviceRef = listOfNotNull(domainKey, subdomainKey, serviceKey).joinToString("/")
        val serviceId = value["id"]?.toString().nonBlankOrNull() ?: serviceKey
        val docs = value.mapAt("docs").mapValuesNotNull { raw ->
            (raw as? String)?.let {
                expandStatic(it, "$serviceRef.docs", properties, diagnostics, allowRuntime = false)
            }
        }
        val artifacts = value.listAt("artifacts").mapNotNull { rawArtifact ->
            val artifact = rawArtifact.asMap()
            val type = artifact["type"] as? String ?: return@mapNotNull null
            val rawPath = artifact["path"] as? String ?: return@mapNotNull null
            ManifestArtifact(
                name = (artifact["name"] as? String).nonBlankOrNull(),
                artifactId = (artifact["artifactId"] as? String).nonBlankOrNull(),
                type = type,
                path = expandStatic(rawPath, "$serviceRef.artifacts.path", properties, diagnostics, allowRuntime = false),
                version = artifact["version"].stringValue(),
            )
        }
        return ManifestService(
            domainKey = domainKey,
            domainId = domainId,
            subdomainKey = subdomainKey,
            subdomainId = subdomainId,
            serviceKey = serviceKey,
            id = serviceId,
            repository = (value["repository"] as? String).nonBlankOrNull(),
            groupId = (value["groupId"] as? String).nonBlankOrNull(),
            version = value["version"].stringValue(),
            domainVersion = domainVersion,
            subdomainVersion = subdomainVersion,
            name = value["name"]?.toString(),
            description = value["description"]?.toString(),
            serviceRef = serviceRef,
            docs = docs,
            artifacts = artifacts,
            consumers = value.listAt("consumers").mapNotNull { normalizeConsumer(domainKey, it) },
        )
    }

    private fun parseProperties(
        values: Map<String, Any?>,
        diagnostics: MutableList<ManifestDiagnostic>,
    ): Map<String, String> = values.mapNotNull { (name, value) ->
        if (isRuntimeVariable(name)) {
            diagnostics += validationDiagnostic(
                "config.properties must not override runtime variable '$name'",
                "reserved-runtime-variable",
                "config.properties.$name",
            )
            null
        } else {
            (value as? String)?.let { name to it }
        }
    }.toMap()

    private fun parseSources(
        values: Map<String, Any?>,
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
    ): ManifestSources {
        fun expression(raw: Any?, default: String, location: String): String = expandStatic(
            raw?.toString() ?: default, location, properties, diagnostics, allowRuntime = true,
        )
        fun optionalExpression(raw: Any?, location: String): String? = raw?.toString()?.let {
            expandStatic(it, location, properties, diagnostics, allowRuntime = true)
        }
        fun static(raw: Any?, default: String? = null, location: String): String? =
            (raw?.toString() ?: default)?.let { expandStatic(it, location, properties, diagnostics, allowRuntime = false) }

        val workspace = values["workspace"]?.asMap().orEmpty()
        val git = values["git"]?.asMap()
        val apicurio = values["apicurio"]?.asMap()
        val artifactory = values["artifactory"]?.asMap()
        val maven = values["maven"]?.asMap()
        val gitProvider = git?.get("provider")?.toString().orEmpty()
        val mavenProvider = maven?.get("provider")?.toString().orEmpty()
        return ManifestSources(
            workspace = ManifestWorkspaceSource(
                expression(
                    workspace["basePathExpression"],
                    "\${domain.id}/\${subdomain.id}/\${service.id}",
                    "config.sources.workspace.basePathExpression",
                ),
            ),
            git = git?.let {
                ManifestGitSource(
                    provider = gitProvider,
                    server = static(
                        it["server"],
                        gitDefaultServer(gitProvider),
                        "config.sources.git.server",
                    ),
                    contentUrlExpression = optionalExpression(
                        it["contentUrlExpression"],
                        "config.sources.git.contentUrlExpression",
                    ),
                )
            },
            apicurio = apicurio?.let {
                ManifestApicurioSource(
                    server = static(it["server"], location = "config.sources.apicurio.server").orEmpty(),
                    contentUrlExpression = optionalExpression(
                        it["contentUrlExpression"],
                        "config.sources.apicurio.contentUrlExpression",
                    ),
                )
            },
            artifactory = artifactory?.let {
                ManifestArtifactorySource(
                    server = static(it["server"], location = "config.sources.artifactory.server").orEmpty(),
                    contentUrlExpression = expression(
                        it["contentUrlExpression"],
                        ARTIFACTORY_DEFAULT_EXPRESSION,
                        "config.sources.artifactory.contentUrlExpression",
                    ),
                )
            },
            maven = maven?.let {
                ManifestMavenSource(
                    provider = mavenProvider,
                    server = static(
                        it["server"],
                        if (mavenProvider == MAVEN_CENTRAL) CENTRAL_SERVER else null,
                        "config.sources.maven.server",
                    ).orEmpty(),
                    repository = static(
                        it["repository"],
                        if (mavenProvider == MAVEN_CENTRAL) CENTRAL_REPOSITORY else null,
                        "config.sources.maven.repository",
                    ).orEmpty(),
                )
            },
        )
    }

    private fun validateContentResolution(
        contentResolution: List<String>,
        sources: ManifestSources,
        diagnostics: MutableList<ManifestDiagnostic>,
    ) {
        contentResolution.forEachIndexed { index, source ->
            val location = "config.contentResolution[$index]"
            when {
                source !in ManifestSourceName.all -> diagnostics += validationDiagnostic(
                    "Unknown content-resolution source '$source'", "unknown-content-source", location,
                )
                source == ManifestSourceName.GIT && sources.git == null -> diagnostics += unconfiguredSource(source, location)
                source == ManifestSourceName.APICURIO && sources.apicurio == null -> diagnostics += unconfiguredSource(source, location)
                source == ManifestSourceName.ARTIFACTORY && sources.artifactory == null -> diagnostics += unconfiguredSource(source, location)
                source == ManifestSourceName.MAVEN && sources.maven == null -> diagnostics += unconfiguredSource(source, location)
            }
        }
        sources.git?.let { source ->
            if (source.provider !in GIT_PROVIDERS) diagnostics += invalidProvider("git", source.provider)
            if (source.provider == GIT_GENERIC && source.contentUrlExpression.nonBlankOrNull() == null) {
                diagnostics += missingSourceField("git", "contentUrlExpression")
            }
        }
        sources.apicurio?.let { if (it.server.isBlank()) diagnostics += missingSourceField("apicurio", "server") }
        sources.artifactory?.let {
            if (it.server.isBlank()) diagnostics += missingSourceField("artifactory", "server")
            if (it.contentUrlExpression.isBlank()) diagnostics += validationDiagnostic(
                "Source 'artifactory' contentUrlExpression must not be blank",
                "invalid-source-read-configuration",
                "config.sources.artifactory.contentUrlExpression",
            )
        }
        sources.maven?.let {
            if (it.provider !in MAVEN_PROVIDERS) diagnostics += invalidProvider("maven", it.provider)
            if (it.server.isBlank()) diagnostics += missingSourceField("maven", "server")
            if (it.repository.isBlank()) diagnostics += missingSourceField("maven", "repository")
        }
    }

    private fun validateCoordinateExpression(
        expression: String,
        location: String,
        diagnostics: MutableList<ManifestDiagnostic>,
    ) {
        val recursive = PLACEHOLDER.findAll(expression).map { it.groupValues[1] }
            .filter { it == "groupId" || it == "artifactId" }.toList()
        if (recursive.isNotEmpty()) diagnostics += validationDiagnostic(
            "Coordinate expression must not reference ${recursive.joinToString(", ")}",
            "recursive-coordinate-expression",
            location,
        )
    }

    private fun invalidProvider(source: String, provider: String) = validationDiagnostic(
        "Invalid $source provider '$provider'", "invalid-source-provider", "config.sources.$source.provider",
    )

    private fun missingSourceField(source: String, field: String) = validationDiagnostic(
        "Source '$source' requires $field", "missing-source-read-configuration", "config.sources.$source.$field",
    )

    private fun unconfiguredSource(source: String, location: String) = validationDiagnostic(
        "Content-resolution source '$source' is not configured in config.sources",
        "unconfigured-content-source",
        location,
    )

    private fun validationDiagnostic(message: String, code: String, location: String) = ManifestDiagnostic(
        message = message,
        code = code,
        location = location,
    )

    private fun expandStatic(
        rawValue: String,
        location: String,
        properties: Map<String, String>,
        diagnostics: MutableList<ManifestDiagnostic>,
        allowRuntime: Boolean,
    ): String {
        val interpolation = ManifestVariableInterpolator.interpolate(rawValue, properties)
        interpolation.unresolvedVariables.filterNot { allowRuntime && isRuntimeVariable(it) }.forEach { variable ->
            diagnostics += ManifestDiagnostic(
                "Unresolved static variable '$variable'",
                code = "unresolved-static-variable",
                location = location,
            )
        }
        return interpolation.value
    }

    private fun isRuntimeVariable(name: String): Boolean =
        name in CANONICAL_RUNTIME_VARIABLES || name == "service.docs" ||
            name.startsWith("service.docs[") || name == "server"

    private fun normalizeConsumer(currentDomain: String, value: Any?): String? = when (value) {
        is String -> if (value.trim().startsWith("#/")) normalizeJsonPointerReference(value) else normalizeServiceRef(currentDomain, value)
        is Map<*, *> -> normalizeConsumerMap(currentDomain, value)
        else -> null
    }

    private fun normalizeConsumerMap(currentDomain: String, value: Map<*, *>): String? {
        val serviceRef = value["service"] as? String
        if (serviceRef != null) return if (serviceRef.trim().startsWith("#/")) {
            normalizeJsonPointerReference(serviceRef)
        } else {
            normalizeServiceRef(currentDomain, serviceRef)
        }
        return normalizeJsonPointerReference(value["\$ref"] as? String)
    }

    private fun normalizeJsonPointerReference(pointer: String?): String? {
        val raw = pointer.nonBlankOrNull() ?: return null
        if (!raw.startsWith("#/")) return raw
        val tokens = raw.removePrefix("#/").split('/').map { it.replace("~1", "/").replace("~0", "~") }
        return when {
            tokens.size >= 4 && tokens[0] == "domains" && tokens[2] == "services" -> "${tokens[1]}/${tokens[3]}"
            tokens.size >= 6 && tokens[0] == "domains" && tokens[2] == "subdomains" && tokens[4] == "services" ->
                "${tokens[1]}/${tokens[3]}/${tokens[5]}"
            else -> raw
        }
    }

    private fun normalizeServiceRef(currentDomain: String, value: String?): String? {
        val raw = value.nonBlankOrNull() ?: return null
        return if ('/' !in raw) "$currentDomain/$raw" else raw
    }

    private fun shieldConsumerRefs(text: String): String =
        text.replace(Regex("""(^[ \t]*-[ \t]*)[${'$'}]ref:""", RegexOption.MULTILINE), "$1service:")

    private fun redactUri(uri: String): String = uri
        .replace(Regex("""(://)[^/@\s]+@"""), "$1<redacted>@")
        .replace(Regex("""([?&][^=&#]+)=([^&#]*)"""), "$1=<redacted>")

    private fun redactErrorMessage(message: String): String = redactUri(message)

    private fun gitDefaultServer(provider: String): String? = when (provider) {
        GIT_GITHUB -> "https://github.com"
        GIT_GITLAB -> "https://gitlab.com"
        GIT_BITBUCKET -> "https://api.bitbucket.org/2.0/repositories"
        else -> null
    }

    private companion object {
        const val GIT_GITHUB = "github"
        const val GIT_GITLAB = "gitlab"
        const val GIT_BITBUCKET = "bitbucket"
        const val GIT_GENERIC = "generic"
        const val MAVEN_ARTIFACTORY = "artifactory"
        const val MAVEN_CENTRAL = "central"
        const val CENTRAL_SERVER = "https://repo.maven.apache.org"
        const val CENTRAL_REPOSITORY = "maven2"
        const val APICURIO_DEFAULT_EXPRESSION =
            "\${server}/apis/registry/v3/groups/\${service.id}/artifacts/\${artifact.path}/versions/\${version}/content"
        const val ARTIFACTORY_DEFAULT_EXPRESSION =
            "\${server}/artifactory/contracts/\${domain.id}/\${subdomain.id}/\${service.id}/\${version}/\${content.path}"
        val GIT_PROVIDERS = setOf(GIT_GITHUB, GIT_GITLAB, GIT_BITBUCKET, GIT_GENERIC)
        val MAVEN_PROVIDERS = setOf(MAVEN_ARTIFACTORY, MAVEN_CENTRAL)
        val PLACEHOLDER = Regex("""[${'$'}][{]([^}]*)[}]""")
        val DOC_LOOKUP = Regex("""service[.]docs\x5B[A-Za-z0-9._-]+\x5D""")
        val CANONICAL_RUNTIME_VARIABLES = setOf(
            "domain.id", "subdomain.id", "service.id", "service.repository", "domain.version", "subdomain.version",
            "service.version", "artifact.version", "artifact.path", "artifact.name",
            "artifact.fileName", "artifact.fileNameWithoutExtension", "content.path",
            "groupId", "artifactId", "version",
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
private fun Map<*, *>.mapAt(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()

private fun Map<String, Any?>.listAt(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()

private fun Map<String, Any?>.mapValuesNotNull(transform: (Any?) -> String?): Map<String, String> =
    entries.mapNotNull { (key, value) -> transform(value)?.let { key to it } }.toMap()

private fun Any?.stringValue(): String? = when (this) {
    is String -> nonBlankOrNull()
    is Number -> toString()
    else -> null
}
