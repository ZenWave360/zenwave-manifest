package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.io.DocumentLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestApiConsumptionsTest {
    @Test
    fun matchesDeclaredAsyncApiAndOpenApiConsumptions() = runTest {
        val (manifest, loader) = manifestAndLoader()

        val result = ManifestApiConsumptions.build(
            manifest,
            loader,
            ApiConsumptionOptions(legacyAddressMatching = false),
        )

        assertEquals(2, result.matches.size)
        assertEquals(
            setOf("onOrderCreated" to ApiMatchKind.EXTERNAL_REF, "sendCreateOrder" to ApiMatchKind.EXTERNAL_REF),
            result.matches.map { it.consumerOperationId to it.matchKind }.toSet(),
        )
        assertTrue(result.matches.any {
            it.consumerOperationId == "onOrderCreated" &&
                it.consumerAction == AsyncApiAction.RECEIVE &&
                it.providerOperationId == "publishOrderCreated" &&
                it.providerAction == AsyncApiAction.SEND
        })
        assertFalse(result.matches.any { it.consumerOperationId == "coincidentalReceive" })
        assertEquals(1, result.apiConsumptions.size)
        assertEquals("consumer.service", result.apiConsumptions.single().edge.consumerService.id)
    }

    @Test
    fun classifiesChannelsWithExplicitPrecedenceAndKeepsBothDirections() = runTest {
        val index = AsyncApiChannelIndex.parse(PROVIDER_ASYNCAPI)

        assertEquals(AsyncApiMessageKind.COMMAND, index.channels.getValue("event-looking-channel").messageKind)
        assertEquals(AsyncApiMessageKind.EVENT, index.channels.getValue("command-looking-channel").messageKind)
        assertEquals(AsyncApiMessageKind.EVENT, index.channels.getValue("named-event-channel").messageKind)
        assertEquals(AsyncApiMessageKind.EVENT, index.channels.getValue("direction-only").messageKind)
        assertEquals(AsyncApiMessageKind.COMMAND, index.channels.getValue("conflicting-types").messageKind)
        assertTrue(index.diagnostics.any { it.code == "conflicting-message-type" })
        assertEquals(
            setOf(AsyncApiAction.SEND, AsyncApiAction.RECEIVE),
            index.channels.getValue("order-created").operations.map { it.action }.toSet(),
        )
    }

    @Test
    fun legacyAddressMatchingIsSkippedOnlyForQualifiedConsumerServices() = runTest {
        val (manifest, loader) = manifestAndLoader()

        val result = ManifestApiConsumptions.build(manifest, loader)

        assertEquals(1, result.legacyMatches.size)
        assertEquals("legacy.service", result.legacyMatches.single().consumerService.id)
        assertEquals("order-created", result.legacyMatches.single().channel.channelKey)
        assertTrue(result.legacyMatches.none { it.consumerService.id == "consumer.service" })
    }

    @Test
    fun addressMatchingRequiresUniqueChannelAndComplementaryOperation() = runTest {
        val resources = mapOf(
            "file:///workspace/provider/asyncapi.yml" to """
                asyncapi: 3.0.0
                channels:
                  one: { address: duplicate }
                  two: { address: duplicate }
                  lonely: { address: lonely }
                operations:
                  receiveOne: { action: receive, channel: { ${'$'}ref: '#/channels/one' } }
                  receiveTwo: { action: receive, channel: { ${'$'}ref: '#/channels/two' } }
                  sendLonely: { action: send, channel: { ${'$'}ref: '#/channels/lonely' } }
            """.trimIndent(),
            "file:///workspace/consumer/client.yml" to """
                asyncapi: 3.0.0
                channels:
                  ambiguous: { address: duplicate }
                  noComplement: { address: lonely }
                operations:
                  ambiguous: { action: send, channel: { ${'$'}ref: '#/channels/ambiguous' } }
                  noComplement: { action: send, channel: { ${'$'}ref: '#/channels/noComplement' } }
            """.trimIndent(),
        )
        val loader = ZenWaveManifestLoader(listOf(MapDocumentLoader(resources)), archiveEntryLoader = null)
        val manifest = loader.parse("file:///workspace/zenwave.yml", simpleManifest())

        val result = ManifestApiConsumptions.build(
            manifest,
            loader,
            ApiConsumptionOptions(legacyAddressMatching = false),
        )

        assertTrue(result.matches.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "ambiguous-channel-address" })
        assertTrue(result.diagnostics.any { it.code == "no-complementary-operation" })
    }

    @Test
    fun malformedAsyncApiProducesDiagnosticWithoutThrowing() = runTest {
        val resources = mapOf(
            "file:///workspace/provider/asyncapi.yml" to "channels: [not: valid",
            "file:///workspace/consumer/client.yml" to "asyncapi: 3.0.0",
        )
        val loader = ZenWaveManifestLoader(listOf(MapDocumentLoader(resources)), archiveEntryLoader = null)
        val manifest = loader.parse("file:///workspace/zenwave.yml", simpleManifest())

        val result = ManifestApiConsumptions.build(manifest, loader)

        assertTrue(result.matches.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "asyncapi-parse-failed" })
    }

    private suspend fun manifestAndLoader(): Pair<ZenWaveManifest, ZenWaveManifestLoader> {
        val resources = mapOf(
            "file:///workspace/provider/asyncapi.yml" to PROVIDER_ASYNCAPI,
            "file:///workspace/provider/openapi.yml" to "openapi: 3.1.0",
            "file:///workspace/consumer/asyncapi-client.yml" to CONSUMER_ASYNCAPI,
            "file:///workspace/consumer/openapi.yml" to "openapi: 3.1.0",
            "file:///workspace/legacy/asyncapi-client.yml" to LEGACY_ASYNCAPI,
        )
        val loader = ZenWaveManifestLoader(listOf(MapDocumentLoader(resources)), archiveEntryLoader = null)
        return loader.parse("file:///workspace/zenwave.yml", MANIFEST) to loader
    }

    private fun simpleManifest(): String = """
        config:
          artifactIdExpression: "${'$'}{artifact.fileNameWithoutExtension}"
          sources:
            workspace:
              basePathExpression: "${'$'}{owner.repository}"
        domains:
          test:
            services:
              provider:
                id: provider.service
                repository: provider
                consumers: [consumer#client]
                artifacts:
                  - { artifactId: provider, type: asyncapi, path: asyncapi.yml, version: 1.0.0 }
              consumer:
                id: consumer.service
                repository: consumer
                artifacts:
                  - { artifactId: client, type: asyncapi-client, path: client.yml, version: 1.0.0 }
    """.trimIndent()

    private class MapDocumentLoader(private val documents: Map<String, String>) : DocumentLoader {
        override fun canLoad(uri: String): Boolean = uri in documents
        override suspend fun load(uri: String): String = requireNotNull(documents[uri])
    }

    private companion object {
        val MANIFEST = """
            config:
              artifactIdExpression: "${'$'}{artifact.fileNameWithoutExtension}"
              sources:
                workspace:
                  basePathExpression: "${'$'}{owner.repository}"
            domains:
              test:
                services:
                  provider:
                    id: provider.service
                    repository: provider
                    consumers:
                      - consumer#asyncapi-client
                      - consumer#openapi
                    artifacts:
                      - { artifactId: provider-asyncapi, type: asyncapi, path: asyncapi.yml, version: 1.0.0 }
                      - { artifactId: provider-openapi, type: openapi, path: openapi.yml, version: 1.0.0 }
                  consumer:
                    id: consumer.service
                    repository: consumer
                    artifacts:
                      - { artifactId: asyncapi-client, type: asyncapi-client, path: asyncapi-client.yml, version: 1.0.0 }
                      - { artifactId: openapi, type: openapi, path: openapi.yml, version: 1.0.0 }
                  legacy:
                    id: legacy.service
                    repository: legacy
                    artifacts:
                      - { artifactId: legacy-client, type: asyncapi-client, path: asyncapi-client.yml, version: 1.0.0 }
        """.trimIndent()

        val PROVIDER_ASYNCAPI = """
            asyncapi: 3.0.0
            info: { title: Provider, version: 1.0.0 }
            servers:
              production: { protocol: kafka }
            components:
              messages:
                ForcedEvent: { x-message-type: event }
                ConflictingEvent: { x-message-type: event }
                ConflictingCommand: { x-message-type: command }
            channels:
              order-created: { address: test.order-created.event.v1 }
              create-order: { address: test.create-order.command.v1 }
              coincidental-channel: {}
              event-looking-channel: { x-message-type: command }
              command-looking-channel:
                messages:
                  forced: { ${'$'}ref: '#/components/messages/ForcedEvent' }
              named-event-channel: {}
              direction-only: {}
              conflicting-types:
                messages:
                  event: { ${'$'}ref: '#/components/messages/ConflictingEvent' }
                  command: { ${'$'}ref: '#/components/messages/ConflictingCommand' }
            operations:
              publishOrderCreated: { action: send, channel: { ${'$'}ref: '#/channels/order-created' } }
              handleOwnOrderCreated: { action: receive, channel: { ${'$'}ref: '#/channels/order-created' } }
              handleCreateOrder: { action: receive, channel: { ${'$'}ref: '#/channels/create-order' } }
              publishCoincidental: { action: send, channel: { ${'$'}ref: '#/channels/coincidental-channel' } }
              publishForcedCommand: { action: send, channel: { ${'$'}ref: '#/channels/event-looking-channel' } }
              handleForcedEvent: { action: receive, channel: { ${'$'}ref: '#/channels/command-looking-channel' } }
              handleNamedEvent: { action: receive, channel: { ${'$'}ref: '#/channels/named-event-channel' } }
              publishDirectionOnly: { action: send, channel: { ${'$'}ref: '#/channels/direction-only' } }
              handleConflict: { action: receive, channel: { ${'$'}ref: '#/channels/conflicting-types' } }
        """.trimIndent()

        val CONSUMER_ASYNCAPI = """
            asyncapi: 3.0.0
            channels:
              order-created: { ${'$'}ref: '../provider/asyncapi.yml#/channels/order-created' }
              create-order: { ${'$'}ref: '../provider/asyncapi.yml#/channels/create-order' }
              coincidental-channel: {}
            operations:
              onOrderCreated: { action: receive, channel: { ${'$'}ref: '#/channels/order-created' } }
              sendCreateOrder: { action: send, channel: { ${'$'}ref: '#/channels/create-order' } }
              coincidentalReceive: { action: receive, channel: { ${'$'}ref: '#/channels/coincidental-channel' } }
        """.trimIndent()

        val LEGACY_ASYNCAPI = """
            asyncapi: 3.0.0
            channels:
              order-created: { address: test.order-created.event.v1 }
            operations:
              receiveOrderCreated: { action: receive, channel: { ${'$'}ref: '#/channels/order-created' } }
        """.trimIndent()
    }
}
