# ZenWave Manifest

[![Maven Central](https://img.shields.io/maven-central/v/io.zenwave360.manifest/manifest-core.svg?label=Maven%20Central&logo=apachemaven)](https://search.maven.org/artifact/io.zenwave360.manifest/manifest-core)
[![build](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/publish-maven-snapshots.yml/badge.svg?branch=develop)](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/publish-maven-snapshots.yml)
[![line coverage](https://raw.githubusercontent.com/ZenWave360/zenwave-manifest/badges/coverage.svg)](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/main.yml)
[![branch coverage](https://raw.githubusercontent.com/ZenWave360/zenwave-manifest/badges/branches.svg)](https://github.com/ZenWave360/zenwave-manifest/actions/workflows/main.yml)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](https://github.com/ZenWave360/zenwave-manifest/blob/main/LICENSE)

ZenWave Manifest is the read-only architecture contract shared by ZenWave tools. A hand-authored `zenwave-architecture.yml` describes domains, services, documentation, and artifacts; the Kotlin Multiplatform library resolves that content deterministically from workspace, Git, Apicurio Registry, generic Artifactory, or Maven sources.


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
```

Domains, subdomains, and services use a non-blank explicit `id` when present and otherwise use their YAML map key. A direct service has an empty `subdomain.id`. The primary example deliberately omits artifact `name` and `artifactId`: names are never derived from paths, while artifact IDs use their configured fallback.

For a manifest stored at `/work/architecture/manifests/zenwave-architecture.yml`, the `../../` prefix reaches `/work`; the first artifact's workspace candidate is:

```text
/work/commerce/orders-api/contracts/orders.openapi.yaml
```

The same artifact has these remote candidates:

```text
https://gitlab.com/commerce/orders-api/-/raw/v1.1.0/contracts/orders.openapi.yaml
https://registry.example.com/apis/registry/v3/groups/orders-api/artifacts/contracts%2Forders.openapi.yaml/versions/1.1.0/content
https://artifacts.example.com/artifactory/contracts/commerce/orders-api/1.1.0/contracts/orders.openapi.yaml
https://artifacts.example.com/artifactory/maven-releases/io/arcadia/orders-api/orders.openapi/1.1.0/orders.openapi-1.1.0.jar!/contracts/orders.openapi.yaml
```

The empty direct-service subdomain segment is removed without damaging `https://`.
The Artifactory URL above comes from its preset `contentUrlExpression`; declare the field only when the repository key or layout differs from `artifactory/contracts/...`.

## Runtime values

Static `config.properties` substitution runs first. It cannot override canonical values or source-local `${server}`. Runtime interpolation is literal: no functions, conditionals, nested expressions, or fallback syntax are supported.

| Variable | Value |
| --- | --- |
| `${domain.id}` | Explicit domain ID, otherwise its map key. |
| `${subdomain.id}` | Explicit subdomain ID, otherwise its map key; empty for direct services. |
| `${service.id}` | Explicit service ID, otherwise its map key. |
| `${service.repository}` | Explicit service repository name; unresolved when omitted. |
| `${domain.version}` | Explicit domain version, when declared. |
| `${subdomain.version}` | Explicit subdomain version, when declared. |
| `${service.version}` | Explicit service version, when declared. |
| `${artifact.version}` | Explicit artifact version, when declared. |
| `${artifact.path}` | Complete declared artifact path. |
| `${artifact.name}` | Explicit non-blank artifact name only; unresolved when omitted. |
| `${artifact.fileName}` | Final artifact path segment, with every extension. |
| `${artifact.fileNameWithoutExtension}` | Filename with only its final extension removed. |
| `${content.path}` | Current artifact path or selected document path. |
| `${service.docs[key]}` | Declared document path for the literal key. |
| `${groupId}` | Common resolved group coordinate. |
| `${artifactId}` | Common resolved artifact coordinate. |
| `${version}` | Inherited content version. |

Service architecture identity and source-control location are deliberately separate. Repository identity is never inferred from `service.id`:

```yaml
config:
  sources:
    workspace:
      basePathExpression: "../${service.repository}"
    git:
      provider: github
      server: https://github.com
      contentUrlExpression: "${server}/arcadia-editions/${service.repository}/raw/main/${content.path}"
domains:
  catalog:
    services:
      products:
        id: catalog.catalog-management.catalog-products
        repository: catalog-products-api
```

If `repository` is omitted, `${service.repository}` stays unresolved and any selected expression that uses it fails with the standard unresolved-runtime-variable diagnostic.

Filename behavior is exact:

| Path | `artifact.fileName` | `artifact.fileNameWithoutExtension` |
| --- | --- | --- |
| `contracts/orders.openapi.yaml` | `orders.openapi.yaml` | `orders.openapi` |
| `archive.tar.gz` | `archive.tar.gz` | `archive.tar` |
| `domain-model.zdl` | `domain-model.zdl` | `domain-model` |
| `.gitignore` | `.gitignore` | `.gitignore` |
| `README` | `README` | `README` |

Documents are selected from the service map. Keys may contain letters, numbers, `.`, `_`, and `-`:

```text
${service.docs[summary]} = docs/SUMMARY.md
${service.docs[catalog]} = docs/EVENT_CATALOG.md
${service.docs[readme]}  = docs/README.md
```

`${service.docs}` alone, an invalid lookup, or a missing key fails before I/O. Loading all docs evaluates the source URL once per entry, so `${content.path}` changes from `docs/SUMMARY.md` to `docs/EVENT_CATALOG.md` and then `docs/README.md`.

`${version}` preserves effective-version inheritance: artifact, service, subdomain, then domain. Documents begin at service. The qualified version expressions expose only their corresponding explicit declaration and do not inherit. YAML strings and numbers normalize to strings; `config.version` and artifact contents never supply a content version.

## Coordinates and explicit overrides

Common coordinates resolve as:

```text
groupId    = service.groupId, otherwise config.groupIdExpression
artifactId = artifact.artifactId, otherwise config.artifactIdExpression
```

Defaults are `${service.id}` and `${artifact.fileNameWithoutExtension}`. Coordinate expressions cannot recursively reference `${groupId}` or `${artifactId}`. Overrides are valid:

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
```

`${groupId}` and `${artifactId}` can be selected by any artifact provider expression, not only Maven.

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

## Kotlin module and verification

`manifest-core` contains common parsing, diagnostics, interpolation, and candidate construction for JVM and JavaScript. JVM also provides Maven Central JAR entry extraction; JavaScript hosts can supply an archive-entry loader.

Published library coordinates are `io.zenwave360.manifest:manifest-core`, `manifest-core-jvm`, and `manifest-core-js`.

Run:

```text
./gradlew :manifest-core:jvmTest
```

The suite validates both schemas and every complete README YAML block marked `schema-test: valid`. Release workflow details are in [RELEASING.md](RELEASING.md); this repository is licensed under the [MIT License](LICENSE).
