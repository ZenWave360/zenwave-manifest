package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestMavenCentralTest {
    @Test
    fun downloadsCentralJarAndReadsDeclaredEntry() = runTest {
        val root = Files.createTempDirectory("manifest-central-").toFile()
        try {
            val jar = File(root, "maven2/io/arcadia/orders-api/1.0/orders-api-1.0.jar")
            writeJar(jar, mapOf("contracts/api.yml" to "openapi: 3.1.0"))
            val loader = ZenWaveManifestLoader()
            val manifest = loader.parse(
                File(root, "zenwave.yml").toURI().toString(),
                manifestText(root, contentResolution = "[maven]"),
            )

            assertEquals(
                "openapi: 3.1.0",
                loader.loadArtifactText(manifest, manifest.services.single(), manifest.services.single().artifacts.single()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingCentralEntryAllowsWorkspaceFallback() = runTest {
        val root = Files.createTempDirectory("manifest-central-fallback-").toFile()
        try {
            val jar = File(root, "maven2/io/arcadia/orders-api/1.0/orders-api-1.0.jar")
            writeJar(jar, mapOf("other.yml" to "unused"))
            val workspaceArtifact = File(root, "commerce/orders/contracts/api.yml")
            workspaceArtifact.parentFile.mkdirs()
            workspaceArtifact.writeText("workspace fallback")
            val loader = ZenWaveManifestLoader()
            val manifest = loader.parse(
                File(root, "zenwave.yml").toURI().toString(),
                manifestText(root, contentResolution = "[maven, workspace]"),
            )

            assertEquals(
                "workspace fallback",
                loader.loadArtifactText(manifest, manifest.services.single(), manifest.services.single().artifacts.single()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interpolatedRepositoryReadsEachServiceFromItsOwnRepositoryPath() = runTest {
        val root = Files.createTempDirectory("manifest-central-repository-").toFile()
        try {
            writeJar(
                File(root, "arcadia-editions/catalog-products-api/io/arcadia/products/1.1.0/products-1.1.0.jar"),
                mapOf("contracts/api.yml" to "openapi: products"),
            )
            writeJar(
                File(root, "arcadia-editions/catalog-inventory-api/io/arcadia/inventory/2.0.0/inventory-2.0.0.jar"),
                mapOf("contracts/api.yml" to "openapi: inventory"),
            )
            val loader = ZenWaveManifestLoader()
            val manifest = loader.parse(
                File(root, "zenwave.yml").toURI().toString(),
                """
                config:
                  groupIdExpression: io.arcadia
                  artifactIdExpression: ${'$'}{service.id}
                  contentResolution: [maven]
                  sources:
                    maven:
                      provider: central
                      server: ${root.toURI().toString().trimEnd('/')}
                      repository: "arcadia-editions/${'$'}{service.repository}"
                domains:
                  catalog:
                    services:
                      products:
                        repository: catalog-products-api
                        artifacts: [{ type: openapi, path: contracts/api.yml, version: 1.1.0 }]
                      inventory:
                        repository: catalog-inventory-api
                        artifacts: [{ type: openapi, path: contracts/api.yml, version: 2.0.0 }]
                """.trimIndent(),
            )
            suspend fun text(serviceRef: String) = manifest.findService(serviceRef)!!.let { service ->
                loader.loadArtifactText(manifest, service, service.artifacts.single())
            }

            assertEquals("openapi: products", text("catalog/products"))
            assertEquals("openapi: inventory", text("catalog/inventory"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifestText(root: File, contentResolution: String): String = """
        config:
          groupIdExpression: io.arcadia
          artifactIdExpression: orders-api
          contentResolution: $contentResolution
          sources:
            maven:
              provider: central
              server: ${root.toURI().toString().trimEnd('/')}
              repository: maven2
        domains:
          commerce:
            name: Commerce
            services:
              orders:
                artifacts:
                  - type: openapi
                    path: contracts/api.yml
                    version: 1.0
    """.trimIndent()

    private fun writeJar(file: File, entries: Map<String, String>) {
        file.parentFile.mkdirs()
        JarOutputStream(file.outputStream()).use { jar ->
            entries.forEach { (path, content) ->
                jar.putNextEntry(JarEntry(path))
                jar.write(content.encodeToByteArray())
                jar.closeEntry()
            }
        }
    }
}
