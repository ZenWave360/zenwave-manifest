package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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

    private fun manifestWithAsyncApis(): String = """
        config:
          artifactIdExpression: ${'$'}{artifact.fileNameWithoutExtension}
        domains:
          orders:
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
}
