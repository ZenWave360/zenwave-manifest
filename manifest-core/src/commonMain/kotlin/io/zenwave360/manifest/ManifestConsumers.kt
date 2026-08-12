package io.zenwave360.manifest

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Reference to an artifact on a service declared in a service-level `consumers` section.
 *
 * The canonical string form is `service.id#artifact.id`. Suffix-less references are retained for
 * backwards compatibility but cannot produce an artifact consumption edge.
 */
data class ManifestConsumerReference(
    val raw: String,
    val serviceReference: String,
    val artifactSelector: String? = null,
) {
    companion object {
        @JvmStatic
        fun parse(raw: String): ManifestConsumerReference {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Consumer reference is required" }
            val separator = value.indexOf('#')
            if (separator < 0) return ManifestConsumerReference(value, value)
            require(separator > 0) { "Consumer service reference is required before '#'" }
            val selector = value.substring(separator + 1).trim()
            require(selector.isNotEmpty()) { "Consumer artifact selector is required after '#'" }
            return ManifestConsumerReference(
                raw = value,
                serviceReference = value.substring(0, separator).trim(),
                artifactSelector = selector,
            )
        }
    }
}

/** Direct compatibility rules expressed as consumer artifact type -> provider artifact types. */
object ManifestConsumptionRules {
    @JvmStatic
    val DEFAULT: Map<String, List<String>> = mapOf(
        "asyncapi-client" to listOf("asyncapi"),
        "openapi" to listOf("openapi"),
    )

    @JvmStatic
    @JvmOverloads
    fun providerTypesFor(
        consumerType: String,
        rules: Map<String, List<String>> = DEFAULT,
    ): List<String> = rules[consumerType].orEmpty()
}

/**
 * A fully resolved and type-compatible declared consumption relationship.
 *
 * Phase one resolves service-owned consumer artifacts only. Supporting domain-owned artifacts such
 * as zfl requires lifting [consumerService] to an artifact-owner abstraction in a later extension.
 */
data class ManifestConsumptionEdge(
    val providerService: ManifestService,
    val consumerService: ManifestService,
    val consumerArtifact: ResolvedManifestArtifact,
    val providerArtifacts: List<ResolvedManifestArtifact>,
    val reference: ManifestConsumerReference,
)

/**
 * Resolves service-level consumer declarations into artifact-level candidate edges.
 * Phase one deliberately indexes service-owned consumer artifacts only; domain-owned zfl artifacts
 * are outside this index until consumption edges use a general artifact-owner abstraction.
 *
 * The index is deliberately non-throwing. Invalid declarations are represented by diagnostics and
 * are not exposed as usable edges.
 */
class ManifestConsumerIndex private constructor(
    val edges: List<ManifestConsumptionEdge>,
    val diagnostics: List<ManifestDiagnostic>,
) {
    companion object {
        private const val TYPE_SELECTOR_PREFIX = "type:"

        @JvmStatic
        @JvmOverloads
        fun build(
            manifest: ZenWaveManifest,
            resolver: ZenWaveManifestLoader = ZenWaveManifestLoader(),
            rules: Map<String, List<String>> = ManifestConsumptionRules.DEFAULT,
        ): ManifestConsumerIndex {
            val diagnostics = mutableListOf<ManifestDiagnostic>()
            val catalog = try {
                ManifestArtifactCatalog.resolve(manifest, resolver)
            } catch (exception: ManifestValidationException) {
                diagnostics += ManifestDiagnostic(
                    message = "Consumer artifacts cannot be resolved from an invalid manifest",
                    severity = ManifestDiagnosticSeverity.WARNING,
                    code = "consumer-index-invalid-manifest",
                    location = manifest.uri,
                )
                return ManifestConsumerIndex(emptyList(), diagnostics)
            }

            val edges = mutableListOf<ManifestConsumptionEdge>()
            manifest.services.forEach { providerService ->
                providerService.consumers.forEachIndexed consumerLoop@{ index, raw ->
                    val location = "${providerService.serviceRef}.consumers[$index]"
                    val reference = try {
                        ManifestConsumerReference.parse(raw)
                    } catch (exception: IllegalArgumentException) {
                        diagnostics += warning(exception.message ?: "Invalid consumer reference", "invalid-consumer-reference", location)
                        return@consumerLoop
                    }
                    val selector = reference.artifactSelector ?: return@consumerLoop
                    val consumerService = resolveService(manifest, providerService, reference.serviceReference)
                    if (consumerService == null) {
                        diagnostics += warning(
                            "Consumer service '${reference.serviceReference}' cannot be resolved",
                            "unresolved-consumer-reference",
                            location,
                        )
                        return@consumerLoop
                    }

                    val consumerInventory = catalog.owner(consumerService.serviceRef).artifacts
                    val selectedArtifacts = selectConsumerArtifacts(consumerInventory, selector, diagnostics, location)
                    selectedArtifacts.forEach { consumerArtifact ->
                        val providerTypes = ManifestConsumptionRules.providerTypesFor(consumerArtifact.artifact.type, rules)
                        if (providerTypes.isEmpty()) {
                            diagnostics += warning(
                                "Consumer artifact type '${consumerArtifact.artifact.type}' has no consumption rule",
                                "unsupported-consumer-artifact-type",
                                location,
                            )
                            return@forEach
                        }
                        val providerArtifacts = catalog.owner(providerService.serviceRef).artifacts
                            .filter { it.artifact.type in providerTypes }
                        if (providerArtifacts.isEmpty()) {
                            diagnostics += warning(
                                "Service '${providerService.id}' has no provider artifact compatible with " +
                                    "consumer type '${consumerArtifact.artifact.type}'",
                                "unresolved-provider-artifact",
                                location,
                            )
                            return@forEach
                        }
                        edges += ManifestConsumptionEdge(
                            providerService = providerService,
                            consumerService = consumerService,
                            consumerArtifact = consumerArtifact,
                            providerArtifacts = providerArtifacts,
                            reference = reference,
                        )
                    }
                }
            }
            return ManifestConsumerIndex(edges, diagnostics)
        }

        private fun resolveService(
            manifest: ZenWaveManifest,
            declaringService: ManifestService,
            reference: String,
        ): ManifestService? = manifest.findService(reference)
            ?: manifest.servicesByRef["${declaringService.domainKey}/$reference"]

        private fun selectConsumerArtifacts(
            inventory: List<ResolvedManifestArtifact>,
            selector: String,
            diagnostics: MutableList<ManifestDiagnostic>,
            location: String,
        ): List<ResolvedManifestArtifact> {
            if (selector.startsWith(TYPE_SELECTOR_PREFIX)) {
                val type = selector.removePrefix(TYPE_SELECTOR_PREFIX).trim()
                if (type.isEmpty()) {
                    diagnostics += warning("Consumer artifact type is required", "invalid-consumer-artifact", location)
                    return emptyList()
                }
                val matches = inventory.filter { it.artifact.type == type }
                if (matches.isEmpty()) {
                    diagnostics += warning(
                        "Consumer artifact type '$type' cannot be resolved",
                        "unresolved-consumer-artifact",
                        location,
                    )
                }
                return matches
            }

            val artifactIdMatches = inventory.filter { it.artifactId == selector }
            if (artifactIdMatches.size == 1) return artifactIdMatches
            if (artifactIdMatches.size > 1) {
                diagnostics += warning(
                    "Consumer artifactId '$selector' is ambiguous (${artifactIdMatches.size} matches)",
                    "ambiguous-consumer-artifact",
                    location,
                )
                return emptyList()
            }

            val typeMatches = inventory.filter { it.artifact.type == selector }
            if (typeMatches.size == 1) return typeMatches
            if (typeMatches.size > 1) {
                diagnostics += warning(
                    "Consumer artifact type fallback '$selector' is ambiguous (${typeMatches.size} matches); " +
                        "use an effective artifactId",
                    "ambiguous-consumer-artifact",
                    location,
                )
                return emptyList()
            }
            diagnostics += warning(
                "Consumer artifact '$selector' cannot be resolved",
                "unresolved-consumer-artifact",
                location,
            )
            return emptyList()
        }

        private fun warning(message: String, code: String, location: String): ManifestDiagnostic = ManifestDiagnostic(
            message = message,
            severity = ManifestDiagnosticSeverity.WARNING,
            code = code,
            location = location,
        )
    }

    fun consumersOf(service: ManifestService): List<ManifestConsumptionEdge> =
        edges.filter { it.providerService.serviceRef == service.serviceRef }

    fun consumptionsBy(service: ManifestService): List<ManifestConsumptionEdge> =
        edges.filter { it.consumerService.serviceRef == service.serviceRef }
}
