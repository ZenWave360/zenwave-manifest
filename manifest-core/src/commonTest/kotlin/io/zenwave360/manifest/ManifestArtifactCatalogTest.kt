package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManifestArtifactCatalogTest {

    @Test
    fun asyncapiAllSelectsEveryAsyncapiArtifactOnOneService() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            "file:///architecture/zenwave-architecture.yml",
            manifestWithAsyncApis(),
        )

        val catalog = ManifestArtifactCatalog.resolve(manifest)
        val selection = catalog.resolve(
            ManifestArtifactSelector.typeInRepository(
                "orders-api",
                ManifestArtifactSelector.ASYNCAPI_ALL,
            ),
        )

        val virtual = assertIs<ManifestArtifactSelection.ByType>(selection)
        assertEquals("orders/service", virtual.owner.artifactOwnerRef)
        assertEquals(
            listOf("asyncapi", "asyncapi-client"),
            virtual.artifacts.map { it.artifact.type },
        )
        assertEquals(
            listOf("orders", "orders-client"),
            virtual.artifacts.map { it.artifactId },
        )
        assertEquals(
            "orders",
            catalog.resolveByArtifactId(
                ManifestOwnerSelector.Repository("orders-api"),
                "orders",
            ).artifact.artifactId,
        )
    }

    @Test
    fun asyncapiAllRequiresExactlyOneMatchingService() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            "file:///architecture/zenwave-architecture.yml",
            """
            domains:
              orders:
                services:
                  public:
                    repository: shared-api
                    artifacts:
                      - type: asyncapi
                        path: public.yml
                        version: 1.0.0
                  internal:
                    repository: shared-api
                    artifacts:
                      - type: asyncapi-client
                        path: internal.yml
                        version: 1.0.0
            """.trimIndent(),
        )

        assertFailsWith<ManifestArtifactSelectionException> {
            ManifestArtifactCatalog.resolve(manifest).resolve(
                ManifestArtifactSelector.typeInRepository(
                    "shared-api",
                    ManifestArtifactSelector.ASYNCAPI_ALL,
                ),
            )
        }
    }

    @Test
    fun parseInRepositoryReservesTheVirtualSelector() {
        assertIs<ManifestArtifactSelector.Type>(
            ManifestArtifactSelector.parseInRepository("orders-api", "asyncapi-all"),
        )
        assertIs<ManifestArtifactSelector.Type>(
            ManifestArtifactSelector.parseInRepository("orders-api", "type:openapi"),
        )
        assertIs<ManifestArtifactSelector.ArtifactId>(
            ManifestArtifactSelector.parseInRepository("orders-api", "orders"),
        )
    }

    @Test
    fun declaredArtifactTypesRemainAnOpenStringNamespace() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            "file:///architecture/zenwave-architecture.yml",
            """
            domains:
              orders:
                services:
                  service:
                    repository: orders-api
                    artifacts:
                      - type: grpc
                        path: contracts/orders.proto
                        version: 1.0.0
            """.trimIndent(),
        )

        val selection = ManifestArtifactCatalog.resolve(manifest).resolveByType(
            ManifestOwnerSelector.Repository("orders-api"),
            "grpc",
        )

        assertEquals("grpc", selection.artifacts.single().artifact.type)
    }

    @Test
    fun inventoriesExposeCoordinatesAndRejectMissingOrDuplicateArtifacts() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            "file:///architecture/zenwave-architecture.yml",
            manifestWithDuplicateArtifactIds(),
        )
        val catalog = ManifestArtifactCatalog.resolve(manifest)

        val domainInventory = catalog.owner("one").requireNotEmpty("domain one")
        val domainArtifact = domainInventory.requireArtifact("shared")
        assertEquals("one", domainArtifact.ownerRef)
        assertEquals("one", domainArtifact.ownerId)
        assertEquals("domain-repo", domainArtifact.repository)
        assertEquals("one", domainArtifact.groupId)
        assertEquals("shared", domainArtifact.artifactId)
        assertEquals("1.0.0", domainArtifact.version)
        assertTrue(domainInventory.requireUniqueArtifactIds() === domainInventory)

        val sharedRepository = catalog.repository("shared-repo")
        assertEquals(setOf("shared"), sharedRepository.duplicateArtifactIds())
        assertFailsWith<IllegalArgumentException> { sharedRepository.requireUniqueArtifactIds() }
        assertFailsWith<IllegalArgumentException> { sharedRepository.requireArtifact("shared") }
        assertFailsWith<IllegalArgumentException> { domainInventory.requireArtifact("missing") }
        assertFailsWith<IllegalArgumentException> {
            catalog.repository("missing").requireNotEmpty("missing repository")
        }

        val declared = catalog.resolve(
            ManifestArtifactSelector.artifactInOwner("one", "shared"),
        )
        assertIs<ManifestArtifactSelection.Declared>(declared)
        assertEquals("shared", declared.artifact.artifactId)
        assertEquals(
            "zfl",
            catalog.resolveByType(ManifestOwnerSelector.OwnerRef("one"), "zfl")
                .artifacts.single().artifact.type,
        )
        assertFailsWith<ManifestArtifactSelectionException> {
            catalog.resolve(ManifestArtifactSelector.artifactInRepository("shared-repo", "shared"))
        }
        assertFailsWith<IllegalArgumentException> {
            ManifestArtifactSelector.Type(ManifestOwnerSelector.OwnerRef("one"), " ")
        }
    }

    @Test
    fun validationFacadeReportsAndRejectsInvalidManifests() = runTest {
        val invalid = ZenWaveManifestLoader().parse(
            "file:///architecture/invalid.yml",
            """
            domains:
              orders:
                artifacts:
                  - type: zfl
                    path: flows/orders.zfl
            """.trimIndent(),
        )

        assertTrue(ManifestValidation.errors(invalid).isNotEmpty())
        assertEquals(invalid.errors, ManifestValidation.errors(invalid))
        val exception = assertFailsWith<ManifestValidationException> {
            ManifestValidation.requireValid(invalid)
        }
        assertEquals(invalid.errors, exception.validationErrors)
        assertFailsWith<ManifestValidationException> { ManifestArtifactCatalog.resolve(invalid) }

        val valid = ZenWaveManifestLoader().parse(
            "file:///architecture/valid.yml",
            manifestWithAsyncApis(),
        )
        assertTrue(ManifestValidation.requireValid(valid) === valid)
    }

    private fun manifestWithAsyncApis(): String = """
        config:
          artifactIdExpression: ${'$'}{artifact.fileNameWithoutExtension}
        domains:
          orders:
            repository: orders-api
            artifacts:
              - type: asyncapi
                path: domain-events.yml
                version: 1.0.0
            services:
              service:
                repository: orders-api
                artifacts:
                  - type: asyncapi
                    path: orders.yml
                    version: 1.0.0
                  - type: asyncapi-client
                    path: orders-client.yml
                    version: 1.0.0
                  - type: openapi
                    path: orders-openapi.yml
                    version: 2.0.0
    """.trimIndent()

    private fun manifestWithDuplicateArtifactIds(): String = """
        domains:
          one:
            repository: domain-repo
            artifacts:
              - artifactId: shared
                type: zfl
                path: flows/one.zfl
                version: 1.0.0
            services:
              api:
                repository: shared-repo
                artifacts:
                  - artifactId: shared
                    type: openapi
                    path: api.yml
                    version: 2.0.0
              worker:
                repository: shared-repo
                artifacts:
                  - artifactId: shared
                    type: grpc
                    path: worker.proto
                    version: 3.0.0
    """.trimIndent()
}
