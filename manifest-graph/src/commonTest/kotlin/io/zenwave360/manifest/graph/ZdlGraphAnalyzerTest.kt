package io.zenwave360.manifest.graph

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.manifest.ZenWaveManifestLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZdlGraphAnalyzerTest {
    @Test
    fun resolvesApiUrlTailAndLinksMethodsEventsEntitiesAndRestEvidence() = runTest {
        val resources = mapOf(
            "file:///workspace/orders/model.zdl" to ZDL,
            "file:///workspace/orders/events.yml" to ORDERS_ASYNCAPI,
            "file:///workspace/payments/contracts/api.yml" to PAYMENTS_ASYNCAPI,
        )
        val loader = ZenWaveManifestLoader(listOf(MapDocumentLoader(resources)), archiveEntryLoader = null)
        val manifest = loader.parse("file:///workspace/zenwave.yml", MANIFEST)

        val result = ArchitectureGraphBuilder(loader).build(manifest)
        val graph = result.graph

        val entity = assertNotNull(graph.nodes.singleOrNull {
            it.kind == ArchitectureNodeKind.ZDL_ENTITY && it.label == "Order"
        })
        assertEquals("true", entity.attributes["aggregate"])
        val method = graph.nodes.single { it.kind == ArchitectureNodeKind.ZDL_METHOD && it.label == "authorizePayment" }
        assertEquals("PaymentsProcessingApi", method.attributes["asyncapiApi"])
        assertEquals("payment-authorized-event-v1", method.attributes["asyncapiChannel"])
        assertEquals("POST", method.attributes["httpMethod"])
        assertEquals("/payments/authorize", method.attributes["restPath"])

        val paymentChannel = graph.nodes.single {
            it.kind == ArchitectureNodeKind.CHANNEL && it.attributes["channelKey"] == "payment-authorized-event-v1"
        }
        assertTrue(graph.edges.any {
            it.kind == ArchitectureEdgeKind.CONSUMES && it.source == method.id && it.target == paymentChannel.id
        })

        val event = graph.nodes.single { it.kind == ArchitectureNodeKind.ZDL_EVENT && it.label == "OrderPlaced" }
        val ordersChannel = graph.nodes.single {
            it.kind == ArchitectureNodeKind.CHANNEL && it.attributes["channelKey"] == "OrdersChannel"
        }
        assertEquals("orders.orders", event.attributes["asyncapiTopic"])
        assertTrue(graph.edges.any {
            it.kind == ArchitectureEdgeKind.EMITS && it.source == event.id && it.target == ordersChannel.id
        })
        assertFalse(result.diagnostics.any { it.code == "unresolved-api-reference" })
        assertFalse(result.diagnostics.any { it.code == "unresolved-channel-reference" })
    }

    private class MapDocumentLoader(private val documents: Map<String, String>) : DocumentLoader {
        override fun canLoad(uri: String): Boolean = uri in documents
        override suspend fun load(uri: String): String = requireNotNull(documents[uri])
    }

    private companion object {
        val MANIFEST = """
            config:
              sources:
                workspace:
                  basePathExpression: "${'$'}{owner.repository}"
            domains:
              sales:
                services:
                  orders:
                    id: sales.orders
                    repository: orders
                    artifacts:
                      - { artifactId: orders-model, type: zdl, path: model.zdl, version: 1.0.0 }
                      - { artifactId: orders-events, type: asyncapi, path: events.yml, version: 1.0.0 }
                  payments:
                    id: sales.payments
                    repository: payments/contracts
                    artifacts:
                      - { artifactId: payments-api, type: asyncapi, path: api.yml, version: 1.0.0 }
        """.trimIndent()

        val ZDL = """
            apis {
                asyncapi client PaymentsProcessingApi "https://github.com/acme/payments/contracts/api.yml"
            }

            @aggregate
            entity Order {
                id String required
            }

            @rest("/payments")
            service PaymentsService for (Order) {
                @asyncapi({api: PaymentsProcessingApi, channel: "payment-authorized-event-v1"})
                @post("/authorize")
                authorizePayment(id)
            }

            @asyncapi({channel: "OrdersChannel", topic: "orders.orders"})
            event OrderPlaced {
                id String required
            }
        """.trimIndent()

        val ORDERS_ASYNCAPI = """
            asyncapi: 3.0.0
            channels:
              OrdersChannel: { address: orders.orders }
            operations:
              publishOrder: { action: send, channel: { ${'$'}ref: '#/channels/OrdersChannel' } }
        """.trimIndent()

        val PAYMENTS_ASYNCAPI = """
            asyncapi: 3.0.0
            channels:
              payment-authorized-event-v1: { address: payments.authorized }
            operations:
              publishAuthorized: { action: send, channel: { ${'$'}ref: '#/channels/payment-authorized-event-v1' } }
        """.trimIndent()
    }
}
