package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.io.InMemoryLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestResolverTest {
    @Test
    fun serviceRepositoryResolvesWorkspaceContent() = runTest {
        val expectedUri = "file:///work/catalog-products-api/contracts/openapi.yml"
        val loader = ZenWaveManifestLoader(listOf(InMemoryLoader(expectedUri, "openapi: 3.1.0")))
        val manifest = loader.parse(
            "file:///work/manifests/zenwave.yml",
            """
            config:
              sources:
                workspace:
                  basePathExpression: "../${'$'}{service.repository}"
            domains:
              catalog:
                services:
                  products:
                    id: catalog.catalog-management.catalog-products
                    repository: catalog-products-api
                    artifacts: [{ type: openapi, path: contracts/openapi.yml }]
            """.trimIndent(),
        )
        val service = manifest.services.single()

        assertEquals("catalog-products-api", loader.artifactResolutionContext(manifest, service, service.artifacts.single()).variables()["service.repository"])
        assertEquals("openapi: 3.1.0", loader.loadArtifactText(manifest, service, service.artifacts.single()))
    }

    @Test
    fun serviceRepositoryResolvesGithubContent() = runTest {
        val expectedUri = "https://github.com/arcadia-editions/catalog-products-api/raw/main/contracts/openapi.yml"
        val loader = ZenWaveManifestLoader(listOf(InMemoryLoader(expectedUri, "openapi: 3.1.0")))
        val manifest = loader.parse(
            "file:///work/zenwave.yml",
            """
            config:
              contentResolution: [git]
              sources:
                git:
                  provider: github
                  server: https://github.com
                  contentUrlExpression: "${'$'}{server}/arcadia-editions/${'$'}{service.repository}/raw/main/${'$'}{content.path}"
            domains:
              catalog:
                services:
                  products:
                    id: catalog.catalog-management.catalog-products
                    repository: catalog-products-api
                    artifacts: [{ type: openapi, path: contracts/openapi.yml }]
            """.trimIndent(),
        )
        val service = manifest.services.single()

        assertEquals("openapi: 3.1.0", loader.loadArtifactText(manifest, service, service.artifacts.single()))
    }

    @Test
    fun selectedServiceRepositoryExpressionFailsWhenRepositoryIsMissing() = runTest {
        val loader = ZenWaveManifestLoader()
        val manifest = loader.parse(
            "file:///work/manifests/zenwave.yml",
            """
            config:
              sources:
                workspace:
                  basePathExpression: "../${'$'}{service.repository}"
            domains:
              catalog:
                services:
                  products:
                    id: catalog.catalog-management.catalog-products
                    artifacts: [{ type: openapi, path: contracts/openapi.yml }]
            """.trimIndent(),
        )
        val service = manifest.services.single()

        val error = assertFailsWith<ManifestResolutionException> {
            loader.buildArtifactCandidates(manifest, service, service.artifacts.single())
        }
        assertEquals(
            "Source 'workspace' expression '../${'$'}{service.repository}' has unresolved runtime variables: service.repository",
            error.message,
        )
    }

    @Test
    fun workspaceIsManifestRelativeAndHandlesDirectNestedEmptyAndTraversalPaths() = runTest {
        val loader = ZenWaveManifestLoader()
        val manifest = loader.parse(
            "file:///work/zenwave-architecture.yml",
            """
            domains:
              commerce:
                services:
                  orders:
                    id: orders-api
                    artifacts: [{ type: openapi, path: contracts/orders.openapi.yaml }]
                subdomains:
                  fulfillment:
                    services:
                      shipping:
                        artifacts: [{ type: asyncapi, path: contracts/shipping.asyncapi.yaml }]
            """.trimIndent(),
        )

        assertEquals(
            "file:///work/commerce/orders-api/contracts/orders.openapi.yaml",
            loader.buildArtifactCandidates(manifest, manifest.findService("orders-api")!!, manifest.findService("orders-api")!!.artifacts.single()).single().uri,
        )
        val shipping = manifest.findService("shipping")!!
        assertEquals(
            "file:///work/commerce/fulfillment/shipping/contracts/shipping.asyncapi.yaml",
            loader.buildArtifactCandidates(manifest, shipping, shipping.artifacts.single()).single().uri,
        )

        val empty = parseArtifactManifest(
            loader,
            "file:///work/manifests/zenwave.yml",
            """
            sources:
              workspace: { basePathExpression: "" }
            """,
        )
        assertEquals(
            "file:///work/manifests/openapi.yml",
            loader.buildArtifactCandidates(empty, empty.services.single(), empty.services.single().artifacts.single()).single().uri,
        )

        val traversing = parseArtifactManifest(
            loader,
            "file:///work/manifests/zenwave.yml",
            """
            sources:
              workspace:
                basePathExpression: ../../shared/${'$'}{service.id}
            """,
        )
        assertEquals(
            "file:///shared/orders/openapi.yml",
            loader.buildArtifactCandidates(traversing, traversing.services.single(), traversing.services.single().artifacts.single()).single().uri,
        )
    }

    @Test
    fun contextProvidesContentArtifactFilenameAndExplicitOnlyNameVariables() = runTest {
        val loader = ZenWaveManifestLoader()
        val manifest = parseArtifactManifest(
            loader,
            config = """
            groupIdExpression: io.example.${'$'}{service.id}
            artifactIdExpression: ${'$'}{artifact.fileNameWithoutExtension}
            """,
            artifactPath = "contracts/orders.openapi.yaml",
        )
        val service = manifest.services.single()
        val artifact = service.artifacts.single()
        val variables = loader.artifactResolutionContext(manifest, service, artifact).variables()

        assertEquals("contracts/orders.openapi.yaml", variables["artifact.path"])
        assertEquals("contracts/orders.openapi.yaml", variables["content.path"])
        assertEquals("orders.openapi.yaml", variables["artifact.fileName"])
        assertEquals("orders.openapi", variables["artifact.fileNameWithoutExtension"])
        assertNull(variables["artifact.name"])
        assertEquals("io.example.orders", variables["groupId"])
        assertEquals("orders.openapi", variables["artifactId"])

        val named = artifact.copy(name = "public-orders")
        assertEquals("public-orders", loader.artifactResolutionContext(manifest, service, named).variables()["artifact.name"])
    }

    @Test
    fun qualifiedVersionsExposeDeclarationsWhileVersionPreservesInheritance() = runTest {
        val loader = ZenWaveManifestLoader()
        val manifest = loader.parse(
            "file:///work/zenwave.yml",
            """
            config:
              contentResolution: [artifactory]
              sources:
                artifactory:
                  server: https://artifacts.example.com
                  contentUrlExpression: ${'$'}{server}/${'$'}{domain.version}/${'$'}{subdomain.version}/${'$'}{service.version}/${'$'}{artifact.version}/${'$'}{version}/${'$'}{content.path}
            domains:
              commerce:
                version: 1
                subdomains:
                  fulfillment:
                    version: 2
                    services:
                      shipping:
                        version: 3
                        artifacts:
                          - type: asyncapi
                            path: contracts/events.yml
                            version: 4
            """.trimIndent(),
        )
        val service = manifest.services.single()
        val artifact = service.artifacts.single()
        val variables = loader.artifactResolutionContext(manifest, service, artifact).variables()

        assertEquals("1", variables["domain.version"])
        assertEquals("2", variables["subdomain.version"])
        assertEquals("3", variables["service.version"])
        assertEquals("4", variables["artifact.version"])
        assertEquals("4", variables["version"])
        assertEquals(
            "https://artifacts.example.com/1/2/3/4/4/contracts/events.yml",
            loader.buildArtifactCandidates(manifest, service, artifact).single().uri,
        )

        val inheritedArtifact = artifact.copy(version = null)
        val inheritedVariables = loader.artifactResolutionContext(manifest, service, inheritedArtifact).variables()
        assertNull(inheritedVariables["artifact.version"])
        assertEquals("3", inheritedVariables["version"])
    }

    @Test
    fun docsLookupAndContentPathChangeForEverySelectedEntry() = runTest {
        val loader = ZenWaveManifestLoader()
        val manifest = loader.parse(
            "file:///work/zenwave.yml",
            """
            config:
              contentResolution: [artifactory]
              sources:
                artifactory:
                  server: https://artifacts.example.com
                  contentUrlExpression: ${'$'}{server}/contracts/${'$'}{service.docs[summary]}/${'$'}{content.path}
            domains:
              commerce:
                services:
                  orders:
                    docs:
                      summary: docs/SUMMARY.md
                      catalog: docs/EVENT_CATALOG.md
            """.trimIndent(),
        )
        val service = manifest.services.single()

        assertEquals(
            "https://artifacts.example.com/contracts/docs/SUMMARY.md/docs/SUMMARY.md",
            loader.buildDocumentCandidates(manifest, service, "summary").single().uri,
        )
        assertEquals(
            "https://artifacts.example.com/contracts/docs/SUMMARY.md/docs/EVENT_CATALOG.md",
            loader.buildDocumentCandidates(manifest, service, "catalog").single().uri,
        )

        val missing = manifest.copy(
            config = manifest.config.copy(
                sources = manifest.config.sources.copy(
                    artifactory = manifest.config.sources.artifactory!!.copy(
                        contentUrlExpression = "${'$'}{server}/${'$'}{service.docs[missing]}",
                    ),
                ),
            ),
        )
        assertFailsWith<ManifestResolutionException> {
            loader.buildDocumentCandidates(missing, service, "summary")
        }

        val invalid = missing.copy(
            config = missing.config.copy(
                sources = missing.config.sources.copy(
                    artifactory = missing.config.sources.artifactory!!.copy(
                        contentUrlExpression = "${'$'}{service.docs[bad/key]}",
                    ),
                ),
            ),
        )
        assertTrue(
            assertFailsWith<ManifestResolutionException> {
                loader.buildDocumentCandidates(invalid, service, "summary")
            }.message!!.contains("invalid document lookup"),
        )
    }

    @Test
    fun artifactoryUsesThePresetContentUrlWhenExpressionIsOmitted() = runTest {
        val loader = ZenWaveManifestLoader()
        val manifest = parseArtifactManifest(
            loader,
            config = """
            contentResolution: [artifactory]
            sources:
              artifactory:
                server: https://artifacts.example.com
            """,
            version = "1",
            artifactPath = "contracts/api.yml",
        )

        assertEquals(
            "https://artifacts.example.com/artifactory/contracts/commerce/orders/1/contracts/api.yml",
            loader.buildArtifactCandidates(
                manifest,
                manifest.services.single(),
                manifest.services.single().artifacts.single(),
            ).single().uri,
        )
    }

    @Test
    fun coordinateOverridesWinAndAreAvailableToNonMavenProviders() = runTest {
        val loader = ZenWaveManifestLoader()
        val manifest = loader.parse(
            "file:///work/zenwave.yml",
            """
            config:
              groupIdExpression: io.default.${'$'}{service.id}
              artifactIdExpression: ${'$'}{artifact.fileNameWithoutExtension}
              contentResolution: [git]
              sources:
                git:
                  provider: generic
                  contentUrlExpression: https://git.example.com/${'$'}{groupId}/${'$'}{artifactId}/${'$'}{content.path}
            domains:
              commerce:
                services:
                  orders:
                    groupId: io.arcadia.orders
                    artifacts:
                      - artifactId: orders-openapi
                        type: openapi
                        path: contracts/orders.openapi.yaml
            """.trimIndent(),
        )
        val service = manifest.services.single()
        val artifact = service.artifacts.single()
        val context = loader.artifactResolutionContext(manifest, service, artifact)

        assertEquals("io.arcadia.orders", context.groupId)
        assertEquals("orders-openapi", context.artifactId)
        assertEquals(
            "https://git.example.com/io.arcadia.orders/orders-openapi/contracts/orders.openapi.yaml",
            loader.buildArtifactCandidates(manifest, service, artifact).single().uri,
        )
    }

    @Test
    fun coordinatesAreResolvedOnlyWhenTheActiveOperationSelectsThem() = runTest {
        val loader = ZenWaveManifestLoader()
        val workspace = parseArtifactManifest(
            loader,
            config = """
            artifactIdExpression: ${'$'}{artifact.name}
            contentResolution: [workspace]
            """,
        )
        assertEquals(
            "file:///work/commerce/orders/openapi.yml",
            loader.buildArtifactCandidates(
                workspace,
                workspace.services.single(),
                workspace.services.single().artifacts.single(),
            ).single().uri,
        )

        val requiringCoordinate = workspace.copy(
            config = workspace.config.copy(
                contentResolution = listOf("git"),
                sources = workspace.config.sources.copy(
                    git = ManifestGitSource(
                        provider = "generic",
                        contentUrlExpression = "https://git.example.com/${'$'}{artifactId}/${'$'}{content.path}",
                    ),
                ),
            ),
        )
        assertFailsWith<ManifestResolutionException> {
            loader.buildArtifactCandidates(
                requiringCoordinate,
                requiringCoordinate.services.single(),
                requiringCoordinate.services.single().artifacts.single(),
            )
        }
    }

    @Test
    fun knownGitProvidersBuildImmutableTagUrlsAndNormalizeDirectServiceSegments() = runTest {
        val loader = ZenWaveManifestLoader()
        suspend fun providerUrl(provider: String, nested: Boolean = false): String {
            val hierarchy = if (nested) {
                "subdomains: { fulfillment: { services: { orders: { artifacts: [{ type: openapi, path: api.yml }] } } } }"
            } else {
                "services: { orders: { artifacts: [{ type: openapi, path: api.yml }] } }"
            }
            val manifest = loader.parse(
                "file:///work/zenwave.yml",
                """
                config:
                  contentResolution: [git]
                  sources:
                    git: { provider: $provider }
                domains:
                  commerce:
                    version: 1.2.3
                    $hierarchy
                """.trimIndent(),
            )
            return loader.buildArtifactCandidates(manifest, manifest.services.single(), manifest.services.single().artifacts.single()).single().uri
        }

        assertEquals("https://github.com/commerce/orders/raw/v1.2.3/api.yml", providerUrl("github"))
        assertEquals("https://gitlab.com/commerce/orders/-/raw/v1.2.3/api.yml", providerUrl("gitlab"))
        assertEquals("https://gitlab.com/commerce/fulfillment/orders/-/raw/v1.2.3/api.yml", providerUrl("gitlab", nested = true))
        assertEquals("https://api.bitbucket.org/2.0/repositories/commerce/orders/src/v1.2.3/api.yml", providerUrl("bitbucket"))
    }

    @Test
    fun customGitAndApicurioV3ApplyProviderSpecificEncoding() = runTest {
        val loader = ZenWaveManifestLoader()
        val git = parseArtifactManifest(
            loader,
            config = """
            contentResolution: [git]
            sources:
              git:
                provider: github
                server: https://github.enterprise.example
                contentUrlExpression: ${'$'}{server}/org/${'$'}{service.id}/raw/release-${'$'}{version}/${'$'}{content.path}
            """,
            version = "2",
            artifactPath = "contracts/my api.yml",
        )
        assertEquals(
            "https://github.enterprise.example/org/orders/raw/release-2/contracts/my%20api.yml",
            loader.buildArtifactCandidates(git, git.services.single(), git.services.single().artifacts.single()).single().uri,
        )

        val apicurio = parseArtifactManifest(
            loader,
            config = """
            contentResolution: [apicurio]
            sources:
              apicurio: { server: https://registry.example.com }
            """,
            version = "3",
            artifactPath = "contracts/orders api.yaml",
        )
        assertEquals(
            "https://registry.example.com/apis/registry/v3/groups/orders/artifacts/contracts%2Forders%20api.yaml/versions/3/content",
            loader.buildArtifactCandidates(apicurio, apicurio.services.single(), apicurio.services.single().artifacts.single()).single().uri,
        )
    }

    @Test
    fun mavenProvidersBuildArchiveEntryAndCentralJarCandidates() = runTest {
        val loader = ZenWaveManifestLoader()
        val artifactory = parseArtifactManifest(
            loader,
            config = """
            groupIdExpression: io.arcadia.${'$'}{service.id}
            contentResolution: [maven]
            sources:
              maven:
                provider: artifactory
                server: https://artifacts.example.com/artifactory
                repository: maven-releases
            """,
            version = "1.1.0",
            artifactPath = "contracts/orders.openapi.yaml",
        )
        assertEquals(
            "https://artifacts.example.com/artifactory/maven-releases/io/arcadia/orders/orders.openapi/1.1.0/orders.openapi-1.1.0.jar!/contracts/orders.openapi.yaml",
            loader.buildArtifactCandidates(artifactory, artifactory.services.single(), artifactory.services.single().artifacts.single()).single().uri,
        )

        val central = parseArtifactManifest(
            loader,
            config = """
            groupIdExpression: io.arcadia.${'$'}{service.id}
            contentResolution: [maven]
            sources:
              maven: { provider: central }
            """,
            version = "1.1.0",
            artifactPath = "contracts/orders.openapi.yaml",
        )
        val candidate = loader.buildArtifactCandidates(central, central.services.single(), central.services.single().artifacts.single()).single()
        assertEquals(
            "https://repo.maven.apache.org/maven2/io/arcadia/orders/orders.openapi/1.1.0/orders.openapi-1.1.0.jar",
            candidate.uri,
        )
        assertEquals("contracts/orders.openapi.yaml", candidate.archiveEntry)
    }

    @Test
    fun artifactOnlySourcesAreSkippedForDocsAndFallbackUsesActiveOrder() = runTest {
        val expected = "https://artifacts.example.com/contracts/orders/docs/SUMMARY.md"
        val loader = ZenWaveManifestLoader(listOf(InMemoryLoader(expected, "# Orders")))
        val manifest = loader.parse(
            "file:///missing/zenwave.yml",
            """
            config:
              contentResolution: [apicurio, maven, workspace, artifactory]
              sources:
                apicurio: { server: https://registry.example.com }
                maven: { provider: central }
                workspace: {}
                artifactory:
                  server: https://artifacts.example.com
                  contentUrlExpression: ${'$'}{server}/contracts/${'$'}{service.id}/${'$'}{content.path}
            domains:
              commerce:
                version: 1
                services:
                  orders:
                    docs: { summary: docs/SUMMARY.md }
            """.trimIndent(),
        )

        assertEquals(
            listOf("workspace", "artifactory"),
            loader.buildDocumentCandidates(manifest, manifest.services.single(), "summary").map { it.source },
        )
        assertEquals("# Orders", loader.loadServiceDocs(manifest, manifest.services.single())["summary"])
    }

    @Test
    fun directUrisBypassSourcesAndFailuresAreOrderedAndRedacted() = runTest {
        val directUri = "classpath:/templates/asyncapi.hbs"
        val directLoader = ZenWaveManifestLoader(listOf(InMemoryLoader(directUri, "template")))
        val direct = parseArtifactManifest(
            directLoader,
            config = "contentResolution: []",
            artifactPath = directUri,
        )
        assertEquals(
            directUri,
            directLoader.resolveArtifact(direct, direct.services.single(), direct.services.single().artifacts.single()).uri,
        )

        val failingLoader = ZenWaveManifestLoader(emptyList(), archiveEntryLoader = null)
        val failing = parseArtifactManifest(
            failingLoader,
            config = """
            contentResolution: [git, artifactory]
            sources:
              git:
                provider: generic
                contentUrlExpression: https://user:secret@git.example.com/${'$'}{content.path}?token=secret
              artifactory:
                server: https://artifacts.example.com
                contentUrlExpression: ${'$'}{server}/${'$'}{content.path}?access_token=secret
            """,
        )
        val error = assertFailsWith<ManifestResourceLoadException> {
            failingLoader.resolveArtifact(failing, failing.services.single(), failing.services.single().artifacts.single())
        }
        assertEquals(listOf("git", "artifactory"), error.candidates.map { it.source })
        assertFalse(error.message!!.contains("secret"))
        assertTrue(error.message!!.contains("Ordered candidates:"))
        assertTrue(error.message!!.contains("Final load error:"))
        assertNull(error.cause)
    }

    private suspend fun parseArtifactManifest(
        loader: ZenWaveManifestLoader,
        uri: String = "file:///work/zenwave.yml",
        config: String,
        version: String? = null,
        artifactPath: String = "openapi.yml",
    ): ZenWaveManifest = loader.parse(
        uri,
        listOf(
            "config:",
            config.trimIndent().prependIndent("  "),
            "domains:",
            "  commerce:",
            "    ${version?.let { "version: $it" } ?: "name: Commerce"}",
            "    services:",
            "      orders:",
            "        artifacts:",
            "          - type: openapi",
            "            path: $artifactPath",
        ).joinToString("\n"),
    )
}
