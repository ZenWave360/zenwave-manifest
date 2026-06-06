package io.zenwave360.manifest

data class ZenWaveManifest(
    val uri: String,
    val config: ManifestConfig = ManifestConfig(),
    val domains: List<ManifestDomain> = emptyList(),
    val services: List<ManifestService> = emptyList(),
    val diagnostics: List<ManifestDiagnostic> = emptyList(),
) {
    val servicesByRef: Map<String, ManifestService> = services.associateBy { it.serviceRef }
    val servicesById: Map<String, ManifestService> = services.mapNotNull { service ->
        service.id?.let { it to service }
    }.toMap()

    fun findService(reference: String): ManifestService? =
        servicesByRef[reference] ?: servicesById[reference]
}

data class ManifestConfig(
    val title: String? = null,
    val version: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val sourcePriority: List<String> = listOf("file", "http", "apicurio"),
    val naming: ManifestNaming = ManifestNaming(),
    val sources: ManifestSources = ManifestSources(),
)

data class ManifestNaming(
    val groupIdExpression: String? = null,
    val artifactIdExpression: String? = null,
)

data class ManifestSources(
    val http: ManifestHttpSource? = null,
    val apicurio: ManifestApicurioSource? = null,
)

data class ManifestHttpSource(
    val enabled: Boolean = true,
    val roots: List<String> = emptyList(),
)

data class ManifestApicurioSource(
    val enabled: Boolean = true,
    val registryUrl: String? = null,
    val branch: String = "latest",
    val contentUrlExpression: String = "/groups/\${groupId}/artifacts/\${artifactId}/branches/\${branch}",
)

data class ManifestDomain(
    val key: String,
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val services: List<ManifestService> = emptyList(),
    val subdomains: List<ManifestSubdomain> = emptyList(),
)

data class ManifestSubdomain(
    val key: String,
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val services: List<ManifestService> = emptyList(),
)

data class ManifestService(
    val domainKey: String,
    val subdomainKey: String?,
    val serviceKey: String,
    val id: String? = null,
    val version: String? = null,
    val name: String? = null,
    val description: String? = null,
    val serviceRef: String,
    val path: String,
    val docs: Map<String, String> = emptyMap(),
    val artifacts: List<ManifestArtifact> = emptyList(),
    val consumers: List<String> = emptyList(),
)

data class ManifestArtifact(
    val name: String,
    val type: String,
    val pathExpression: String,
    val version: String? = null,
)

data class ManifestLoadOptions(
    val preferredSource: String? = null,
    val allowFallback: Boolean = true,
    val localRoots: List<String> = emptyList(),
)

data class ManifestResolvedResource(
    val source: String,
    val uri: String,
)
