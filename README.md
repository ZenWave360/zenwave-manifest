# ZenWave Manifest

[![Maven Central](https://img.shields.io/maven-central/v/io.zenwave360.manifest/manifest-core.svg?label=Maven%20Central&logo=apachemaven)](https://search.maven.org/artifact/io.zenwave360.manifest/manifest-core)
[![build](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/publish-maven-snapshots.yml/badge.svg?branch=develop)](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/publish-maven-snapshots.yml)
[![line coverage](https://raw.githubusercontent.com/ZenWave360/zenwave-manifest/badges/coverage.svg)](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/main.yml)
[![branch coverage](https://raw.githubusercontent.com/ZenWave360/zenwave-manifest/badges/branches.svg)](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/main.yml)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](https://github.com/ZenWave360/zenwave-manifest/blob/main/LICENSE)

ZenWave Manifest is the architecture contract shared by ZenWave tools. A hand-authored `zenwave-architecture.yml` describes domains, services, documentation, and artifacts; the Kotlin Multiplatform library resolves that content deterministically from workspace, Git, Apicurio Registry, generic Artifactory, or Maven sources. Its source-aware editor can update existing scalar values without reserializing the surrounding YAML.


- Versioned schema: `https://schemas.zenwave360.io/zenwave-architecture/1.0/schema.json`
- Latest schema: `https://schemas.zenwave360.io/zenwave-architecture/latest/schema.json`
- Detailed authoring guide: [docs/source-resolution-guide.md](docs/source-resolution-guide.md)
- Normative source-resolution specification: [docs/source-resolution-specification.md](docs/source-resolution-specification.md)

## Complete manifest

```yaml
# schema-test: valid
# yaml-language-server: $schema=https://schemas.zenwave360.io/zenwave-architecture/1.0/schema.json

config:
  title: Arcadia architecture
  groupIdExpression: "io.arcadia.${service.id}"

  contentResolution:
    - workspace
    - git
    - apicurio
    - artifactory
    - maven

  sources:
    workspace:
      basePathExpression: "../../${domain.id}/${subdomain.id}/${service.id}"

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
```

Domains, subdomains, and services use a non-blank explicit `id` when present and otherwise use their YAML map key. A direct service has an empty `subdomain.id`. Every artifact declares its own required `version`; domain, subdomain, and service versions are optional and apply only to service documents. The primary example deliberately omits artifact `name` and `artifactId`: names are never derived from paths, while artifact IDs use their configured fallback.

For a manifest stored at `/work/architecture/manifests/zenwave-architecture.yml`, the `../../` prefix reaches `/work`; the first artifact's workspace candidate is:

```text
/work/commerce/orders-api/contracts/orders.openapi.yaml
```

The same artifact has these remote candidates:

```text
https://gitlab.com/commerce/orders-api/-/raw/v1.1.0/contracts/orders.openapi.yaml
https://registry.example.com/apis/registry/v3/groups/orders-api/artifacts/contracts%2Forders.openapi.yaml/versions/1.1.0/content
https://artifacts.example.com/artifactory/contracts/commerce/orders-api/1.1.0/contracts/orders.openapi.yaml
https://artifacts.example.com/artifactory/maven-releases/io/arcadia/orders-api/contracts%2Forders.openapi/1.1.0/contracts%2Forders.openapi-1.1.0.jar!/contracts/orders.openapi.yaml
```

The empty direct-service subdomain segment is removed without damaging `https://`.
The Artifactory URL above comes from its preset `contentUrlExpression`; declare the field only when the repository key or layout differs from `artifactory/contracts/...`.

## Runtime values

Static `config.properties` substitution runs first. It cannot override canonical values or source-local `${server}`. Runtime interpolation is literal: no functions, conditionals, nested expressions, or fallback syntax are supported.

| Variable | Value |
| --- | --- |
| `${owner.id}` | Resolved ID of the service or domain that declares the artifact. |
| `${owner.repository}` | Explicit repository of the service or domain artifact owner; unresolved when omitted. |
| `${domain.id}` | Explicit domain ID, otherwise its map key. |
| `${subdomain.id}` | Explicit subdomain ID, otherwise its map key; empty for direct services. |
| `${service.id}` | Explicit service ID, otherwise its map key. |
| `${service.repository}` | Explicit service repository name; unresolved when omitted. |
| `${domain.version}` | Explicit domain version, when declared. |
| `${subdomain.version}` | Explicit subdomain version, when declared. |
| `${service.version}` | Explicit service version, when declared. |
| `${artifact.version}` | Declared artifact version; artifact `version` is required. |
| `${artifact.path}` | Complete declared artifact path. |
| `${artifact.pathWithoutExtension}` | Complete artifact path with only its final extension removed. |
| `${artifact.name}` | Explicit non-blank artifact name only; unresolved when omitted. |
| `${artifact.fileName}` | Final artifact path segment, with every extension. |
| `${artifact.fileNameWithoutExtension}` | Filename with only its final extension removed. |
| `${content.path}` | Current artifact path or selected document path. |
| `${service.docs[key]}` | Declared document path for the literal key. |
| `${groupId}` | Common resolved group coordinate. |
| `${artifactId}` | Common resolved artifact coordinate. |
| `${version}` | Effective content version: `artifact.version` for an artifact, the inherited service, subdomain, or domain version for a service document. |

Services and domains may own artifacts. Owner identity and source-control location are deliberately separate; repository identity is never inferred from `owner.id`:

```yaml
config:
  sources:
    workspace:
      basePathExpression: "../${service.repository}"
    git:
      provider: github
      server: https://github.com
      contentUrlExpression: "${server}/arcadia-editions/${service.repository}/raw/main/${content.path}"
    maven:
      provider: github
      server: https://maven.pkg.github.com
      repository: "arcadia-editions/${service.repository}"
domains:
  catalog:
    services:
      products:
        id: catalog.catalog-management.catalog-products
        repository: catalog-products-api
```

`maven.repository` accepts the same runtime expressions and is resolved once per artifact, so services publishing to their own GitHub Packages repositories share a single Maven source declaration.

If `repository` is omitted, `${service.repository}` stays unresolved and any selected expression that uses it fails with the standard unresolved-runtime-variable diagnostic.

Filename behavior is exact:

| Path | `artifact.pathWithoutExtension` | `artifact.fileName` | `artifact.fileNameWithoutExtension` |
| --- | --- | --- | --- |
| `contracts/orders.openapi.yaml` | `contracts/orders.openapi` | `orders.openapi.yaml` | `orders.openapi` |
| `archive.tar.gz` | `archive.tar` | `archive.tar.gz` | `archive.tar` |
| `domain-model.zdl` | `domain-model` | `domain-model.zdl` | `domain-model` |
| `.gitignore` | `.gitignore` | `.gitignore` | `.gitignore` |
| `README` | `README` | `README` | `README` |

Documents are selected from the service map. Keys may contain letters, numbers, `.`, `_`, and `-`:

```text
${service.docs[summary]} = docs/SUMMARY.md
${service.docs[catalog]} = docs/EVENT_CATALOG.md
${service.docs[readme]}  = docs/README.md
```

`${service.docs}` alone, an invalid lookup, or a missing key fails before I/O. Loading all docs evaluates the source URL once per entry, so `${content.path}` changes from `docs/SUMMARY.md` to `docs/EVENT_CATALOG.md` and then `docs/README.md`.

`${version}` resolves differently for the two kinds of content. For an artifact it is exactly `artifact.version`, which is a required field and never inherits from the service, subdomain, or domain. For a service document it inherits service, then subdomain, then domain. The qualified version expressions expose only their corresponding explicit declaration and do not inherit. Blank versions count as absent. YAML strings and numbers normalize to strings; `config.version` and artifact contents never supply a content version.

## Coordinates and explicit overrides

Common coordinates resolve as:

```text
groupId    = owner.groupId, otherwise config.groupIdExpression
artifactId = artifact.artifactId, otherwise config.artifactIdExpression
```

Defaults are `${owner.id}` and `${artifact.pathWithoutExtension}`. Coordinate expressions cannot recursively reference `${groupId}` or `${artifactId}`. Overrides are valid:

```yaml
domains:
  commerce:
    services:
      orders:
        groupId: io.arcadia.orders
        artifacts:
          - name: public-orders-api
            artifactId: orders-openapi
            type: openapi
            path: contracts/orders.openapi.yaml
            version: 1.1.0
```

`${groupId}` and `${artifactId}` can be selected by any artifact provider expression, not only Maven.

## Artifact catalog and selections

`ManifestArtifactCatalog` resolves every declared artifact together with its owner and effective
coordinates. Declared artifact `type` values remain an open string namespace: `openapi`,
`asyncapi`, `zdl`, `zfl`, `grpc`, and future types require no registration with the library.

### Consumer artifact references

A service may identify the exact artifact that consumes one of its contracts with
`service.id#artifact.id`. The artifact selector is matched against the effective artifact ID first,
including `config.artifactIdExpression`; `type:<type>` explicitly selects every artifact of a type.
Plain selectors fall back to an artifact type only when exactly one artifact matches.

```yaml
# schema-test: valid
domains:
  catalog:
    services:
      inventory:
        artifacts:
          - type: asyncapi
            path: asyncapi.yml
            version: 1.0.0
        consumers:
          - orders.checkout#asyncapi-client
  orders:
    services:
      checkout:
        id: orders.checkout
        artifacts:
          - type: asyncapi-client
            path: asyncapi-client.yml
            version: 1.0.0
```

`ManifestConsumerIndex` resolves these declarations in both directions and infers compatible
provider artifacts from `ManifestConsumptionRules`. Suffix-less service references remain accepted
for backwards compatibility but do not create artifact-level consumption edges.
Callers can resolve either a unique effective artifact ID or every artifact of a declared type on
one owner:

```kotlin
val owner = ManifestOwnerSelector.Repository("orders-api")
val openApis = catalog.resolveByType(owner, "openapi").artifacts
val contract = catalog.resolveByArtifactId(owner, "contracts/orders").artifact
```

Type resolution also supports library-defined virtual behavior. `asyncapi-all` selects every
declared `asyncapi` and `asyncapi-client` artifact on exactly one service and returns the concrete
members; it is never inserted into `ZenWaveManifest.artifacts`.

```kotlin
val catalog = ManifestArtifactCatalog.resolve(manifest, loader)
val selection = catalog.resolve(
    ManifestArtifactSelector.typeInRepository(
        "orders-api",
        ManifestArtifactSelector.ASYNCAPI_ALL,
    ),
)
val bundleMembers: List<ResolvedManifestArtifact> = selection.artifacts
```

Repository selectors may generally span several owners. An artifact-ID selector must resolve one
artifact. A declared type must resolve artifacts on one owner, while `asyncapi-all` must resolve one
matching service; missing and ambiguous selections fail with `ManifestArtifactSelectionException`.

## Source-aware editing

`ZenWaveManifestEditor.updateArtifactVersions` accepts one or more artifact-ID or type selectors.
It snapshots the root and referenced documents, resolves every selection, patches only the existing
version scalar ranges, and validates the complete overlay before returning document changes.

```kotlin
val result = editor.updateArtifactVersions(
    manifestUri,
    listOf(
        ManifestArtifactVersionUpdate(
            ManifestArtifactSelector.typeInRepository(
                "orders-api",
                ManifestArtifactSelector.ASYNCAPI_ALL,
            ),
            "1.4.0",
        ),
    ),
)
```

The returned `ManifestDocumentTextUpdate` values preserve unrelated formatting, comments, scalar
quote style, and source ownership through external `$ref` documents. The editor does not persist
them; callers can compare `originalText` before writing. JVM and Java callers can use
`BlockingZenWaveManifestEditor` with the default file and HTTP(S) document reader.

`updateScalars` is the lower-level API for updating another existing owner or artifact scalar.
Structural edits such as adding or removing manifest nodes remain out of scope.

## Ordered reads

`contentResolution` defaults to `[workspace]`. Only listed sources participate; configuring a source does not activate it. Transport and not-found failures advance to the next applicable source. Apicurio and Maven are artifact-only and are skipped for service documents. A preferred source may reorder or restrict only active sources.

Supported absolute URIs bypass the list. Exhausted reads report redacted candidates in attempted order and the final error. Unknown sources, active unconfigured sources, invalid providers, missing required fields, and unresolved expressions fail deterministically before transport I/O where possible.

| Source | Artifacts | Service docs |
| --- | --- | --- |
| `workspace` | Yes | Yes |
| `git` | Yes | Yes |
| `apicurio` | Yes | No |
| `artifactory` | Yes | Yes |
| `maven` | Yes | No |

See [the detailed source guide](docs/source-resolution-guide.md) for provider defaults, custom Git recipes, encoding, workspace traversal, Artifactory layouts, and Maven Central JAR extraction.

## Loading API

`ZenWaveManifestLoader` is the canonical multiplatform API. Its loading operations are
`suspend` functions so JVM and Node.js/Kotlin callers can perform manifest, document, and
artifact I/O without blocking their server event loop:

```kotlin
val loader = ZenWaveManifestLoader()
val manifest = loader.load("file:///workspace/zenwave-architecture.yml") // http also work
val service = manifest.findService("commerce/orders")!!
val openApis = service.findArtifacts("openapi")
val text = loader.loadArtifactText(manifest, service, openApis.first())
val schema = loader.resolveArtifactReference(
    manifest,
    service,
    openApis.first(),
    "schemas/order.yaml",
)
```

Java and synchronous JVM integrations can use `BlockingZenWaveManifestLoader`. It exposes the
same I/O operations without coroutine continuations and accepts either `String` or `java.net.URI`
for root resources:

```java
var loader = new BlockingZenWaveManifestLoader();
var manifest = loader.load(URI.create("file:///workspace/zenwave-architecture.yml"));
var service = manifest.findService("commerce/orders");
var artifact = service.findArtifact("openapi");
var options = new ManifestLoadOptions()
        .withPreferredSource("workspace")
        .withFallback(true);
var text = loader.loadArtifactText(manifest, service, artifact, options);
```

For batch service documents, `loadServiceDocResults` preserves one result per configured
document, including its resolved resource or error message. `loadAvailableServiceDocs` is the
convenience variant for generators that only need successfully loaded content.
`loadServiceDocs` remains the fail-fast variant.

Candidate and reference construction remains non-blocking on the common loader. Use
`ManifestResolvedResource.referenceUri()` when a consumer needs a single URI-like reference,
including a resource stored inside an archive.

## Kotlin module and verification

`manifest-core` contains common parsing, diagnostics, interpolation, and candidate construction for JVM and JavaScript. JVM also provides Maven Central JAR entry extraction; JavaScript hosts can supply an archive-entry loader.

Published library coordinates are `io.zenwave360.manifest:manifest-core`, `manifest-core-jvm`, and `manifest-core-js`.

Run:

```text
./gradlew :manifest-core:jvmTest
```

The suite validates both schemas and every complete README YAML block marked `schema-test: valid`. Release workflow details are in [RELEASING.md](RELEASING.md); this repository is licensed under the [MIT License](LICENSE).
