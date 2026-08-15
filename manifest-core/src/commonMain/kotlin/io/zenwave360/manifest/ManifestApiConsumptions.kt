package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.RefParser
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

enum class AsyncApiAction {
    SEND,
    RECEIVE;

    companion object {
        @JvmStatic
        fun parse(value: String?): AsyncApiAction? = when (value?.trim()?.lowercase()) {
            "send" -> SEND
            "receive" -> RECEIVE
            else -> null
        }
    }
}

enum class AsyncApiMessageKind { EVENT, COMMAND }

enum class OpenApiOperationIntent { COMMAND, QUERY }

data class OpenApiOperation(
    val operationId: String,
    val method: String,
    val path: String,
    val normalizedPath: String,
    val intent: OpenApiOperationIntent,
    val summary: String?,
    val description: String?,
)

class OpenApiOperationIndex internal constructor(
    val operations: List<OpenApiOperation>,
    val version: String?,
    val diagnostics: List<ManifestDiagnostic>,
) {
    fun byMethodAndPath(method: String, path: String): List<OpenApiOperation> = operations.filter {
        it.method == method.uppercase() && it.normalizedPath == normalizeOpenApiPath(path)
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        suspend fun parse(text: String, location: String = "openapi"): OpenApiOperationIndex =
            parseOpenApiContract(text, location)
    }
}

enum class ApiMatchKind { EXTERNAL_REF, ADDRESS, LEGACY_ADDRESS }

data class AsyncApiOperationRef(
    val operationId: String,
    val action: AsyncApiAction,
)

data class AsyncApiChannel(
    val channelKey: String,
    val address: String?,
    val messageKind: AsyncApiMessageKind,
    val summary: String?,
    val description: String?,
    val operations: List<AsyncApiOperationRef>,
)

class AsyncApiChannelIndex internal constructor(
    val channels: Map<String, AsyncApiChannel>,
    val version: String?,
    val protocols: List<String>,
    val diagnostics: List<ManifestDiagnostic>,
) {
    private val channelsByAddress = channels.values.filter { it.address != null }.groupBy { it.address!! }

    fun byAddress(address: String): List<AsyncApiChannel> = channelsByAddress[address].orEmpty()

    companion object {
        @JvmStatic
        @JvmOverloads
        suspend fun parse(text: String, location: String = "asyncapi"): AsyncApiChannelIndex =
            parseAsyncApiContract(text, location).index
    }
}

data class ApiConsumptionMatch(
    val edge: ManifestConsumptionEdge,
    val providerArtifact: ResolvedManifestArtifact,
    val channel: AsyncApiChannel,
    val consumerOperationId: String,
    val consumerAction: AsyncApiAction,
    val providerOperationId: String,
    val providerAction: AsyncApiAction,
    val matchKind: ApiMatchKind,
)

data class ApiServiceConsumption(val edge: ManifestConsumptionEdge)

data class LegacyClientMatch(
    val consumerService: ManifestService,
    val consumerArtifact: ResolvedManifestArtifact,
    val providerService: ManifestService,
    val providerArtifact: ResolvedManifestArtifact,
    val channel: AsyncApiChannel,
    val consumerOperationId: String,
    val consumerAction: AsyncApiAction,
)

data class ApiConsumptionOptions @JvmOverloads constructor(
    val loadOptions: ManifestLoadOptions = ManifestLoadOptions(),
    val rules: Map<String, List<String>> = ManifestConsumptionRules.DEFAULT,
    val legacyAddressMatching: Boolean = true,
) {
    fun withLoadOptions(options: ManifestLoadOptions): ApiConsumptionOptions = copy(loadOptions = options)

    fun withRules(rules: Map<String, List<String>>): ApiConsumptionOptions = copy(rules = rules)

    fun withLegacyAddressMatching(enabled: Boolean): ApiConsumptionOptions = copy(legacyAddressMatching = enabled)
}

class ManifestApiConsumptions private constructor(
    val consumerIndex: ManifestConsumerIndex,
    val providerChannelIndexes: Map<String, AsyncApiChannelIndex>,
    val openApiOperationIndexes: Map<String, OpenApiOperationIndex>,
    val matches: List<ApiConsumptionMatch>,
    val apiConsumptions: List<ApiServiceConsumption>,
    val legacyMatches: List<LegacyClientMatch>,
    val diagnostics: List<ManifestDiagnostic>,
) {
    fun channelIndex(artifact: ResolvedManifestArtifact): AsyncApiChannelIndex? =
        providerChannelIndexes[artifactKey(artifact)]

    fun openApiIndex(artifact: ResolvedManifestArtifact): OpenApiOperationIndex? =
        openApiOperationIndexes[artifactKey(artifact)]

    fun matchesFor(providerService: ManifestService): List<ApiConsumptionMatch> =
        matches.filter { it.edge.providerService.serviceRef == providerService.serviceRef }

    companion object {
        @JvmStatic
        @JvmOverloads
        suspend fun build(
            manifest: ZenWaveManifest,
            loader: ZenWaveManifestLoader = ZenWaveManifestLoader(),
            options: ApiConsumptionOptions = ApiConsumptionOptions(),
        ): ManifestApiConsumptions {
            val consumerIndex = ManifestConsumerIndex.build(manifest, loader, options.rules)
            val diagnostics = consumerIndex.diagnostics.toMutableList()
            val catalog = try {
                ManifestArtifactCatalog.resolve(manifest, loader)
            } catch (error: Exception) {
                diagnostics += warning(
                    error.message ?: "API consumption artifacts cannot be resolved",
                    "api-consumptions-invalid-manifest",
                    manifest.uri,
                )
                return ManifestApiConsumptions(
                    consumerIndex, emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), diagnostics,
                )
            }

            val providerArtifacts = catalog.artifacts.filter { it.artifact.type == "asyncapi" }
            val providerContracts = linkedMapOf<String, ParsedAsyncApiContract>()
            providerArtifacts.forEach { artifact ->
                loadContract(manifest, loader, artifact, options.loadOptions, diagnostics)?.let { contract ->
                    providerContracts[artifactKey(artifact)] = contract
                    diagnostics += contract.index.diagnostics
                }
            }
            val providerIndexes = providerContracts.mapValues { it.value.index }
            val openApiIndexes = linkedMapOf<String, OpenApiOperationIndex>()
            catalog.artifacts.filter { it.artifact.type == "openapi" }.forEach { artifact ->
                val loaded = loader.loadArtifactResult(manifest, artifact.owner, artifact.artifact, options.loadOptions)
                val content = loaded.content
                if (content == null) {
                    diagnostics += warning(
                        loaded.errorMessage ?: "Cannot load OpenAPI artifact '${artifact.artifact.path}'",
                        "openapi-artifact-load-failed",
                        "${artifact.ownerRef}#${artifact.artifactId}",
                    )
                } else {
                    val index = parseOpenApiContract(
                        content,
                        loaded.resource?.referenceUri() ?: artifact.artifact.path,
                    )
                    openApiIndexes[artifactKey(artifact)] = index
                    diagnostics += index.diagnostics
                }
            }

            val matches = mutableListOf<ApiConsumptionMatch>()
            val serviceConsumptions = mutableListOf<ApiServiceConsumption>()
            val consumerContracts = mutableMapOf<String, ParsedAsyncApiContract?>()
            consumerIndex.edges.forEach { edge ->
                when (edge.consumerArtifact.artifact.type) {
                    "openapi" -> {
                        if (edge.providerArtifacts.any { it.artifact.type == "openapi" }) {
                            serviceConsumptions += ApiServiceConsumption(edge)
                        }
                    }
                    "asyncapi-client" -> {
                        val consumerContract = consumerContracts.getOrPut(artifactKey(edge.consumerArtifact)) {
                            loadContract(manifest, loader, edge.consumerArtifact, options.loadOptions, diagnostics)
                        } ?: return@forEach
                        edge.providerArtifacts.filter { it.artifact.type == "asyncapi" }.forEach { providerArtifact ->
                            val provider = providerContracts[artifactKey(providerArtifact)] ?: return@forEach
                            matches += matchDeclared(edge, providerArtifact, consumerContract, provider, diagnostics)
                        }
                    }
                }
            }

            val legacyMatches = if (options.legacyAddressMatching) {
                matchLegacy(manifest, catalog, loader, options, providerContracts, consumerContracts, diagnostics)
            } else {
                emptyList()
            }

            return ManifestApiConsumptions(
                consumerIndex = consumerIndex,
                providerChannelIndexes = providerIndexes,
                openApiOperationIndexes = openApiIndexes,
                matches = matches.distinct(),
                apiConsumptions = serviceConsumptions.distinct(),
                legacyMatches = legacyMatches.distinct(),
                diagnostics = diagnostics.distinct(),
            )
        }
    }
}

private suspend fun parseOpenApiContract(text: String, location: String): OpenApiOperationIndex = try {
    val root = RefParser.fromText(text, baseUri = memoryUri(location)).parse().getRoot().stringMap()
    val diagnostics = mutableListOf<ManifestDiagnostic>()
    val operations = mutableListOf<OpenApiOperation>()
    val supported = linkedMapOf(
        "get" to OpenApiOperationIntent.QUERY,
        "head" to OpenApiOperationIntent.QUERY,
        "post" to OpenApiOperationIntent.COMMAND,
        "put" to OpenApiOperationIntent.COMMAND,
        "patch" to OpenApiOperationIntent.COMMAND,
        "delete" to OpenApiOperationIntent.COMMAND,
    )
    root["paths"].stringMap().forEach { (path, pathItemValue) ->
        val pathItem = pathItemValue.stringMap()
        supported.forEach { (verb, intent) ->
            val operation = pathItem[verb].stringMap()
            if (operation.isEmpty()) return@forEach
            val operationId = operation["operationId"]?.toString()?.takeIf(String::isNotBlank)
            if (operationId == null) {
                diagnostics += warning(
                    "OpenAPI operation '${verb.uppercase()} $path' has no operationId",
                    "missing-openapi-operation-id",
                    "$location#/paths/$path/$verb",
                )
                return@forEach
            }
            operations += OpenApiOperation(
                operationId = operationId,
                method = verb.uppercase(),
                path = path,
                normalizedPath = normalizeOpenApiPath(path),
                intent = intent,
                summary = operation["summary"]?.toString(),
                description = operation["description"]?.toString(),
            )
        }
    }
    operations.groupingBy { it.operationId }.eachCount().filterValues { it > 1 }.keys.forEach { operationId ->
        diagnostics += warning(
            "OpenAPI operationId '$operationId' is declared more than once",
            "duplicate-openapi-operation-id",
            location,
        )
    }
    OpenApiOperationIndex(
        operations = operations,
        version = root["info"].stringMap()["version"]?.toString(),
        diagnostics = diagnostics,
    )
} catch (error: Exception) {
    OpenApiOperationIndex(
        operations = emptyList(),
        version = null,
        diagnostics = listOf(warning(
            error.message ?: "Cannot parse OpenAPI document",
            "openapi-parse-failed",
            location,
        )),
    )
}

internal fun normalizeOpenApiPath(path: String): String = path
    .replace(Regex("\\{[^}/]+\\}"), "{}")
    .replace(Regex("/+"), "/")
    .removeSuffix("/")
    .ifEmpty { "/" }

private data class ParsedAsyncApiContract(
    val root: Map<String, Any?>,
    val channels: Map<String, Map<String, Any?>>,
    val operations: Map<String, Map<String, Any?>>,
    val index: AsyncApiChannelIndex,
)

private data class ConsumerChannel(
    val externalKey: String? = null,
    val address: String? = null,
)

private data class GlobalProviderChannel(
    val artifact: ResolvedManifestArtifact,
    val channel: AsyncApiChannel,
)

private suspend fun parseAsyncApiContract(text: String, location: String): ParsedAsyncApiContract {
    return try {
        val root = RefParser.fromText(text, baseUri = memoryUri(location)).parse().getRoot().stringMap()
        val rawChannels = root["channels"].stringMapOfMaps()
        val rawOperations = root["operations"].stringMapOfMaps()
        val componentMessages = root["components"].stringMap()["messages"].stringMapOfMaps()
        val diagnostics = mutableListOf<ManifestDiagnostic>()
        val operationsByChannel = linkedMapOf<String, MutableList<AsyncApiOperationRef>>()
        rawOperations.forEach { (operationId, operation) ->
            val action = AsyncApiAction.parse(operation["action"]?.toString()) ?: return@forEach
            val channelKey = resolveLocalChannelKey(operation["channel"], rawChannels) ?: return@forEach
            operationsByChannel.getOrPut(channelKey) { mutableListOf() } += AsyncApiOperationRef(operationId, action)
        }
        val channels = rawChannels.mapValues { (channelKey, channel) ->
            val operations = operationsByChannel[channelKey].orEmpty()
            val messageKind = classifyMessage(channelKey, channel, componentMessages, operations, diagnostics, location)
            AsyncApiChannel(
                channelKey = channelKey,
                address = channel["address"]?.toString()?.takeIf(String::isNotBlank),
                messageKind = messageKind,
                summary = channel["summary"]?.toString(),
                description = channel["description"]?.toString(),
                operations = operations,
            )
        }
        val protocols = root["servers"].stringMap().values.mapNotNull { server ->
            server.stringMap()["protocol"]?.toString()?.takeIf(String::isNotBlank)
        }.distinct()
        val version = root["info"].stringMap()["version"]?.toString()
        ParsedAsyncApiContract(
            root = root,
            channels = rawChannels,
            operations = rawOperations,
            index = AsyncApiChannelIndex(channels, version, protocols, diagnostics),
        )
    } catch (error: Exception) {
        val diagnostic = warning(
            error.message ?: "Cannot parse AsyncAPI document",
            "asyncapi-parse-failed",
            location,
        )
        ParsedAsyncApiContract(
            root = emptyMap(),
            channels = emptyMap(),
            operations = emptyMap(),
            index = AsyncApiChannelIndex(emptyMap(), null, emptyList(), listOf(diagnostic)),
        )
    }
}

private fun classifyMessage(
    channelKey: String,
    channel: Map<String, Any?>,
    componentMessages: Map<String, Map<String, Any?>>,
    operations: List<AsyncApiOperationRef>,
    diagnostics: MutableList<ManifestDiagnostic>,
    location: String,
): AsyncApiMessageKind {
    messageKind(channel["x-message-type"])?.let { return it }

    val messageKinds = channel["messages"].stringMap().values.mapNotNull { message ->
        val rawMessage = message.stringMap()
        val reference = rawMessage["\$ref"]?.toString()
        val resolved = localPointerKey(reference, "#/components/messages/")
            ?.let(componentMessages::get) ?: rawMessage
        messageKind(resolved["x-message-type"])
    }.toSet()
    if (messageKinds.size == 1) return messageKinds.single()
    if (messageKinds.size > 1) {
        diagnostics += warning(
            "Channel '$channelKey' declares conflicting x-message-type values; falling back to its name and direction",
            "conflicting-message-type",
            "$location#/channels/$channelKey",
        )
    }

    val normalized = channelKey.lowercase()
    val eventName = "event" in normalized
    val commandName = "command" in normalized
    if (eventName != commandName) return if (eventName) AsyncApiMessageKind.EVENT else AsyncApiMessageKind.COMMAND
    return if (operations.any { it.action == AsyncApiAction.SEND }) {
        AsyncApiMessageKind.EVENT
    } else {
        AsyncApiMessageKind.COMMAND
    }
}

private fun messageKind(value: Any?): AsyncApiMessageKind? = when (value?.toString()?.trim()?.lowercase()) {
    "event" -> AsyncApiMessageKind.EVENT
    "command" -> AsyncApiMessageKind.COMMAND
    else -> null
}

private suspend fun loadContract(
    manifest: ZenWaveManifest,
    loader: ZenWaveManifestLoader,
    artifact: ResolvedManifestArtifact,
    options: ManifestLoadOptions,
    diagnostics: MutableList<ManifestDiagnostic>,
): ParsedAsyncApiContract? {
    val loaded = loader.loadArtifactResult(manifest, artifact.owner, artifact.artifact, options)
    val content = loaded.content
    if (content == null) {
        diagnostics += warning(
            loaded.errorMessage ?: "Cannot load API artifact '${artifact.artifact.path}'",
            "api-artifact-load-failed",
            "${artifact.ownerRef}#${artifact.artifactId}",
        )
        return null
    }
    val contract = parseAsyncApiContract(content, loaded.resource?.referenceUri() ?: artifact.artifact.path)
    diagnostics += contract.index.diagnostics
    return contract
}

private fun matchDeclared(
    edge: ManifestConsumptionEdge,
    providerArtifact: ResolvedManifestArtifact,
    consumer: ParsedAsyncApiContract,
    provider: ParsedAsyncApiContract,
    diagnostics: MutableList<ManifestDiagnostic>,
): List<ApiConsumptionMatch> {
    val results = mutableListOf<ApiConsumptionMatch>()
    consumer.operations.forEach { (operationId, operation) ->
        val consumerAction = AsyncApiAction.parse(operation["action"]?.toString()) ?: return@forEach
        val consumerChannel = resolveConsumerChannel(operation["channel"], consumer.channels)
        val channelAndKind = when {
            consumerChannel.externalKey != null -> provider.index.channels[consumerChannel.externalKey]
                ?.let { it to ApiMatchKind.EXTERNAL_REF }
                ?: addressMatch(provider.index, consumerChannel.address, diagnostics, edge, providerArtifact, operationId)
            else -> addressMatch(provider.index, consumerChannel.address, diagnostics, edge, providerArtifact, operationId)
        } ?: return@forEach
        val (channel, matchKind) = channelAndKind
        val providerAction = consumerAction.complementary()
        val providerOperation = channel.operations.firstOrNull { it.action == providerAction }
        if (providerOperation == null) {
            diagnostics += warning(
                "Consumer operation '$operationId' action '${consumerAction.serialized()}' has no complementary provider operation in " +
                    "${edge.providerService.id}#${providerArtifact.artifactId}",
                "no-complementary-operation",
                "${edge.consumerArtifact.ownerRef}#${edge.consumerArtifact.artifactId}#/operations/$operationId",
            )
            return@forEach
        }
        results += ApiConsumptionMatch(
            edge, providerArtifact, channel, operationId, consumerAction,
            providerOperation.operationId, providerOperation.action, matchKind,
        )
    }
    return results
}

private fun addressMatch(
    provider: AsyncApiChannelIndex,
    address: String?,
    diagnostics: MutableList<ManifestDiagnostic>,
    edge: ManifestConsumptionEdge,
    providerArtifact: ResolvedManifestArtifact,
    operationId: String,
): Pair<AsyncApiChannel, ApiMatchKind>? {
    if (address == null) return null
    val matches = provider.byAddress(address)
    if (matches.size == 1) return matches.single() to ApiMatchKind.ADDRESS
    if (matches.size > 1) {
        diagnostics += warning(
            "Consumer channel address '$address' is ambiguous in ${edge.providerService.id}#${providerArtifact.artifactId}",
            "ambiguous-channel-address",
            "${edge.consumerArtifact.ownerRef}#${edge.consumerArtifact.artifactId}#/operations/$operationId",
        )
    }
    return null
}

private suspend fun matchLegacy(
    manifest: ZenWaveManifest,
    catalog: ManifestArtifactCatalog,
    loader: ZenWaveManifestLoader,
    options: ApiConsumptionOptions,
    providerContracts: Map<String, ParsedAsyncApiContract>,
    consumerContracts: MutableMap<String, ParsedAsyncApiContract?>,
    diagnostics: MutableList<ManifestDiagnostic>,
): List<LegacyClientMatch> {
    val qualifiedConsumerServiceRefs = manifest.services.flatMap { provider ->
        provider.consumers.mapNotNull { raw ->
            if ('#' !in raw) return@mapNotNull null
            val reference = try {
                ManifestConsumerReference.parse(raw)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            manifest.findService(reference.serviceReference)
                ?: manifest.servicesByRef["${provider.domainKey}/${reference.serviceReference}"]
        }
    }.mapTo(mutableSetOf()) { it.serviceRef }

    val globalByAddress = linkedMapOf<String, GlobalProviderChannel>()
    catalog.artifacts.filter { it.artifact.type == "asyncapi" }.forEach { artifact ->
        providerContracts[artifactKey(artifact)]?.index?.channels?.values?.forEach { channel ->
            channel.address?.let { globalByAddress[it] = GlobalProviderChannel(artifact, channel) }
        }
    }

    val results = mutableListOf<LegacyClientMatch>()
    manifest.services.filter { it.serviceRef !in qualifiedConsumerServiceRefs }.forEach { service ->
        catalog.owner(service.serviceRef).artifacts.filter { it.artifact.type == "asyncapi-client" }.forEach { artifact ->
            val contract = consumerContracts.getOrPut(artifactKey(artifact)) {
                loadContract(manifest, loader, artifact, options.loadOptions, diagnostics)
            } ?: return@forEach
            contract.operations.forEach { (operationId, operation) ->
                val action = AsyncApiAction.parse(operation["action"]?.toString()) ?: return@forEach
                val address = resolveConsumerChannel(operation["channel"], contract.channels).address ?: return@forEach
                val provider = globalByAddress[address]
                if (provider == null) {
                    diagnostics += warning(
                        "Legacy AsyncAPI client channel address '$address' was not found in a provider contract",
                        "legacy-address-unmatched",
                        "${artifact.ownerRef}#${artifact.artifactId}#/operations/$operationId",
                    )
                    return@forEach
                }
                val providerService = provider.artifact.owner as? ManifestService ?: return@forEach
                results += LegacyClientMatch(
                    service, artifact, providerService, provider.artifact, provider.channel, operationId, action,
                )
            }
        }
    }
    return results
}

private fun resolveConsumerChannel(
    operationChannel: Any?,
    channels: Map<String, Map<String, Any?>>,
): ConsumerChannel {
    val pointer = operationChannel.stringMap()
    val operationReference = pointer["\$ref"]?.toString()
    val localKey = localPointerKey(operationReference, "#/channels/")
    if (localKey != null) {
        val channel = channels[localKey].orEmpty()
        return ConsumerChannel(
            externalKey = externalChannelKey(channel["\$ref"]?.toString()),
            address = channel["address"]?.toString()?.takeIf(String::isNotBlank),
        )
    }
    return ConsumerChannel(address = pointer["address"]?.toString()?.takeIf(String::isNotBlank))
}

private fun resolveLocalChannelKey(value: Any?, channels: Map<String, Map<String, Any?>>): String? {
    val pointer = value.stringMap()
    val key = localPointerKey(pointer["\$ref"]?.toString(), "#/channels/")
    if (key != null) return key.takeIf(channels::containsKey)
    val address = pointer["address"]?.toString() ?: return null
    return channels.entries.singleOrNull { it.value["address"]?.toString() == address }?.key
}

private fun externalChannelKey(reference: String?): String? {
    if (reference == null) return null
    val marker = "#/channels/"
    val index = reference.indexOf(marker)
    return if (index < 0) null else decodeJsonPointerSegment(reference.substring(index + marker.length))
}

private fun localPointerKey(reference: String?, prefix: String): String? =
    reference?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let(::decodeJsonPointerSegment)

private fun decodeJsonPointerSegment(value: String): String = value.replace("~1", "/").replace("~0", "~")

private fun AsyncApiAction.complementary(): AsyncApiAction =
    if (this == AsyncApiAction.SEND) AsyncApiAction.RECEIVE else AsyncApiAction.SEND

internal fun AsyncApiAction.serialized(): String = name.lowercase()

internal fun ApiMatchKind.serialized(): String = name.lowercase().replace('_', '-')

private fun artifactKey(artifact: ResolvedManifestArtifact): String = "${artifact.ownerRef}#${artifact.artifactId}"

private fun memoryUri(location: String): String =
    if (ManifestReferenceResolver.hasScheme(location)) location else "memory:///${location.trimStart('/')}"

private fun warning(message: String, code: String, location: String?): ManifestDiagnostic = ManifestDiagnostic(
    message = message,
    severity = ManifestDiagnosticSeverity.WARNING,
    code = code,
    location = location,
)

private fun Any?.stringMap(): Map<String, Any?> = when (this) {
    is Map<*, *> -> entries.associate { it.key.toString() to it.value }
    else -> emptyMap()
}

private fun Any?.stringMapOfMaps(): Map<String, Map<String, Any?>> =
    stringMap().mapValues { it.value.stringMap() }
