package io.zenwave360.manifest.graph

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.manifest.ZenWaveManifestLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZflGraphAnalyzerTest {
    @Test
    fun contributesStartsPoliciesHandlersSignalsAndFlowOutcomes() = runTest {
        val resources = mapOf(
            "file:///workspace/architecture/checkout.zfl" to ZFL,
            "file:///workspace/orders/model.zdl" to ZDL,
        )
        val loader = ZenWaveManifestLoader(listOf(MapDocumentLoader(resources)), archiveEntryLoader = null)
        val manifest = loader.parse("file:///workspace/zenwave.yml", MANIFEST)

        val result = ArchitectureGraphBuilder(loader).build(manifest)
        val graph = result.graph
        val flow = graph.nodes.single { it.kind == ArchitectureNodeKind.ZFL_FLOW && it.label == "CheckoutFlow" }
        assertEquals("completed=OrderCreated;stockGone=StockUnavailable", flow.attributes["endOutcomes"])

        val start = graph.nodes.single {
            it.kind == ArchitectureNodeKind.ZFL_STEP && it.label == "StartOrderCheckout" && it.attributes["role"] == "start"
        }
        assertEquals("Customer", start.attributes["actor"])
        val startEvent = graph.nodes.single { it.kind == ArchitectureNodeKind.ZFL_EVENT && it.label == "StartOrderCheckout" }
        assertEquals("true", startEvent.attributes["isStart"])
        assertTrue(graph.edges.any {
            it.kind == ArchitectureEdgeKind.TRIGGERS && it.source == start.id && it.target == startEvent.id
        })

        val startCommand = graph.nodes.single {
            it.kind == ArchitectureNodeKind.ZFL_STEP && it.label == "startOrderCheckout" && it.attributes["role"] == null
        }
        assertTrue(graph.edges.any {
            it.kind == ArchitectureEdgeKind.TRIGGERS && it.source == startEvent.id && it.target == startCommand.id
        })
        val createOrder = graph.nodes.single { it.kind == ArchitectureNodeKind.ZFL_STEP && it.label == "createOrder" }
        assertTrue(graph.edges.any {
            it.kind == ArchitectureEdgeKind.INVOKES && it.source == startCommand.id && it.target == createOrder.id &&
                it.attributes["outcome"] == "StockReserved" && it.attributes["role"] == "handler"
        })
        val unavailable = graph.nodes.single { it.kind == ArchitectureNodeKind.ZFL_EVENT && it.label == "StockUnavailable" }
        assertTrue(graph.edges.any {
            it.kind == ArchitectureEdgeKind.EMITS && it.source == startCommand.id && it.target == unavailable.id &&
                it.attributes["role"] == "handler"
        })
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
                id: sales
                repository: architecture
                artifacts:
                  - { artifactId: checkout-flow, type: zfl, path: checkout.zfl, version: 1.0.0 }
                services:
                  orders:
                    id: sales.orders
                    repository: orders
                    artifacts:
                      - { artifactId: orders-model, type: zdl, path: model.zdl, version: 1.0.0 }
        """.trimIndent()

        val ZDL = """
            @aggregate entity Order { id String required }
            service OrdersService for (Order) {
                startOrderCheckout(id)
                reserveStock(id)
                createOrder(id)
            }
        """.trimIndent()

        val ZFL = """
            systems {
                @zdl("orders/model.zdl")
                Orders {
                    service OrdersService
                }
            }
            flow CheckoutFlow {
                @actor(Customer)
                start StartOrderCheckout { orderId String }

                when StartOrderCheckout do startOrderCheckout

                do startOrderCheckout {
                    service Orders.OrdersService
                    call reserveStock
                    on StockReserved call createOrder
                    on StockUnavailable emits StockUnavailable
                    emits OrderCreated
                    emits StockUnavailable
                }
                do reserveStock {
                    service Orders.OrdersService
                    emits StockReserved
                    emits StockUnavailable
                }
                do createOrder {
                    service Orders.OrdersService
                    emits OrderCreated
                }
                end {
                    completed: OrderCreated
                    stockGone: StockUnavailable
                }
            }
        """.trimIndent()
    }
}
