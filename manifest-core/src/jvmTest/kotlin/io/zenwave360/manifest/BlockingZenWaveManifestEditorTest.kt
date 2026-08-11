package io.zenwave360.manifest

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlockingZenWaveManifestEditorTest {

    @Test
    fun jvmReaderRecognizesSupportedUrisAndReadsFilesAndHttp() {
        val path = Files.createTempFile("manifest-reader-", ".yml")
        val reader = JvmManifestDocumentReader()
        try {
            Files.writeString(path, "file contents", StandardCharsets.UTF_8)

            assertTrue(reader.canRead(path.toUri().toString()))
            assertTrue(reader.canRead("HTTP://example.test/manifest.yml"))
            assertTrue(reader.canRead("HTTPS://example.test/manifest.yml"))
            assertTrue(!reader.canRead("ftp://example.test/manifest.yml"))
            assertTrue(!reader.canRead("://invalid"))
            assertEquals("file contents", runBlocking { reader.read(path.toUri().toString()) })

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/manifest.yml") { exchange ->
                val body = "http contents".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                assertEquals(
                    "http contents",
                    runBlocking {
                        reader.read("http://127.0.0.1:${server.address.port}/manifest.yml")
                    },
                )
            } finally {
                server.stop(0)
            }

            assertFailsWith<ManifestEditException> {
                runBlocking { reader.read("ftp://example.test/manifest.yml") }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun blockingFacadeSupportsUriAndStringOverloads() {
        val path = Files.createTempFile("manifest-editor-", ".yml")
        try {
            Files.writeString(
                path,
                """
                domains:
                  orders:
                    services:
                      service:
                        name: Orders API
                        repository: orders-api
                        artifacts:
                          - type: openapi
                            path: orders.yml
                            version: 1.0.0
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
            val editor = BlockingZenWaveManifestEditor()

            val versionResult = editor.updateArtifactVersions(
                path.toUri(),
                listOf(
                    ManifestArtifactVersionUpdate(
                        ManifestArtifactSelector.artifactInRepository("orders-api", "orders"),
                        "2.0.0",
                    ),
                ),
            )
            val scalarResult = editor.updateScalars(
                path.toUri().toString(),
                listOf(
                    ManifestScalarUpdate(
                        ManifestScalarTarget.Owner("orders/service"),
                        "name",
                        "Orders API v2",
                    ),
                ),
            )

            assertTrue(versionResult.documents.single().updatedText.contains("version: 2.0.0"))
            assertEquals("2.0.0", versionResult.manifest.services.single().artifacts.single().version)
            assertTrue(scalarResult.documents.single().updatedText.contains("name: \"Orders API v2\""))
            assertEquals("Orders API v2", scalarResult.manifest.services.single().name)
            assertEquals(ZenWaveManifestEditor::class, editor.delegate::class)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
