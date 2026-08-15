package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestConsumersTest {

    @Test
    fun resolvesDottedServiceIdAndEffectiveArtifactIdIntoCompatibleEdge() = runTest {
        val manifest = parse(
            consumers = listOf("orders.checkout.orders-checkout#asyncapi-client"),
        )

        val index = ManifestConsumerIndex.build(manifest)

        assertTrue(index.diagnostics.isEmpty(), index.diagnostics.joinToString { it.message })
        val edge = index.edges.single()
        assertEquals("catalog/inventory/catalog-inventory", edge.providerService.serviceRef)
        assertEquals("orders/checkout/orders-checkout", edge.consumerService.serviceRef)
        assertEquals("asyncapi-client", edge.consumerArtifact.artifactId)
        assertEquals(listOf("asyncapi"), edge.providerArtifacts.map { it.artifact.type })
        assertEquals(edge, index.consumersOf(edge.providerService).single())
        assertEquals(edge, index.consumptionsBy(edge.consumerService).single())
    }

    @Test
    fun supportsTypeSelectorsAndPlainTypeFallbackWithoutChoosingAmbiguousArtifact() = runTest {
        val typeSelection = ManifestConsumerIndex.build(
            parse(consumers = listOf("orders.checkout.orders-checkout#type:asyncapi-client"), twoClients = true),
        )
        assertEquals(2, typeSelection.edges.size)

        val ambiguousFallback = ManifestConsumerIndex.build(
            parse(consumers = listOf("orders.checkout.orders-checkout#asyncapi-client"), twoClients = true),
        )
        assertTrue(ambiguousFallback.edges.isEmpty())
        assertTrue(ambiguousFallback.diagnostics.any { it.code == "ambiguous-consumer-artifact" })
    }

    @Test
    fun retainsLegacyReferencesAndReportsUnresolvedQualifiedReferences() = runTest {
        val legacy = parse(consumers = listOf("catalog-inventory"))
        val provider = legacy.findService("catalog/inventory/catalog-inventory")
        assertNotNull(provider)
        assertEquals(listOf("catalog/catalog-inventory"), provider.consumers)
        assertTrue(legacy.diagnostics.none { it.code == "unresolved-consumer-reference" })
        assertTrue(ManifestConsumerIndex.build(legacy).edges.isEmpty())

        val unresolved = parse(consumers = listOf("missing.service#asyncapi-client"))
        assertTrue(unresolved.diagnostics.any { it.code == "unresolved-consumer-reference" })
        val index = ManifestConsumerIndex.build(unresolved)
        assertTrue(index.edges.isEmpty())
        assertTrue(index.diagnostics.any { it.code == "unresolved-consumer-reference" })
    }

    @Test
    fun artifactIdWinsBeforeTypeFallback() = runTest {
        val manifest = parse(
            consumers = listOf("orders.checkout.orders-checkout#asyncapi-client"),
            explicitClientId = true,
        )

        val edge = ManifestConsumerIndex.build(manifest).edges.single()
        assertEquals("asyncapi-client", edge.consumerArtifact.artifactId)
        assertEquals("openapi", edge.consumerArtifact.artifact.type)
        assertEquals(listOf("openapi"), edge.providerArtifacts.map { it.artifact.type })
    }

    private suspend fun parse(
        consumers: List<String>,
        twoClients: Boolean = false,
        explicitClientId: Boolean = false,
    ): ZenWaveManifest {
        val declarations = consumers.joinToString("\n") { "                        - \"$it\"" }
        val clientArtifacts = (if (explicitClientId) {
            """
            - artifactId: asyncapi-client
              type: openapi
              path: client-openapi.yml
              version: 1.0.0
            - type: asyncapi-client
              path: other-client.yml
              version: 1.0.0
            """.trimIndent()
        } else {
            buildString {
                append(
                    """
                    - type: asyncapi-client
                      path: ${if (twoClients) "primary-client.yml" else "asyncapi-client.yml"}
                      version: 1.0.0
                    """.trimIndent(),
                )
                if (twoClients) append(
                    """

                    - type: asyncapi-client
                      path: secondary-client.yml
                      version: 1.0.0
                    """.trimIndent(),
                )
            }
        }).lines().joinToString("\n") { "                          $it" }
        val manifestText = """
        config:
          artifactIdExpression: ${'$'}{artifact.fileNameWithoutExtension}
        domains:
          catalog:
            subdomains:
              inventory:
                services:
                  catalog-inventory:
                    id: catalog.inventory.catalog-inventory
                    artifacts:
                      - type: asyncapi
                        path: asyncapi.yml
                        version: 1.0.0
                      - type: openapi
                        path: openapi.yml
                        version: 1.0.0
                    consumers:
        CONSUMERS
          orders:
            subdomains:
              checkout:
                services:
                  orders-checkout:
                    id: orders.checkout.orders-checkout
                    artifacts:
        CLIENT_ARTIFACTS
        """.trimIndent()
            .replace("CONSUMERS", declarations)
            .replace("CLIENT_ARTIFACTS", clientArtifacts)
        return ZenWaveManifestLoader().parse(
            "file:///architecture/zenwave-architecture.yml",
            manifestText,
        )
    }
}
