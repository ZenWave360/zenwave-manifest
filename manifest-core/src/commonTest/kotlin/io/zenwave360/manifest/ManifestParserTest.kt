package io.zenwave360.manifest

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestParserTest {
    @Test
    fun resolvesHierarchyIdsFromExplicitValuesOrYamlKeys() = runTest {
        val manifest = parse(
            """
            domains:
              commerce:
                id: business
                services:
                  orders:
                    artifacts: []
                subdomains:
                  fulfillment:
                    id: delivery
                    services:
                      shipping:
                        id: shipping-api
                        artifacts: []
              support:
                subdomains:
                  care:
                    services:
                      tickets: {}
            """,
        )

        assertEquals(listOf("business", "support"), manifest.domains.map { it.id })
        assertEquals("orders", manifest.findService("orders")!!.id)
        assertEquals("", manifest.findService("orders")!!.subdomainId)
        assertEquals("delivery", manifest.findService("shipping-api")!!.subdomainId)
        assertEquals("care", manifest.findService("tickets")!!.subdomainId)
        assertTrue(manifest.diagnostics.isEmpty(), manifest.diagnostics.toString())
    }

    @Test
    fun parsesExplicitServiceRepositoryWithoutDerivingItFromServiceId() = runTest {
        val manifest = parse(
            """
            domains:
              catalog:
                services:
                  products:
                    id: catalog.catalog-management.catalog-products
                    repository: catalog-products-api
                  inventory:
                    id: catalog.catalog-management.catalog-inventory
            """,
        )

        assertEquals("catalog-products-api", manifest.findService("catalog/products")!!.repository)
        assertNull(manifest.findService("catalog/inventory")!!.repository)
        assertTrue(manifest.diagnostics.isEmpty(), manifest.diagnostics.toString())
    }

    @Test
    fun parsesReadOnlySourceShapesAndDefaults() = runTest {
        val manifest = parse(
            """
            config:
              contentResolution: [workspace, git, apicurio, artifactory, maven]
              sources:
                workspace: {}
                git:
                  provider: github
                apicurio:
                  server: https://registry.example.com
                artifactory:
                  server: https://artifacts.example.com
                maven:
                  provider: central
            domains:
              sales:
                services:
                  orders:
                    artifacts:
                      - type: openapi
                        path: contracts/orders.openapi.yaml
            """,
        )

        assertEquals("${'$'}{service.id}", manifest.config.groupIdExpression)
        assertEquals("${'$'}{artifact.fileNameWithoutExtension}", manifest.config.artifactIdExpression)
        assertEquals("${'$'}{domain.id}/${'$'}{subdomain.id}/${'$'}{service.id}", manifest.config.sources.workspace.basePathExpression)
        assertEquals("https://github.com", manifest.config.sources.git!!.server)
        assertEquals(
            "${'$'}{server}/artifactory/contracts/${'$'}{domain.id}/${'$'}{subdomain.id}/${'$'}{service.id}/${'$'}{version}/${'$'}{content.path}",
            manifest.config.sources.artifactory!!.contentUrlExpression,
        )
        assertEquals("https://repo.maven.apache.org", manifest.config.sources.maven!!.server)
        assertEquals("maven2", manifest.config.sources.maven.repository)
        assertTrue(manifest.diagnostics.isEmpty(), manifest.diagnostics.toString())
    }

    @Test
    fun explicitNameIsOptionalAndFilenameSemanticsRemoveOnlyFinalExtension() = runTest {
        val manifest = parse(
            """
            domains:
              d:
                services:
                  s:
                    artifacts:
                      - { type: openapi, path: contracts/orders.openapi.yaml }
                      - { name: archive, type: other, path: archive.tar.gz }
                      - { type: other, path: .gitignore }
                      - { type: other, path: README }
            """,
        )
        val artifacts = manifest.services.single().artifacts
        assertNull(artifacts[0].name)
        assertEquals("orders.openapi.yaml", artifacts[0].fileName)
        assertEquals("orders.openapi", artifacts[0].fileNameWithoutExtension)
        assertEquals("archive", artifacts[1].name)
        assertEquals("archive.tar", artifacts[1].fileNameWithoutExtension)
        assertEquals(".gitignore", artifacts[2].fileNameWithoutExtension)
        assertEquals("README", artifacts[3].fileNameWithoutExtension)
    }

    @Test
    fun resolvesVersionByClosestDeclarationAndNeverUsesConfigVersion() = runTest {
        val manifest = parse(
            """
            config:
              version: 99
            domains:
              sales:
                version: 1
                subdomains:
                  orders:
                    version: 2
                    services:
                      api:
                        version: 3
                        docs: { summary: docs/SUMMARY.md }
                        artifacts:
                          - { type: openapi, path: api.yml, version: 4 }
                          - { type: asyncapi, path: events.yml }
              inherited:
                version: 5
                services:
                  api:
                    artifacts: [{ type: zdl, path: model.zdl }]
              unresolved:
                services:
                  api:
                    artifacts: [{ type: zdl, path: model.zdl }]
            """,
        )

        val service = manifest.findService("sales/orders/api")!!
        assertEquals("4", service.resolvedVersion(service.artifacts[0]))
        assertEquals("3", service.resolvedVersion(service.artifacts[1]))
        assertEquals("3", service.resolvedVersion())
        assertEquals("5", manifest.findService("inherited/api")!!.resolvedVersion())
        assertNull(manifest.findService("unresolved/api")!!.resolvedVersion())
    }

    @Test
    fun staticPropertiesCannotOverrideCanonicalOrSourceLocalVariables() = runTest {
        val manifest = parse(
            """
            config:
              properties:
                host: https://git.example.com
                service.id: forged
                service.repository: forged
                content.path: forged
                server: forged
                service.docs[summary]: forged
              contentResolution: [git]
              sources:
                git:
                  provider: generic
                  contentUrlExpression: ${'$'}{host}/${'$'}{service.id}/${'$'}{content.path}
            domains:
              sales:
                services:
                  orders:
                    artifacts: [{ type: openapi, path: api.yml }]
            """,
        )

        assertEquals(
            "https://git.example.com/${'$'}{service.id}/${'$'}{content.path}",
            manifest.config.sources.git!!.contentUrlExpression,
        )
        assertEquals(setOf("host"), manifest.config.properties.keys)
        assertEquals(5, manifest.diagnostics.count { it.code == "reserved-runtime-variable" })
    }

    @Test
    fun validatesProvidersRequiredFieldsAndRecursiveCoordinatesDeterministically() = runTest {
        val manifest = parse(
            """
            config:
              groupIdExpression: ${'$'}{groupId}
              artifactIdExpression: ${'$'}{artifactId}
              contentResolution: [unknown, git, apicurio, artifactory, maven]
              sources:
                git: { provider: invalid }
                apicurio: {}
                artifactory: {}
                maven: { provider: invalid }
            domains:
              d: { services: { s: {} } }
            """,
        )
        assertEquals(
            listOf(
                "recursive-coordinate-expression",
                "recursive-coordinate-expression",
                "unknown-content-source",
                "invalid-source-provider",
                "missing-source-read-configuration",
                "missing-source-read-configuration",
                "invalid-source-provider",
                "missing-source-read-configuration",
                "missing-source-read-configuration",
            ),
            manifest.diagnostics.mapNotNull { it.code },
        )
    }

    @Test
    fun interpolationRemainsLiteralAndDoesNotTrimNames() {
        val result = ManifestVariableInterpolator.interpolate(
            "${'$'}{service.id}/${'$'}{ service.id }",
            mapOf("service.id" to "orders"),
        )
        assertEquals("orders/${'$'}{ service.id }", result.value)
        assertEquals(listOf(" service.id "), result.unresolvedVariables)
    }

    private suspend fun parse(text: String): ZenWaveManifest =
        ZenWaveManifestLoader().parse("file:///workspace/zenwave-architecture.yml", text.trimIndent())
}
