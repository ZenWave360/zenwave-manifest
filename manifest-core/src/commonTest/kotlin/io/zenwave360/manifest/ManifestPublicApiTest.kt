package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ManifestPublicApiTest {

    @Test
    fun queriesArtifactsAndBuildsReferencesWithoutPluginAdapters() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            "file:///workspace/architecture/zenwave-architecture.yml",
            """
            config:
              sources:
                workspace:
                  basePathExpression: "../services/${'$'}{service.id}"
            domains:
              commerce:
                services:
                  orders:
                    artifacts:
                      - type: openapi
                        path: contracts/orders.yaml
                        version: 1.0.0
                      - type: openapi
                        path: contracts/admin.yaml
                        version: 1.0.0
            """.trimIndent(),
        )

        val service = assertNotNull(manifest.findService("commerce/orders"))
        assertEquals("contracts/orders.yaml", service.findArtifact("openapi")?.path)
        assertEquals(2, service.findArtifacts("openapi").size)

        val artifact = assertNotNull(service.findArtifact("openapi"))
        val resource = assertNotNull(
            ZenWaveManifestLoader().resolveArtifactReference(
                manifest,
                service,
                artifact,
                "schemas/order.yaml",
            ),
        )
        assertEquals(
            "file:///workspace/services/orders/contracts/schemas/order.yaml",
            resource.referenceUri(),
        )
    }

    @Test
    fun loadOptionsSupportFluentImmutableConfiguration() {
        val options = ManifestLoadOptions()
            .withPreferredSource(" git ")
            .withFallback(false)

        assertEquals("git", options.preferredSource)
        assertEquals(false, options.allowFallback)
    }

    @Test
    fun archiveReferencesRemainInsideTheArchive() {
        val artifact = ManifestResolvedResource(
            source = ManifestSourceName.MAVEN,
            uri = "https://repo.example.com/orders.jar",
            archiveEntry = "contracts/orders.yaml",
        )

        val schema = artifact.resolveReference("../schemas/order.yaml")
        assertEquals("schemas/order.yaml", schema.archiveEntry)
        assertEquals("https://repo.example.com/orders.jar!/schemas/order.yaml", schema.referenceUri())
    }
}
