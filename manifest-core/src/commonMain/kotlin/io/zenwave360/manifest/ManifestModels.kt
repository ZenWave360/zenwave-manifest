package io.zenwave360.manifest

data class ZenWaveManifest(
    val uri: String,
    val config: ManifestConfig = ManifestConfig(),
    val domains: List<ManifestDomain> = emptyList(),
    val services: List<ManifestService> = emptyList(),
    val diagnostics: List<ManifestDiagnostic> = emptyList(),
) {
    val servicesByRef: Map<String, ManifestService> = services.associateBy { it.serviceRef }
    val servicesById: Map<String, ManifestService> = services.associateBy { it.id }

    fun findService(reference: String): ManifestService? =
        servicesByRef[reference] ?: servicesById[reference]

    val artifactOwners: List<ManifestArtifactOwner>
        get() = domains + services
}

sealed interface ManifestArtifactOwner {
    val id: String
    val repository: String?
    val groupId: String?
    val version: String?
    val artifacts: List<ManifestArtifact>
    val artifactOwnerRef: String
}

data class ManifestConfig(
    val title: String? = null,
    val version: String? = null,
    val groupIdExpression: String = "\${owner.id}",
    val artifactIdExpression: String = "\${artifact.fileNameWithoutExtension}",
    val properties: Map<String, String> = emptyMap(),
    val contentResolution: List<String> = listOf(ManifestSourceName.WORKSPACE),
    val sources: ManifestSources = ManifestSources(),
)

object ManifestSourceName {
    const val WORKSPACE = "workspace"
    const val GIT = "git"
    const val APICURIO = "apicurio"
    const val ARTIFACTORY = "artifactory"
    const val MAVEN = "maven"

    val all: Set<String> = setOf(WORKSPACE, GIT, APICURIO, ARTIFACTORY, MAVEN)
}

data class ManifestSources(
    val workspace: ManifestWorkspaceSource = ManifestWorkspaceSource(),
    val git: ManifestGitSource? = null,
    val apicurio: ManifestApicurioSource? = null,
    val artifactory: ManifestArtifactorySource? = null,
    val maven: ManifestMavenSource? = null,
)

data class ManifestWorkspaceSource(
    val basePathExpression: String = "\${domain.id}/\${subdomain.id}/\${service.id}",
)

data class ManifestGitSource(
    val provider: String,
    val server: String? = null,
    val contentUrlExpression: String? = null,
)

data class ManifestApicurioSource(
    val server: String,
    val contentUrlExpression: String? = null,
)

data class ManifestArtifactorySource(
    val server: String,
    val contentUrlExpression: String =
        "\${server}/artifactory/contracts/\${domain.id}/\${subdomain.id}/\${service.id}/\${version}/\${content.path}",
)

data class ManifestMavenSource(
    val provider: String,
    val server: String,
    val repository: String,
)

data class ManifestDomain(
    val key: String,
    override val id: String,
    override val version: String? = null,
    val name: String? = null,
    val description: String? = null,
    val services: List<ManifestService> = emptyList(),
    val subdomains: List<ManifestSubdomain> = emptyList(),
    override val repository: String? = null,
    override val groupId: String? = null,
    override val artifacts: List<ManifestArtifact> = emptyList(),
    val docs: Map<String, String> = emptyMap(),
) : ManifestArtifactOwner {
    override val artifactOwnerRef: String
        get() = key

    fun findArtifact(type: String): ManifestArtifact? = artifacts.firstOrNull { it.type == type }

    fun findArtifacts(type: String): List<ManifestArtifact> = artifacts.filter { it.type == type }
}

data class ManifestSubdomain(
    val key: String,
    val id: String,
    val version: String? = null,
    val name: String? = null,
    val description: String? = null,
    val services: List<ManifestService> = emptyList(),
)

data class ManifestService(
    val domainKey: String,
    val domainId: String,
    val subdomainKey: String?,
    val subdomainId: String,
    val serviceKey: String,
    override val id: String,
    override val groupId: String? = null,
    override val version: String? = null,
    val domainVersion: String? = null,
    val subdomainVersion: String? = null,
    val name: String? = null,
    val description: String? = null,
    val serviceRef: String,
    val docs: Map<String, String> = emptyMap(),
    override val artifacts: List<ManifestArtifact> = emptyList(),
    val consumers: List<String> = emptyList(),
    override val repository: String? = null,
) : ManifestArtifactOwner {
    override val artifactOwnerRef: String
        get() = serviceRef

    /**
     * Effective `${version}` for a service document: the closest explicit declaration in the
     * service, subdomain, domain chain. Artifacts never take part in this inheritance.
     */
    fun documentVersion(): String? =
        version.nonBlankOrNull()
            ?: subdomainVersion.nonBlankOrNull()
            ?: domainVersion.nonBlankOrNull()

    fun findArtifact(type: String): ManifestArtifact? =
        artifacts.firstOrNull { it.type == type }

    fun findArtifacts(type: String): List<ManifestArtifact> =
        artifacts.filter { it.type == type }
}

data class ManifestArtifact(
    val name: String? = null,
    val artifactId: String? = null,
    val type: String,
    val path: String,
    val version: String? = null,
) {
    /**
     * Effective `${version}` for this artifact: its own declared version, with no inheritance from
     * the owning service, subdomain, or domain. `version` is a required artifact field, so a valid
     * manifest always resolves it.
     */
    val resolvedVersion: String?
        get() = version.nonBlankOrNull()

    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    val fileNameWithoutExtension: String
        get() {
            val file = fileName
            val extensionIndex = file.lastIndexOf('.')
            return if (extensionIndex <= 0) file else file.substring(0, extensionIndex)
        }
}

data class ManifestCoordinates(
    val groupId: String,
    val artifactId: String,
)

data class ManifestResolutionContext(
    val domainId: String,
    val subdomainId: String,
    val serviceId: String,
    val domainVersion: String?,
    val subdomainVersion: String?,
    val serviceVersion: String?,
    val artifact: ManifestArtifact?,
    val contentPath: String,
    val docs: Map<String, String>,
    val groupId: String? = null,
    val artifactId: String? = null,
    /**
     * Effective `${version}` for the operation being resolved: [ManifestArtifact.resolvedVersion]
     * for an artifact load and [ManifestService.documentVersion] for a service-document load.
     */
    val version: String? = null,
    val repository: String? = null,
    val ownerId: String = serviceId,
    val ownerRepository: String? = repository,
) {
    fun variables(): Map<String, String> = buildMap {
        put("domain.id", domainId)
        put("subdomain.id", subdomainId)
        put("service.id", serviceId)
        repository.nonBlankOrNull()?.let { put("service.repository", it) }
        put("owner.id", ownerId)
        ownerRepository.nonBlankOrNull()?.let { put("owner.repository", it) }
        domainVersion.nonBlankOrNull()?.let { put("domain.version", it) }
        subdomainVersion.nonBlankOrNull()?.let { put("subdomain.version", it) }
        serviceVersion.nonBlankOrNull()?.let { put("service.version", it) }
        put("content.path", contentPath)
        artifact?.let {
            put("artifact.path", it.path)
            put("artifact.fileName", it.fileName)
            put("artifact.fileNameWithoutExtension", it.fileNameWithoutExtension)
            it.name.nonBlankOrNull()?.let { name -> put("artifact.name", name) }
            it.resolvedVersion?.let { version -> put("artifact.version", version) }
        }
        docs.forEach { (key, value) -> put("service.docs[$key]", value) }
        groupId.nonBlankOrNull()?.let { put("groupId", it) }
        artifactId.nonBlankOrNull()?.let { put("artifactId", it) }
        version.nonBlankOrNull()?.let { put("version", it) }
    }
}

data class ManifestLoadOptions @kotlin.jvm.JvmOverloads constructor(
    val preferredSource: String? = null,
    val allowFallback: Boolean = true,
) {
    fun withPreferredSource(source: String?): ManifestLoadOptions =
        copy(preferredSource = source.nonBlankOrNull())

    fun withFallback(allow: Boolean): ManifestLoadOptions =
        copy(allowFallback = allow)
}

data class ManifestResolvedResource(
    val source: String,
    val uri: String,
    val archiveEntry: String? = null,
) {
    fun resolveReference(reference: String): ManifestResolvedResource {
        if (reference.isBlank()) return this
        if (archiveEntry != null && !ManifestReferenceResolver.hasScheme(reference)) {
            return copy(archiveEntry = ManifestReferenceResolver.resolvePathReference(archiveEntry, reference))
        }
        return copy(
            uri = ManifestReferenceResolver.resolveReference(uri, reference),
            archiveEntry = null,
        )
    }

    fun referenceUri(): String =
        archiveEntry?.let { "$uri!/${it.trimStart('/')}" } ?: uri
}

/**
 * Non-throwing result for batch resource loading.
 *
 * Tooling can retain successfully loaded content while presenting [errorMessage] for an
 * individual document that could not be resolved or read.
 */
data class ManifestResourceLoadResult(
    val path: String,
    val resource: ManifestResolvedResource? = null,
    val content: String? = null,
    val errorMessage: String? = null,
) {
    val successful: Boolean
        get() = content != null
}

fun interface ManifestArchiveEntryLoader {
    suspend fun loadEntry(uri: String, entryPath: String): String
}

internal expect fun defaultManifestArchiveEntryLoader(): ManifestArchiveEntryLoader?

internal fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
