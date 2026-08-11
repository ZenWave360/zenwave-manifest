package io.zenwave360.manifest;

import java.net.URI;
import java.util.List;

/**
 * Compile-time Java API contract. Runtime behavior is covered by the Kotlin tests.
 */
class BlockingZenWaveManifestLoaderJavaTest {

    void javaCallSiteCompilesWithoutCoroutineTypes() {
        var loader = new BlockingZenWaveManifestLoader();
        var manifest = loader.parse(
                URI.create("file:///workspace/zenwave-architecture.yml"),
                """
                domains:
                  commerce:
                    services:
                      orders:
                        docs:
                          summary: README.md
                        artifacts:
                          - type: openapi
                            path: orders.yaml
                            version: 1.0.0
                """);

        var service = manifest.findService("commerce/orders");
        var defaults = new ManifestLoadOptions();
        var artifact = service.findArtifact("openapi");
        var artifacts = service.findArtifacts("openapi");
        var docResults = loader.loadServiceDocResults(manifest, service);
        var availableDocs = loader.loadAvailableServiceDocs(manifest, service);
        var reference = loader.getDelegate()
                .resolveArtifactReference(manifest, service, artifact, null, defaults)
                .referenceUri();
        var catalog = ManifestArtifactCatalog.resolve(manifest, loader.getDelegate());
        var owner = new ManifestOwnerSelector.OwnerRef("commerce/orders");
        var openApis = catalog.resolveByType(owner, "openapi");
        var orders = catalog.resolveByArtifactId(owner, "orders");
        var selector = ManifestArtifactSelector.typeInRepository(
                "orders-repository",
                ManifestArtifactSelector.ASYNCAPI_ALL
        );
        var versionUpdate = new ManifestArtifactVersionUpdate(selector, "2.0.0");
        var editor = new BlockingZenWaveManifestEditor();
        List<ManifestArtifactVersionUpdate> updates = List.of(versionUpdate);

        if (artifacts.isEmpty() || docResults.isEmpty() || availableDocs == null || reference.isBlank()
                || catalog.getArtifacts().isEmpty() || openApis.getArtifacts().isEmpty()
                || orders.getArtifact() == null || editor.getDelegate() == null || updates.isEmpty()) {
            throw new AssertionError("Unreachable compile-time API contract");
        }
    }
}
