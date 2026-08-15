# Contributing

This repository has three deliverables:

- the published schema site served from `schemas.zenwave360.io`
- the shared Kotlin library in `manifest-core`
- the architecture graph library in `manifest-graph`

The workflow is intentionally close to `json-schema-ref-parser-kmp`. Both libraries are Kotlin
Multiplatform modules with JVM and JavaScript targets.

## Building Locally

### Prerequisites

- JDK 21
- Git
- Node.js 18 or higher (for JavaScript builds and Node.js tests)

Use the Gradle wrapper from the repository root:

```bash
./gradlew --version
```

On Windows with Git Bash:

```bash
./gradlew.bat --version
```

### Repository layout

- `manifest-core/` - shared manifest parsing and loading library
- `manifest-graph/` - typed architecture graph and artifact analyzers
- `zenwave-architecture/` - published JSON Schema paths
- `index.html`, `CNAME`, `.nojekyll` - GitHub Pages site assets

### Build Commands

Run these commands from the repository root. Root-level `build` and `publishToMavenLocal` tasks
include both `manifest-core` and `manifest-graph`.

```bash
# Build both modules
./gradlew clean build

# Run JVM tests for both modules
./gradlew :manifest-core:jvmTest :manifest-graph:jvmTest

# Run JavaScript/Node.js tests for both modules
./gradlew :manifest-core:jsNodeTest :manifest-graph:jsNodeTest

# Run JVM tests and generate coverage reports for both modules
./gradlew :manifest-core:koverHtmlReport :manifest-graph:koverHtmlReport

# Publish both modules to the local Maven repository
./gradlew clean publishToMavenLocal

# Build, test, and publish both modules to the local Maven repository
./gradlew clean build publishToMavenLocal

# Build or publish one module only
./gradlew :manifest-core:build
./gradlew :manifest-graph:build
./gradlew :manifest-core:publishToMavenLocal
./gradlew :manifest-graph:publishToMavenLocal
```

If the sibling repository `../json-schema-ref-parser-kmp` exists, Gradle will use it as a composite build automatically. Otherwise it will resolve `json-schema-ref-parser-kmp` from Maven repositories.

The single root-level publish command is the expected path while wiring `zenwave-lsp` and
`zenwave-sdk` to consume the shared manifest libraries. Publishing only `manifest-core` is not
enough for consumers that use architecture graph APIs.

## Schema development

The public schema URLs are served directly from this repository, so changes under:

- `zenwave-architecture/1.0/schema.json`
- `zenwave-architecture/latest/schema.json`

are released by pushing to the branch used by GitHub Pages.

Typical schema validation workflow:

1. Edit the schema JSON.
2. Point a real manifest file to the versioned schema URL.
3. Reload the YAML editor or language server.
4. Confirm both schema validation and Kotlin tests still make sense.

## Releasing the schema site

GitHub Pages is currently configured to deploy from `main`.

That means the schema release flow is:

1. Update the schema or site files.
2. Commit and push to `main`.
3. Wait for GitHub Pages to publish.
4. Verify the public URL:

```text
https://schemas.zenwave360.io/zenwave-architecture/1.0/schema.json
```

If the change is not backward compatible, publish a new versioned path instead of mutating an existing contract in place.

Recommended pattern:

- `zenwave-architecture/1.0/schema.json` - frozen version
- `zenwave-architecture/1.1/schema.json` - next compatible version
- `zenwave-architecture/latest/schema.json` - optional moving alias

## Releasing the library

`manifest-core` and `manifest-graph` are configured for Maven Central publishing with the same
general model used in `json-schema-ref-parser-kmp`.

Standard local verification:

1. Run the verification build:

```bash
./gradlew clean build
```

2. Publish locally if needed:

```bash
./gradlew clean publishToMavenLocal
```

3. If you need to validate Central publication wiring without releasing, inspect the available tasks:

```bash
./gradlew tasks --group publishing
```

Public release automation is defined under `.github/workflows/` and the full operator checklist lives in `RELEASING.md`.

## Contribution guidelines

- Keep the schema authoring contract and the Kotlin normalized model aligned.
- Do not add LSP-specific or generator-specific behavior into `manifest-core`.
- Prefer adding tests in `manifest-core/src/commonTest` for parsing, interpolation, normalization, and URI resolution changes.
- Prefer adding tests in `manifest-graph/src/commonTest` for graph identities, bindings, traversal, and artifact analyzer changes.
- Keep public schema URLs stable.
- Use a new schema version path for breaking authoring changes.

## Recommended change flow

1. Update the schema, parser, or model.
2. Add or update tests in `manifest-core` and/or `manifest-graph`.
3. Run:

```bash
./gradlew clean build
```

4. If the schema changed, verify a real `zenwave-architecture.yml` against the published or local schema.
5. Push to `main` only when both the library and schema contract are coherent.
