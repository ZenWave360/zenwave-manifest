package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZenWaveManifestEditorTest {

    @Test
    fun updatesTheCompleteAsyncapiAllSelectionTogether() = runTest {
        val uri = "file:///architecture/zenwave-architecture.yml"
        val reader = MapDocumentReader(
            mapOf(
                uri to """
                    domains:
                      orders:
                        services:
                          service:
                            repository: orders-api
                            artifacts:
                              - type: asyncapi
                                path: orders.yml
                                version: '1.0.0' # provider contract
                              - type: asyncapi-client
                                path: orders-client.yml
                                version: 1.0.0
                              - type: openapi
                                path: orders-openapi.yml
                                version: 2.0.0
                """.trimIndent(),
            ),
        )

        val result = ZenWaveManifestEditor(reader).updateArtifactVersions(
            uri,
            listOf(
                ManifestArtifactVersionUpdate(
                    ManifestArtifactSelector.typeInRepository(
                        "orders-api",
                        ManifestArtifactSelector.ASYNCAPI_ALL,
                    ),
                    "1.1.0",
                ),
            ),
        )

        assertEquals(2, result.changes.size)
        assertTrue(result.changes.all { it.changed })
        assertEquals(1, result.documents.size)
        val updated = result.documents.single().updatedText
        assertTrue(updated.contains("version: '1.1.0' # provider contract"))
        assertEquals(1, Regex("version: 1.1.0").findAll(updated).count())
        assertTrue(updated.contains("version: 2.0.0"))
        assertEquals(
            listOf("1.1.0", "1.1.0", "2.0.0"),
            result.manifest.services.single().artifacts.map { it.version },
        )
    }

    @Test
    fun updatesDomainOwnedArtifactsThroughTheSameTypedApi() = runTest {
        val uri = "file:///architecture/zenwave-architecture.yml"
        val reader = MapDocumentReader(
            mapOf(
                uri to """
                    domains:
                      architecture:
                        repository: architecture-repository
                        artifacts:
                          - type: zfl
                            path: flows/place-order.zfl
                            version: 1.0.0
                """.trimIndent(),
            ),
        )

        val result = ZenWaveManifestEditor(reader).updateArtifactVersions(
            uri,
            listOf(
                ManifestArtifactVersionUpdate(
                    ManifestArtifactSelector.artifactInRepository(
                        "architecture-repository",
                        "flows/place-order",
                    ),
                    "1.1.0",
                ),
            ),
        )

        assertEquals("1.1.0", result.manifest.domains.single().artifacts.single().version)
        assertTrue(result.documents.single().updatedText.contains("version: 1.1.0"))
    }

    @Test
    fun updatesAllArtifactsOfAnOpenDeclaredTypeOnOneOwner() = runTest {
        val uri = "file:///architecture/zenwave-architecture.yml"
        val reader = MapDocumentReader(
            mapOf(
                uri to """
                    domains:
                      orders:
                        services:
                          service:
                            repository: orders-api
                            artifacts:
                              - type: openapi
                                path: public/orders.yml
                                version: 1.0.0
                              - type: openapi
                                path: internal/orders.yml
                                version: 1.0.0
                              - type: grpc
                                path: orders.proto
                                version: 2.0.0
                """.trimIndent(),
            ),
        )

        val result = ZenWaveManifestEditor(reader).updateArtifactVersions(
            uri,
            listOf(
                ManifestArtifactVersionUpdate(
                    ManifestArtifactSelector.typeInRepository("orders-api", "openapi"),
                    "1.1.0",
                ),
            ),
        )

        assertEquals(2, result.changes.size)
        assertEquals(
            listOf("1.1.0", "1.1.0", "2.0.0"),
            result.manifest.services.single().artifacts.map { it.version },
        )
    }

    @Test
    fun editsTheExternalDocumentThatOwnsTheSelectedArtifact() = runTest {
        val rootUri = "file:///architecture/zenwave-architecture.yml"
        val domainUri = "file:///architecture/orders.yml"
        val reader = MapDocumentReader(
            mapOf(
                rootUri to """
                    domains:
                      orders:
                        ${'$'}ref: orders.yml
                """.trimIndent(),
                domainUri to """
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
                """.trimIndent(),
            ),
        )

        val result = ZenWaveManifestEditor(reader).updateArtifactVersions(
            rootUri,
            listOf(
                ManifestArtifactVersionUpdate(
                    ManifestArtifactSelector.typeInRepository(
                        "orders-api",
                        ManifestArtifactSelector.ASYNCAPI_ALL,
                    ),
                    "1.2.0",
                ),
            ),
        )

        assertEquals(domainUri, result.documents.single().uri)
        assertEquals(2, Regex("version: 1.2.0").findAll(result.documents.single().updatedText).count())
    }

    @Test
    fun resolvesEverySelectorBeforeReturningAnyDocumentUpdates() = runTest {
        val uri = "file:///architecture/zenwave-architecture.yml"
        val original = """
            domains:
              orders:
                services:
                  service:
                    repository: orders-api
                    artifacts:
                      - type: asyncapi
                        path: orders.yml
                        version: 1.0.0
        """.trimIndent()
        val reader = MapDocumentReader(mapOf(uri to original))

        assertFailsWith<ManifestArtifactSelectionException> {
            ZenWaveManifestEditor(reader).updateArtifactVersions(
                uri,
                listOf(
                    ManifestArtifactVersionUpdate(
                        ManifestArtifactSelector.typeInRepository(
                            "orders-api",
                            ManifestArtifactSelector.ASYNCAPI_ALL,
                        ),
                        "1.1.0",
                    ),
                    ManifestArtifactVersionUpdate(
                        ManifestArtifactSelector.artifactInRepository("orders-api", "missing"),
                        "1.1.0",
                    ),
                ),
            )
        }
        assertEquals(original, reader.read(uri))
    }

    private class MapDocumentReader(
        private val documents: Map<String, String>,
    ) : ManifestDocumentReader {
        override fun canRead(uri: String): Boolean = uri in documents

        override suspend fun read(uri: String): String =
            documents[uri] ?: error("missing document: $uri")
    }
}
