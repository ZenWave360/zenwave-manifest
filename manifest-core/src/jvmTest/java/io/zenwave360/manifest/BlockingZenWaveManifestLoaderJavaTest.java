package io.zenwave360.manifest;

import java.net.URI;

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

        if (artifacts.isEmpty() || docResults.isEmpty() || availableDocs == null || reference.isBlank()) {
            throw new AssertionError("Unreachable compile-time API contract");
        }
    }
}
