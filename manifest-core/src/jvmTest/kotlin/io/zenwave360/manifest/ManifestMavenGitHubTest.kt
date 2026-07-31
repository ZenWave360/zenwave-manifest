package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestMavenGitHubTest {
    @Test
    fun downloadsGitHubJarAndReadsDeclaredEntry() = runTest {
        val root = Files.createTempDirectory("manifest-github-").toFile()
        try {
            val jar = File(root, "arcadia-editions/catalog-products-api/io/arcadia/products/1.0/products-1.0.jar")
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
    fun appliesGitHubServerDefault() = runTest {
        val root = Files.createTempDirectory("manifest-github-defaults-").toFile()
        try {
            val jar = File(root, "arcadia-editions/catalog-products-api/io/arcadia/products/1.1.0/products-1.1.0.jar")
            writeJar(jar, mapOf("contracts/api.yml" to "github server default"))
            val loader = ZenWaveManifestLoader()
            val manifest = loader.parse(
                File(root, "zenwave.yml").toURI().toString(),
                """
                config:
                  groupIdExpression: io.arcadia
                  artifactIdExpression: products
                  contentResolution: [maven]
                  sources:
                    maven:
                      provider: github
                      server: ${root.toURI().toString().trimEnd('/')}
                      repository: "arcadia-editions/catalog-products-api"
                domains:
                  catalog:
                    services:
                      products:
                        artifacts:
                          - type: openapi
                            path: contracts/api.yml
                            version: 1.1.0
                """.trimIndent(),
            )

            val candidates = loader.buildArtifactCandidates(
                manifest,
                manifest.services.single(),
                manifest.services.single().artifacts.single(),
            )
            assertEquals(1, candidates.size)
            val candidate = candidates.single()
            assertTrue(candidate.uri.startsWith(root.toURI().toString()), candidate.uri)
            assertEquals("contracts/api.yml", candidate.archiveEntry)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingGitHubRepositoryProducesDiagnostic() = runTest {
        val root = Files.createTempDirectory("manifest-github-missing-repo-").toFile()
        try {
            val loader = ZenWaveManifestLoader()
            val manifest = loader.parse(
                File(root, "zenwave.yml").toURI().toString(),
                """
                config:
                  contentResolution: [maven]
                  sources:
                    maven:
                      provider: github
                domains:
                  catalog:
                    services:
                      products:
                        artifacts:
                          - type: openapi
                            path: contracts/api.yml
                            version: 1.1.0
                """.trimIndent(),
            )

            val repositoryDiagnostics = manifest.diagnostics.filter { "repository" in it.message }
            assertEquals(1, repositoryDiagnostics.size, manifest.diagnostics.toString())
            assertTrue(repositoryDiagnostics.any { "maven" in it.message })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interpolatedRepositoryReadsFromEachServiceRepository() = runTest {
        val root = Files.createTempDirectory("manifest-github-repository-").toFile()
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
                      provider: github
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

    @Test
    fun missingGitHubEntryAllowsWorkspaceFallback() = runTest {
        val root = Files.createTempDirectory("manifest-github-fallback-").toFile()
        try {
            val jar = File(root, "arcadia-editions/catalog-products-api/io/arcadia/products/1.0/products-1.0.jar")
            writeJar(jar, mapOf("other.yml" to "unused"))
            val workspaceArtifact = File(root, "commerce/products/contracts/api.yml")
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
    fun gitHubCandidateHasArchiveEntryAndNoExclamationMark() = runTest {
        val root = Files.createTempDirectory("manifest-github-entry-").toFile()
        try {
            val jar = File(root, "arcadia-editions/catalog-products-api/io/arcadia/products/1.0/products-1.0.jar")
            writeJar(jar, mapOf("contracts/api.yml" to "candidate test"))
            val loader = ZenWaveManifestLoader()
            val manifest = loader.parse(
                File(root, "zenwave.yml").toURI().toString(),
                manifestText(root, contentResolution = "[maven]"),
            )

            val candidates = loader.buildArtifactCandidates(
                manifest,
                manifest.services.single(),
                manifest.services.single().artifacts.single(),
            )
            assertEquals(1, candidates.size)
            val candidate = candidates.single()
            assertEquals("contracts/api.yml", candidate.archiveEntry)
            assertTrue(!candidate.uri.contains("!/"), "GitHub candidate must not have !/ in URL: ${candidate.uri}")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifestText(root: File, contentResolution: String): String = """
        config:
          groupIdExpression: io.arcadia
          artifactIdExpression: products
          contentResolution: $contentResolution
          sources:
            maven:
              provider: github
              server: ${root.toURI().toString().trimEnd('/')}
              repository: "arcadia-editions/catalog-products-api"
        domains:
          commerce:
            name: Commerce
            services:
              products:
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
