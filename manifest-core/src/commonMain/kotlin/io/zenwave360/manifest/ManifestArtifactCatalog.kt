package io.zenwave360.manifest

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

sealed interface ManifestOwnerSelector {
    val value: String

    data class OwnerRef(override val value: String) : ManifestOwnerSelector

    data class Repository(override val value: String) : ManifestOwnerSelector
}

sealed interface ManifestArtifactSelector {
    val owner: ManifestOwnerSelector

    data class ArtifactId(
        override val owner: ManifestOwnerSelector,
        val artifactId: String,
    ) : ManifestArtifactSelector

    data class Type(
        override val owner: ManifestOwnerSelector,
        val type: String,
    ) : ManifestArtifactSelector {
        init {
            require(type.isNotBlank()) { "artifact type is required" }
        }
    }

    companion object {
        const val ASYNCAPI_ALL = "asyncapi-all"

        @JvmStatic
        fun artifactInRepository(repository: String, artifactId: String): ManifestArtifactSelector =
            ArtifactId(ManifestOwnerSelector.Repository(repository), artifactId)

        @JvmStatic
        fun artifactInOwner(ownerRef: String, artifactId: String): ManifestArtifactSelector =
            ArtifactId(ManifestOwnerSelector.OwnerRef(ownerRef), artifactId)

        @JvmStatic
        fun typeInRepository(
            repository: String,
            type: String,
        ): ManifestArtifactSelector = Type(ManifestOwnerSelector.Repository(repository), type)

        @JvmStatic
        fun typeInOwner(
            ownerRef: String,
            type: String,
        ): ManifestArtifactSelector = Type(ManifestOwnerSelector.OwnerRef(ownerRef), type)

        @JvmStatic
        fun parseInRepository(repository: String, selector: String): ManifestArtifactSelector = when {
            selector == ASYNCAPI_ALL -> typeInRepository(repository, selector)
            selector.startsWith(TYPE_SELECTOR_PREFIX) ->
                typeInRepository(repository, selector.removePrefix(TYPE_SELECTOR_PREFIX))
            else -> artifactInRepository(repository, selector)
        }
    }
}

class ResolvedManifestArtifact internal constructor(
    val owner: ManifestArtifactOwner,
    val artifact: ManifestArtifact,
    val coordinates: ManifestCoordinates,
    internal val artifactIndex: Int,
) {
    val ownerRef: String
        get() = owner.artifactOwnerRef

    val ownerId: String
        get() = owner.id

    val repository: String?
        get() = owner.repository

    val groupId: String
        get() = coordinates.groupId

    val artifactId: String
        get() = coordinates.artifactId

    val version: String
        get() = requireNotNull(artifact.resolvedVersion) {
            "Artifact '$artifactId' has no resolved version"
        }
}

sealed interface ManifestArtifactSelection {
    val selector: ManifestArtifactSelector
    val artifacts: List<ResolvedManifestArtifact>

    data class Declared(
        override val selector: ManifestArtifactSelector.ArtifactId,
        val artifact: ResolvedManifestArtifact,
    ) : ManifestArtifactSelection {
        override val artifacts: List<ResolvedManifestArtifact> = listOf(artifact)
    }

    data class ByType(
        override val selector: ManifestArtifactSelector.Type,
        val owner: ManifestArtifactOwner,
        override val artifacts: List<ResolvedManifestArtifact>,
    ) : ManifestArtifactSelection
}

class ManifestArtifactSelectionException(
    val selector: ManifestArtifactSelector,
    message: String,
) : IllegalArgumentException(message)

class ManifestArtifactInventory internal constructor(
    val artifacts: List<ResolvedManifestArtifact>,
) {
    fun requireNotEmpty(description: String): ManifestArtifactInventory {
        if (artifacts.isEmpty()) throw IllegalArgumentException("No manifest artifacts found for $description")
        return this
    }

    fun duplicateArtifactIds(): Set<String> = artifacts
        .groupingBy { it.artifactId }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    fun requireUniqueArtifactIds(): ManifestArtifactInventory {
        val duplicates = duplicateArtifactIds().sorted()
        if (duplicates.isNotEmpty()) {
            throw IllegalArgumentException("Duplicate resolved artifactId(s): ${duplicates.joinToString(", ")}")
        }
        return this
    }

    fun requireArtifact(artifactId: String): ResolvedManifestArtifact {
        val matches = artifacts.filter { it.artifactId == artifactId }
        if (matches.size != 1) {
            throw IllegalArgumentException(
                "Expected artifactId '$artifactId' exactly once, found ${matches.size}",
            )
        }
        return matches.single()
    }
}

class ManifestArtifactCatalog private constructor(
    val manifest: ZenWaveManifest,
    val artifacts: List<ResolvedManifestArtifact>,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun resolve(
            manifest: ZenWaveManifest,
            resolver: ZenWaveManifestLoader = ZenWaveManifestLoader(),
        ): ManifestArtifactCatalog {
            manifest.requireValid()
            val artifacts = manifest.artifactOwners.flatMap { owner ->
                owner.artifacts.mapIndexed { index, artifact ->
                    val context = resolver.artifactResolutionContext(manifest, owner, artifact)
                    ResolvedManifestArtifact(
                        owner = owner,
                        artifact = artifact,
                        coordinates = ManifestCoordinates(
                            groupId = requireNotNull(context.groupId),
                            artifactId = requireNotNull(context.artifactId),
                        ),
                        artifactIndex = index,
                    )
                }
            }
            return ManifestArtifactCatalog(manifest, artifacts)
        }
    }

    fun inventory(owner: ManifestOwnerSelector): ManifestArtifactInventory =
        ManifestArtifactInventory(artifacts.filter { owner.matches(it.owner) })

    fun repository(repository: String): ManifestArtifactInventory =
        inventory(ManifestOwnerSelector.Repository(repository))

    fun owner(ownerRef: String): ManifestArtifactInventory =
        inventory(ManifestOwnerSelector.OwnerRef(ownerRef))

    fun resolve(selector: ManifestArtifactSelector): ManifestArtifactSelection = when (selector) {
        is ManifestArtifactSelector.ArtifactId -> selectDeclared(selector)
        is ManifestArtifactSelector.Type -> selectType(selector)
    }

    fun resolveByArtifactId(
        owner: ManifestOwnerSelector,
        artifactId: String,
    ): ManifestArtifactSelection.Declared = selectDeclared(ManifestArtifactSelector.ArtifactId(owner, artifactId))

    fun resolveByType(
        owner: ManifestOwnerSelector,
        type: String,
    ): ManifestArtifactSelection.ByType = selectType(ManifestArtifactSelector.Type(owner, type))

    private fun selectDeclared(selector: ManifestArtifactSelector.ArtifactId): ManifestArtifactSelection.Declared {
        val matches = artifacts.filter {
            selector.owner.matches(it.owner) && it.artifactId == selector.artifactId
        }
        if (matches.size != 1) {
            throw ManifestArtifactSelectionException(
                selector,
                "Expected artifactId '${selector.artifactId}' exactly once for " +
                    "${selector.owner.describe()}, found ${matches.size}${matches.describeOwners()}",
            )
        }
        return ManifestArtifactSelection.Declared(selector, matches.single())
    }

    private fun selectType(selector: ManifestArtifactSelector.Type): ManifestArtifactSelection.ByType {
        val candidates = when (selector.type) {
            ManifestArtifactSelector.ASYNCAPI_ALL -> artifacts.filter {
                selector.owner.matches(it.owner) &&
                    it.owner is ManifestService &&
                    it.artifact.type in ASYNCAPI_ALL_MEMBER_TYPES
            }
            else -> artifacts.filter {
                selector.owner.matches(it.owner) && it.artifact.type == selector.type
            }
        }
        val byOwner = candidates.groupBy { it.owner }
        if (byOwner.size != 1) {
            throw ManifestArtifactSelectionException(
                selector,
                "Expected artifact type '${selector.type}' on exactly one owner for " +
                    "${selector.owner.describe()}, found ${byOwner.size}${candidates.describeOwners()}",
            )
        }
        val (owner, members) = byOwner.entries.single()
        return ManifestArtifactSelection.ByType(selector, owner, members)
    }
}

val ZenWaveManifest.errors: List<ManifestDiagnostic>
    get() = diagnostics.filter { it.severity == ManifestDiagnosticSeverity.ERROR }

fun ZenWaveManifest.requireValid(): ZenWaveManifest {
    if (errors.isNotEmpty()) throw ManifestValidationException(errors)
    return this
}

class ManifestValidationException(
    val validationErrors: List<ManifestDiagnostic>,
) : IllegalArgumentException("Manifest diagnostics: ${validationErrors.joinToString("; ")}")

object ManifestValidation {
    @JvmStatic
    fun errors(manifest: ZenWaveManifest): List<ManifestDiagnostic> = manifest.errors

    @JvmStatic
    fun requireValid(manifest: ZenWaveManifest): ZenWaveManifest = manifest.requireValid()
}

private fun ManifestOwnerSelector.matches(owner: ManifestArtifactOwner): Boolean = when (this) {
    is ManifestOwnerSelector.OwnerRef -> value == owner.artifactOwnerRef
    is ManifestOwnerSelector.Repository -> value == owner.repository
}

private fun ManifestOwnerSelector.describe(): String = when (this) {
    is ManifestOwnerSelector.OwnerRef -> "owner '$value'"
    is ManifestOwnerSelector.Repository -> "repository '$value'"
}

private fun List<ResolvedManifestArtifact>.describeOwners(): String {
    val owners = map { it.ownerRef }.distinct().sorted()
    return if (owners.isEmpty()) "" else " in owner(s): ${owners.joinToString(", ")}"
}

private val ASYNCAPI_ALL_MEMBER_TYPES = setOf("asyncapi", "asyncapi-client")
private const val TYPE_SELECTOR_PREFIX = "type:"
