package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.io.InMemoryLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZenWaveManifestLoaderTest {

    @Test
    fun parsesDomainsWithServicePathsAndArtifactsIntoNormalizedServices() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                config:
                  sourcePriority:
                    - file
                    - http
                    - apicurio
                domains:
                  orders:
                    id: orders
                    services:
                      orders-api:
                        id: orders.orders-api
                        path: /orders-api
                        artifacts:
                          - type: zdl
                            path: domain-model.zdl
                  fulfillment:
                    id: fulfillment
                    subdomains:
                      shipping:
                        id: fulfillment.shipping
                        services:
                          shipping-api:
                            id: fulfillment.shipping.shipping-api
                            path: /shipping-api
                            artifacts:
                              - type: asyncapi
                                path: asyncapi.yml
            """.trimIndent()
        )

        assertEquals(listOf("file", "http", "apicurio"), manifest.config.sourcePriority)
        assertEquals(2, manifest.services.size)
        val directService = manifest.services.first { it.serviceKey == "orders-api" }
        assertEquals("orders", directService.domainKey)
        assertEquals(null, directService.subdomainKey)
        assertEquals("orders/orders-api", directService.serviceRef)
        assertEquals("/orders-api", directService.path)
        assertEquals("domain-model", directService.artifacts.single().name)

        val nestedService = manifest.services.first { it.serviceKey == "shipping-api" }
        assertEquals("fulfillment", nestedService.domainKey)
        assertEquals("shipping", nestedService.subdomainKey)
        assertEquals("fulfillment/shipping/shipping-api", nestedService.serviceRef)
    }

    @Test
    fun resolvesArtifactsThroughPreferredSourceHierarchy() = runTest {
        val manifestUri = "file:///workspace/my-docs/master.yml"
        val loader = ZenWaveManifestLoader(
            documentLoaders = listOf(
                InMemoryLoader(
                    "file:///workspace/orders-api/openapi.yaml",
                    "openapi: 3.0.0\ninfo:\n  title: Orders\n"
                ),
                InMemoryLoader(
                    "https://raw.githubusercontent.com/acme/orders-api/openapi.yaml",
                    "remote-http"
                ),
                InMemoryLoader(
                    "https://registry.acme.io/apis/registry/v2/groups/orders.orders-api/artifacts/openapi/branches/latest",
                    "remote-apicurio"
                )
            )
        )
        val manifest = loader.parse(
            uri = manifestUri,
            text = """
                config:
                  sourcePriority:
                    - file
                    - http
                    - apicurio
                  naming:
                    groupIdExpression: "${'$'}{service.id}"
                    artifactIdExpression: "${'$'}{artifactName}"
                  sources:
                    http:
                      roots:
                        - https://raw.githubusercontent.com/acme
                    apicurio:
                      registryUrl: https://registry.acme.io/apis/registry/v2
                domains:
                  orders:
                    services:
                      orders-api:
                        path: /orders-api
                        artifacts:
                          - type: openapi
                            path: openapi.yaml
            """.trimIndent()
        )

        val service = manifest.services.single()
        val artifact = service.artifacts.single()

        val fileResolved = loader.resolveArtifact(
            manifest,
            service,
            artifact,
            ManifestLoadOptions(localRoots = listOf("file:///workspace"))
        )
        assertEquals("file", fileResolved.source)
        assertEquals("file:///workspace/orders-api/openapi.yaml", fileResolved.uri)

        val apicurioResolved = loader.resolveArtifact(
            manifest,
            service,
            artifact,
            ManifestLoadOptions(preferredSource = "apicurio", allowFallback = false)
        )
        assertEquals("apicurio", apicurioResolved.source)
        assertEquals(
            "https://registry.acme.io/apis/registry/v2/groups/orders.orders-api/artifacts/openapi/branches/latest",
            apicurioResolved.uri
        )
    }

    @Test
    fun loadsServiceDocsAndArtifactsWithConfiguredSources() = runTest {
        val manifestUri = "file:///workspace/docs/zenwave-architecture.yml"
        val loader = ZenWaveManifestLoader(
            documentLoaders = listOf(
                InMemoryLoader(
                    "file:///workspace/orders-api/docs/SUMMARY.md",
                    "# Orders API"
                ),
                InMemoryLoader(
                    "file:///workspace/orders-api/src/main/resources/openapi.yaml",
                    "openapi: 3.0.0"
                )
            )
        )
        val manifest = loader.parse(
            uri = manifestUri,
            text = """
                domains:
                  orders:
                    services:
                      orders-api:
                        path: /orders-api
                        docs:
                          summary: docs/SUMMARY.md
                        artifacts:
                          - type: openapi
                            path: src/main/resources/openapi.yaml
            """.trimIndent()
        )

        val service = manifest.services.single()
        val docs = loader.loadServiceDocs(
            manifest,
            service,
            ManifestLoadOptions(localRoots = listOf("file:///workspace"))
        )
        val artifacts = loader.loadServiceArtifacts(
            manifest,
            service,
            ManifestLoadOptions(localRoots = listOf("file:///workspace"))
        )

        assertEquals("# Orders API", docs["summary"])
        assertEquals("openapi: 3.0.0", artifacts[service.artifacts.single()])
    }

    @Test
    fun supportsDirectClasspathArtifactsWithoutSourceResolution() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                domains:
                  orders:
                    services:
                      orders-api:
                        path: /orders-api
                        artifacts:
                          - type: template
                            path: classpath:/templates/asyncapi.hbs
            """.trimIndent()
        )

        val artifact = manifest.services.single().artifacts.single()
        assertEquals("classpath:/templates/asyncapi.hbs", artifact.pathExpression)
    }

    @Test
    fun normalizesConsumerReferencesInAllSupportedForms() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                domains:
                  orders:
                    services:
                      orders-api:
                        path: /orders-api
                        consumers:
                          - service: fulfillment/shipping/shipping-api
                          - service: notifications-api
                          - ${'$'}ref: "#/domains/payments/subdomains/checkout/services/payments-api"
                          - "#/domains/inventory/services/inventory-api"
            """.trimIndent()
        )

        val consumers = manifest.services.single().consumers
        assertEquals(
            listOf(
                "fulfillment/shipping/shipping-api",
                "orders/notifications-api",
                "payments/checkout/payments-api",
                "inventory/inventory-api"
            ),
            consumers
        )
    }

    @Test
    fun recordsUnresolvedVariablesAndDuplicateArtifactNamesAsDiagnostics() = runTest {
        val manifest = ZenWaveManifestLoader().parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                config:
                  properties:
                    templateRoot: classpath:/templates
                domains:
                  orders:
                    services:
                      orders-api:
                        path: "${'$'}{missingRoot}/orders-api"
                        artifacts:
                          - type: template
                            path: "${'$'}{templateRoot}/openapi.yml"
                          - type: template
                            path: "${'$'}{templateRoot}/openapi.yaml"
            """.trimIndent()
        )

        assertTrue(manifest.diagnostics.any { it.message.contains("Unresolved variable") })
        assertTrue(manifest.diagnostics.any { it.code == "duplicate-artifact-name" })
    }
}
