package io.zenwave360.manifest

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestSchemaTest {
    private val root = sequenceOf(
        File(System.getProperty("user.dir")),
        File(System.getProperty("user.dir")).parentFile,
    ).first { File(it, "zenwave-architecture/1.0/schema.json").isFile }
    private val v1Text = File(root, "zenwave-architecture/1.0/schema.json").readText()
    private val latestText = File(root, "zenwave-architecture/latest/schema.json").readText()
    private val schemas = schemaRegistry()

    @Test
    fun canonicalCompleteManifestValidatesAgainstVersionOneAndLatest() {
        assertValid(v1Schema(), CANONICAL_MANIFEST)
        assertValid(latestSchema(), CANONICAL_MANIFEST)
    }

    @Test
    fun versionOneAndLatestAreValidDraft202012SchemasAndDifferOnlyById() {
        val metaSchema = schemas.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"))
        assertEquals(emptyList(), metaSchema.validate(v1Text, InputFormat.JSON))
        assertEquals(emptyList(), metaSchema.validate(latestText, InputFormat.JSON))
        assertEquals(v1Text.replace(V1_ID, LATEST_ID), latestText)
    }

    @Test
    fun optionalIdsAndCoordinateOverridesAreSchemaValid() {
        assertValid(
            v1Schema(),
            """
            config:
              groupIdExpression: io.default.${'$'}{service.id}
              artifactIdExpression: ${'$'}{artifact.fileNameWithoutExtension}
            domains:
              domain-key:
                subdomains:
                  subdomain-key:
                    services:
                      service-key:
                        groupId: io.explicit
                        docs:
                          summary.v1: docs/SUMMARY.md
                        artifacts:
                          - name: public-api
                            artifactId: public-openapi
                            type: openapi
                            path: contracts/api.yml
                            version: 1.0.0
            """.trimIndent(),
        )
    }

    @Test
    fun artifactVersionIsRequiredInBothSchemas() {
        val versioned = """
            domains:
              commerce:
                services:
                  orders:
                    artifacts:
                      - type: openapi
                        path: contracts/api.yml
                        version: 1.0.0
        """.trimIndent()
        assertValid(v1Schema(), versioned)
        assertValid(latestSchema(), versioned)

        val withoutVersion = """
            domains:
              commerce:
                services:
                  orders:
                    artifacts:
                      - type: openapi
                        path: contracts/api.yml
        """.trimIndent()
        assertInvalid(v1Schema(), withoutVersion)
        assertInvalid(latestSchema(), withoutVersion)
    }

    @Test
    fun mavenRepositoryAcceptsRuntimeExpressionsInBothSchemas() {
        val interpolated = """
            config:
              contentResolution: [maven]
              sources:
                maven:
                  provider: central
                  server: https://maven.pkg.github.com
                  repository: "arcadia-editions/${'$'}{service.repository}"
            domains:
              catalog:
                services:
                  products:
                    repository: catalog-products-api
                    artifacts:
                      - type: openapi
                        path: contracts/api.yml
                        version: 1.1.0
        """.trimIndent()
        assertValid(v1Schema(), interpolated)
        assertValid(latestSchema(), interpolated)

        val blankRepository = interpolated.replace(
            "repository: \"arcadia-editions/${'$'}{service.repository}\"",
            "repository: \" \"",
        )
        assertInvalid(v1Schema(), blankRepository)
        assertInvalid(latestSchema(), blankRepository)
    }

    @Test
    fun mavenGitHubProviderRequiresRepositoryButDefaultsServer() {
        val gitHubProvider = """
            config:
              contentResolution: [maven]
              sources:
                maven:
                  provider: github
                  repository: "arcadia-editions/${'$'}{service.repository}"
            domains:
              catalog:
                services:
                  products:
                    repository: catalog-products-api
                    artifacts:
                      - type: openapi
                        path: contracts/api.yml
                        version: 1.1.0
        """.trimIndent()
        assertValid(v1Schema(), gitHubProvider)
        assertValid(latestSchema(), gitHubProvider)

        val gitHubMissingRepository = """
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
        """.trimIndent()
        assertInvalid(v1Schema(), gitHubMissingRepository)
        assertInvalid(latestSchema(), gitHubMissingRepository)
    }

    @Test
    fun serviceRepositoryIsAnOptionalNonBlankStringInBothSchemas() {
        val manifest = """
            domains:
              catalog:
                services:
                  products:
                    id: catalog.catalog-management.catalog-products
                    repository: catalog-products-api
        """.trimIndent()
        assertValid(v1Schema(), manifest)
        assertValid(latestSchema(), manifest)

        val blankRepository = """
            domains:
              catalog:
                services:
                  products:
                    repository: " "
        """.trimIndent()
        assertInvalid(v1Schema(), blankRepository)
        assertInvalid(latestSchema(), blankRepository)
    }

    @Test
    fun rejectsActiveUnconfiguredSourcesInvalidProvidersAndRequiredFields() {
        listOf(
            "contentResolution: [git]",
            "contentResolution: [apicurio]",
            "contentResolution: [artifactory]",
            "contentResolution: [maven]",
            "sources: { git: { provider: generic } }",
            "sources: { git: { provider: invalid } }",
            "sources: { apicurio: {} }",
            "sources: { artifactory: {} }",
            "sources: { maven: { provider: artifactory } }",
            "sources: { maven: { provider: invalid } }",
        ).forEach { config ->
            assertInvalid(
                v1Schema(),
                """
                config:
                  $config
                domains:
                  d: { services: { s: {} } }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsEveryRemovedDraftField() {
        val obsoleteConfigs = listOf(
            "sources: { git: { provider: github, contentBaseUrlExpressions: [https://example.com] } }",
            "sources: { git: { provider: github, publish: {} } }",
            "sources: { apicurio: { server: https://example.com, registryUrl: https://example.com } }",
            "sources: { apicurio: { server: https://example.com, groupIdExpression: x } }",
            "sources: { artifactory: { server: https://example.com, contentUrlExpression: x, publishBaseUrlExpression: x } }",
            "sources: { maven: { provider: central, providers: {} } }",
            "sources: { maven: { provider: central, groupIdExpression: x } }",
        )
        obsoleteConfigs.forEach { config ->
            assertInvalid(
                v1Schema(),
                """
                config:
                  $config
                domains:
                  d: { services: { s: {} } }
                """.trimIndent(),
            )
        }
        assertInvalid(
            v1Schema(),
            """
            domains:
              d:
                services:
                  s:
                    path: /obsolete
            """.trimIndent(),
        )
    }

    @Test
    fun everyMarkedReadmeManifestValidatesAndParses() = runTest {
        val readme = File(root, "README.md").readText()
        val examples = Regex("""```yaml\s*\n([\s\S]*?)```""")
            .findAll(readme)
            .map { it.groupValues[1] }
            .filter { "schema-test: valid" in it }
            .toList()
        assertTrue(examples.isNotEmpty(), "README must contain marked complete manifest examples")
        examples.forEach { yaml ->
            assertValid(v1Schema(), yaml)
            val manifest = ZenWaveManifestLoader().parse("file:///work/zenwave-architecture.yml", yaml)
            assertTrue(manifest.diagnostics.isEmpty(), manifest.diagnostics.toString())
        }
    }

    @Test
    fun publicationApisAndObsoleteSchemaNamesAreAbsent() {
        assertFalse(File(root, "manifest-core/src/jvmMain/kotlin/io/zenwave360/manifest/ManifestPublication.kt").exists())
        assertFalse(File(root, "manifest-core/src/jvmTest/kotlin/io/zenwave360/manifest/ManifestPublisherTest.kt").exists())
        listOf(
            "contentBaseUrlExpressions", "publishBaseUrlExpression", "registryUrl",
            "mavenRepository", "mavenCentral", "artifactName", "localRoots", "publish",
        ).forEach { removed -> assertFalse(v1Text.contains("\"$removed\""), removed) }
    }

    private fun schemaRegistry(): SchemaRegistry = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12,
    ) { builder -> builder.schemas(mapOf(V1_ID to v1Text, LATEST_ID to latestText)) }

    private fun v1Schema(): Schema = schemas.getSchema(SchemaLocation.of(V1_ID))
    private fun latestSchema(): Schema = schemas.getSchema(SchemaLocation.of(LATEST_ID))

    private fun assertValid(schema: Schema, yaml: String) {
        assertEquals(emptyList(), schema.validate(yaml, InputFormat.YAML))
    }

    private fun assertInvalid(schema: Schema, yaml: String) {
        assertTrue(schema.validate(yaml, InputFormat.YAML).isNotEmpty(), yaml)
    }

    private companion object {
        const val V1_ID = "https://schemas.zenwave360.io/zenwave-architecture/1.0/schema.json"
        const val LATEST_ID = "https://schemas.zenwave360.io/zenwave-architecture/latest/schema.json"
        val CANONICAL_MANIFEST = """
            config:
              title: Arcadia architecture
              groupIdExpression: "io.arcadia.${'$'}{service.id}"
              contentResolution: [workspace, git, apicurio, artifactory, maven]
              sources:
                workspace:
                  basePathExpression: "../../${'$'}{domain.id}/${'$'}{subdomain.id}/${'$'}{service.id}"
                git:
                  provider: gitlab
                  server: https://gitlab.com
                apicurio:
                  server: https://registry.example.com
                artifactory:
                  server: https://artifacts.example.com
                maven:
                  provider: artifactory
                  server: https://artifacts.example.com/artifactory
                  repository: maven-releases
            domains:
              commerce:
                version: 1.0.0
                services:
                  orders:
                    id: orders-api
                    docs:
                      summary: docs/SUMMARY.md
                      catalog: docs/EVENT_CATALOG.md
                      readme: docs/README.md
                    artifacts:
                      - type: openapi
                        path: contracts/orders.openapi.yaml
                        version: 1.1.0
                subdomains:
                  fulfillment:
                    version: 2.0.0
                    services:
                      shipping:
                        artifacts:
                          - type: asyncapi
                            path: contracts/shipping.asyncapi.yaml
                            version: 2.0.0
        """.trimIndent()
    }
}
