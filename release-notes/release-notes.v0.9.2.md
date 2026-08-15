## What's Changed

Patch release **0.9.2** expands ZenWave Manifest from deterministic content loading into a source-aware architecture model with artifact selection, safe editing, API-consumption analysis, and a typed semantic graph.

## What's New

### Artifact Coordinates and Selection

- Resolves and exposes effective Maven-style `groupId` and `artifactId` coordinates for every manifest artifact, including owner-level and artifact-level overrides.
- Makes `${groupId}` and `${artifactId}` available to all artifact source expressions, not only Maven providers.
- Adds `ManifestArtifactCatalog` with owner, repository, artifact-ID, and artifact-type selectors plus deterministic missing and ambiguous-selection errors.
- Adds the virtual `asyncapi-all` selector for selecting all `asyncapi` and `asyncapi-client` artifacts belonging to one service without adding synthetic artifacts to the manifest.

### Source-Aware Manifest Editing

- Adds `ZenWaveManifestEditor.updateArtifactVersions` for updating selected artifact versions across root and referenced manifest documents.
- Adds the lower-level `updateScalars` API for existing owner and artifact scalar values.
- Preserves unrelated YAML formatting, comments, scalar quoting, and source ownership; edits are returned as document changes and are not persisted automatically.
- Adds `BlockingZenWaveManifestEditor` for Java and synchronous JVM integrations.

### Consumer and API-Consumption Modeling

- Supports artifact-qualified consumer references using `service.id#artifact.id` and explicit `type:<type>` selectors while retaining suffix-less service references for compatibility.
- Adds `ManifestConsumerIndex` to resolve declared consumers in both directions and infer compatible provider artifacts through configurable consumption rules.
- Adds `ManifestApiConsumptions`, `AsyncApiChannelIndex`, and `OpenApiOperationIndex` for catalog-agnostic AsyncAPI and OpenAPI analysis.
- Matches consumer and provider AsyncAPI operations using external channel references, channel addresses, and complementary send/receive directions; classifies command/event channels and OpenAPI command/query intent.
- Reports invalid, missing, and ambiguous consumption evidence as diagnostics so callers can retain partial results. Legacy global address matching remains available for compatibility.

### Typed Semantic Architecture Graph

- Adds the Kotlin Multiplatform `manifest-graph` module for JVM and Node/JS, with blocking JVM entry points for Java and synchronous callers.
- Builds stable, URI-safe nodes and edges for manifest domains, subdomains, services, artifacts, API operations, channels, messages, ZDL models, and ZFL flows.
- Retains source URI, artifact ownership, semantic path, line/column provenance, and diagnostics for loaded artifact content.
- Adds built-in ZDL and ZFL analyzers plus an extension point for additional artifact analyzers.
- Models ZFL operation occurrences, starts, triggers, emissions, responses, compensation paths, terminal outcomes, and their relationships without collapsing repeated operations.
- Adds typed OpenAPI and AsyncAPI operation bindings with invocation, trigger, emission, and response roles, including transport, message kind, direction, operation ID, HTTP method/path, and channel metadata.
- Provides graph query APIs for incoming/outgoing edges, resolved ZDL methods, operation occurrences, flow outcomes, consumers, and bounded subgraph traversal.
- Keeps graph construction resilient: artifact loading and analysis failures become diagnostics rather than discarding the remaining architecture graph.

### Schema and JVM API Updates

- Extends the versioned and latest manifest schemas for explicit artifact coordinates and artifact-qualified consumer references.
- Adds blocking JVM wrappers for architecture graph construction, API-consumption analysis, channel indexing, manifest editing, and loader operations.

**Full Changelog**: https://github.com/ZenWave360/zenwave-manifest/compare/v0.9.1...v0.9.2
