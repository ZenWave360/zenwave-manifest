package io.zenwave360.manifest.graph

import io.zenwave360.manifest.ZenWaveManifestLoader
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcadiaArchitectureGraphSmokeTest {
    @Test
    fun buildsLocalArcadiaArchitectureGraphWhenConfigured() = runTest {
        val configuredPath = System.getProperty("arcadia.architecture")
            ?.takeIf(String::isNotBlank)
            ?: return@runTest
        val loader = ZenWaveManifestLoader()
        val manifest = loader.load(Path.of(configuredPath).toUri().toString())

        val result = ArchitectureGraphBuilder(loader).build(manifest)
        val graph = result.graph
        val flow = assertNotNull(graph.nodes.singleOrNull {
            it.kind == ArchitectureNodeKind.ZFL_FLOW && it.label == "PlaceOrderFlow"
        })
        val declaredSystems = graph.edges(ArchitectureEdgeKind.DECLARES_DOMAIN)
            .filter { edge -> graph.node(edge.source)?.kind == ArchitectureNodeKind.ZFL_SYSTEM }
        assertEquals(
            5,
            declaredSystems.size,
            result.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" },
        )
        assertTrue(graph.outgoing(flow.id).isNotEmpty())
        assertTrue(graph.edges(ArchitectureEdgeKind.INVOKES).any { edge ->
            graph.node(edge.source)?.kind == ArchitectureNodeKind.ZFL_STEP &&
                graph.node(edge.target)?.kind == ArchitectureNodeKind.ZDL_METHOD
        })
        assertTrue(graph.nodes.any { it.kind == ArchitectureNodeKind.CHANNEL })

        val authorizePayment = assertNotNull(graph.nodes.singleOrNull {
            it.kind == ArchitectureNodeKind.ZDL_METHOD &&
                it.attributes["asyncapiApi"] == "PaymentsProcessingApi" &&
                it.attributes["asyncapiChannel"] == "payment-authorized-event-v1"
        })
        assertTrue(graph.outgoing(authorizePayment.id).any { edge ->
            edge.kind == ArchitectureEdgeKind.CONSUMES &&
                graph.node(edge.target)?.kind == ArchitectureNodeKind.CHANNEL &&
                graph.node(edge.target)?.attributes?.get("channelKey") == "payment-authorized-event-v1"
        }, buildString {
            appendLine("Outgoing: ${graph.outgoing(authorizePayment.id)}")
            append(result.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" })
        })
        assertFalse(result.diagnostics.any { it.code == "dangling-graph-edge" })
    }
}
