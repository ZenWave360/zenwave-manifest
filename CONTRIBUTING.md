# Contributing

This repository has two deliverables:

- the published schema site served from `schemas.zenwave360.io`
- the shared Kotlin library in `manifest-core`

The workflow is intentionally close to `json-schema-ref-parser-kmp`, but simpler for now because this repository currently ships a JVM-tested Kotlin Multiplatform module and a GitHub Pages site.

## Prerequisites

- JDK 21
- Git
- Node.js is not required for the current `manifest-core` build

Use the Gradle wrapper from the repository root:

```bash
./gradlew --version
```

On Windows with Git Bash:

```bash
./gradlew.bat --version
```

## Repository layout

- `manifest-core/` - shared manifest parsing and loading library
- `zenwave-architecture/` - published JSON Schema paths
- `index.html`, `CNAME`, `.nojekyll` - GitHub Pages site assets

## Common commands

Build the library:

```bash
./gradlew :manifest-core:build
```

Run tests and verification:

```bash
./gradlew :manifest-core:check
```

Build the JAR only:

```bash
./gradlew :manifest-core:jar
```

Install to the local Maven repository:

```bash
./gradlew :manifest-core:publishToMavenLocal
```

If the sibling repository `../json-schema-ref-parser-kmp` exists, Gradle will use it as a composite build automatically. Otherwise it will resolve `json-schema-ref-parser-kmp` from Maven repositories.

## Local publishing

Publish the library to your local Maven cache:

```bash
./gradlew :manifest-core:publishToMavenLocal
```

This is the expected path while wiring `zenwave-lsp` and `zenwave-sdk` to consume the shared manifest library.

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

`manifest-core` is configured for Maven Central publishing with the same general model used in `json-schema-ref-parser-kmp`.

Standard local verification:

1. Run the verification build:

```bash
./gradlew :manifest-core:check
```

2. Publish locally if needed:

```bash
./gradlew :manifest-core:publishToMavenLocal
```

3. If you need to validate Central publication wiring without releasing, inspect the available tasks:

```bash
./gradlew :manifest-core:tasks --group publishing
```

Public release automation is defined under `.github/workflows/` and the full operator checklist lives in `RELEASING.md`.

## Contribution guidelines

- Keep the schema authoring contract and the Kotlin normalized model aligned.
- Do not add LSP-specific or generator-specific behavior into `manifest-core`.
- Prefer adding tests in `manifest-core/src/commonTest` for parsing, interpolation, normalization, and URI resolution changes.
- Keep public schema URLs stable.
- Use a new schema version path for breaking authoring changes.

## Recommended change flow

1. Update the schema, parser, or model.
2. Add or update tests in `manifest-core`.
3. Run:

```bash
./gradlew :manifest-core:check
```

4. If the schema changed, verify a real `zenwave-architecture.yml` against the published or local schema.
5. Push to `main` only when both the library and schema contract are coherent.
