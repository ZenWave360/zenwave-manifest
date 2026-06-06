# ZenWave Manifest

Shared manifest contract for ZenWave workspace tooling.

[MIT License](LICENSE)

This repository owns:

- the published JSON Schema for `zenwave-architecture.yml`
- the shared Kotlin manifest parsing and loading library
- the normalization rules used by IDE, generators, and other workspace tooling

## Published schema

- `https://schemas.zenwave360.io/zenwave-architecture/1.0/schema.json`
- `https://schemas.zenwave360.io/zenwave-architecture/latest/schema.json`

Manifest files can reference the schema directly:

```yaml
# yaml-language-server: $schema=https://schemas.zenwave360.io/zenwave-architecture/1.0/schema.json
```

## Kotlin module

- `manifest-core` - multiplatform manifest parsing, normalization, interpolation, and resource loading

Published Maven coordinates:

- `io.zenwave360.manifest:manifest-core`
- `io.zenwave360.manifest:manifest-core-jvm`
- `io.zenwave360.manifest:manifest-core-js`

Key responsibilities in `manifest-core`:

- parse `zenwave-architecture.yml`
- expand `config.properties` placeholders such as `${root}`
- resolve service docs and artifacts through source priority
- normalize consumer references
- expose a resolved service index for downstream tooling

Example manifest shape:

```yaml
config:
  sourcePriority:
    - file
    - http
    - apicurio
  naming:
    groupIdExpression: "${service.id}"
    artifactIdExpression: "${artifactName}"
  sources:
    http:
      roots:
        - https://raw.githubusercontent.com/acme
    apicurio:
      registryUrl: https://registry.acme.io/apis/registry/v2
      branch: latest
domains:
  orders:
    services:
      orders-api:
        path: /orders-api
        artifacts:
          - type: openapi
            path: src/main/resources/openapi.yaml
```

## Development

The build uses Gradle Kotlin DSL and includes the local `json-schema-ref-parser-kmp` composite build when it is present next to this repository.

## Publishing

`manifest-core` is configured for Maven Central publishing through the Sonatype Central Portal.

Important tasks:

- `./gradlew publishToMavenLocal`
- `./gradlew publishToMavenCentral`
- `./gradlew publishAndReleaseToMavenCentral`

Release workflow details and required secrets are documented in [RELEASING.md](RELEASING.md).
