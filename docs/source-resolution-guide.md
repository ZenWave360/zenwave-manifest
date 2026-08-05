# Source-resolution guide

This is the detailed runtime guide for ZenWave Manifest 1.0. The [1.0 schema](../zenwave-architecture/1.0/schema.json), [latest schema](../zenwave-architecture/latest/schema.json), Kotlin model, parser, and resolver implement the same read-only contract.

Version 1 reads manifest-managed content. It never publishes, uploads, registers, clones, pushes, commits, tags, signs, or deploys it.

## Resolution context

Hierarchy IDs resolve from an explicit non-blank `id` or the node's YAML map key. A direct service receives an empty `subdomain.id`.

| Runtime expression | Resolution |
| --- | --- |
| `${owner.id}` | Resolved ID of the service or domain declaring the artifact. |
| `${owner.repository}` | Explicit repository of the service or domain declaring the artifact. |
| `${domain.id}` | Resolved owning domain ID. |
| `${subdomain.id}` | Resolved owning subdomain ID, or empty for a direct service. |
| `${service.id}` | Resolved owning service ID. |
| `${service.repository}` | Explicit owning service repository name; absent when not declared. |
| `${domain.version}` | Explicit owning domain version, when declared. |
| `${subdomain.version}` | Explicit owning subdomain version, when declared. |
| `${service.version}` | Explicit owning service version, when declared. |
| `${artifact.version}` | Declared current artifact version; artifact `version` is a required field. |
| `${artifact.path}` | Complete declared artifact path. |
| `${artifact.name}` | Explicit artifact `name`; absent for an unnamed artifact. |
| `${artifact.fileName}` | Last path segment including all extensions. |
| `${artifact.fileNameWithoutExtension}` | Last segment with only its final extension removed. |
| `${content.path}` | Current artifact path or selected service-doc value. |
| `${service.docs[key]}` | Value of a literal document-map key. |
| `${groupId}` | Resolved common group coordinate. |
| `${artifactId}` | Resolved common artifact coordinate. |
| `${version}` | Effective operation version: `artifact.version` for an artifact load, the inherited service, subdomain, or domain version for a document load. |

`service.id` is the stable architecture identity; `service.repository` is an explicit source-control locator. The repository is never derived from the ID. For example, a domain-qualified identity can map to a short repository slug:

```yaml
domains:
  catalog:
    services:
      products:
        id: catalog.catalog-management.catalog-products
        repository: catalog-products-api
```

`${service.repository}` is available only for that explicit non-blank value. If the field is omitted and a selected source expression uses the variable, resolution fails with the normal unresolved-runtime-variable diagnostic rather than falling back to `service.id`.

`${artifact.name}` is never inferred. For `contracts/orders.openapi.yaml`, the filename is `orders.openapi.yaml` and its filename without the final extension is `orders.openapi`. `archive.tar.gz` becomes `archive.tar`; `.gitignore` and `README` remain unchanged.

Document lookup keys accept letters, numbers, `.`, `_`, and `-`. Given:

```yaml
docs:
  summary: docs/SUMMARY.md
  catalog: docs/EVENT_CATALOG.md
  readme: docs/README.md
```

the lookups resolve to their corresponding paths. `${service.docs}` denotes a map and is invalid. An invalid or missing lookup fails before I/O. When `loadServiceDocs` iterates the map, `${content.path}` is evaluated separately as `docs/SUMMARY.md`, `docs/EVENT_CATALOG.md`, and `docs/README.md`.

`${version}` depends on what is being loaded. For an artifact it is exactly `artifact.version`; `version` is a required artifact field and does not inherit from the service, subdomain, or domain. For a service document it inherits service, then subdomain, then domain. Qualified expressions such as `${service.version}` expose only that node's explicit declaration and are unresolved when it is absent; they do not inherit. Blank versions are absent — an artifact whose `version` is missing or whitespace-only reports the `missing-artifact-field` diagnostic at parse time — and YAML strings and numbers become strings. `config.version` is not content versioning, and the resolver never opens content to discover a version.

## Static and runtime interpolation

`config.properties` supplies ordinary static strings. Static expansion happens before runtime interpolation and cannot override any canonical variable, keyed docs lookup, or source-local `${server}`. Coordinates are first-class config fields rather than properties.

Runtime interpolation is exact `${name}` replacement. There are no functions, conditionals, nested expressions, fallback operators, or implicit discovery. Unresolved names report the source, complete expression, and names before transport I/O.

Provider encoding happens after values are selected:

- Git and generic Artifactory preserve `/` separators in `content.path` and encode each path segment.
- Apicurio encodes the complete `artifact.path` as one artifact-ID path parameter, including embedded `/` as `%2F`.
- hierarchy, service-repository, coordinate, name, and version values are encoded as ordinary URL path segments;
- URI schemes, query delimiters, and Artifactory's `!/` delimiter are preserved.

Empty slash-separated hierarchy segments are removed without rewriting non-empty segments or damaging a URI scheme. Thus `https://gitlab.com/commerce//orders/...` becomes `https://gitlab.com/commerce/orders/...`, while `sub-${subdomain.id}` becomes the retained `sub-` segment.

## Coordinates

```yaml
config:
  groupIdExpression: "${owner.id}"
  artifactIdExpression: "${artifact.fileNameWithoutExtension}"
```

These are the defaults. Resolution precedence is:

```text
groupId    = owner.groupId, otherwise config.groupIdExpression
artifactId = artifact.artifactId, otherwise config.artifactIdExpression
```

After resolution, both coordinates are available to provider expressions. Coordinate expressions themselves cannot reference `${groupId}` or `${artifactId}`. An operation selecting a missing coordinate fails before I/O.

## Ordered fallback and direct URIs

```yaml
config:
  contentResolution:
    - workspace
    - git
    - apicurio
    - artifactory
    - maven
```

The default is `[workspace]`. Only listed sources are active; source configuration alone has no effect on ordering. A transport or not-found failure continues to the next applicable candidate. Apicurio and Maven are skipped for document loads.

`ManifestLoadOptions.preferredSource` may move an already-active source to the front. With `allowFallback = false`, it restricts the attempt to that active source. It cannot inject a source absent from `contentResolution`.

Supported absolute artifact or document URIs—such as `file:///work/api.yml`, `https://example.com/api.yml`, or `classpath:/templates/api.hbs`—load directly and bypass the pipeline.

After exhaustion, `ManifestResourceLoadException` exposes redacted candidates in attempted order and reports the final load error without retaining a credential-bearing transport cause. Unknown names, active but unconfigured sources, missing required fields, invalid providers, malformed docs lookups, and unresolved expressions are deterministic pre-I/O errors where possible.

## Workspace

```yaml
sources:
  workspace:
    basePathExpression: "../../${domain.id}/${subdomain.id}/${service.id}"
```

Examples favor an explicit relative path from a nested manifest directory. The schema default, when the field is omitted, remains `${domain.id}/${subdomain.id}/${service.id}`. Resolution is always relative to the directory containing the loaded manifest:

```text
manifest directory + basePathExpression + content.path
```

There is no host local-root option. For `/work/architecture/manifests/zenwave-architecture.yml`, direct service `commerce/orders-api`, and artifact `contracts/orders.openapi.yaml`, `../../` reaches `/work` and the final path is:

```text
/work/commerce/orders-api/contracts/orders.openapi.yaml
```

An empty base resolves directly below the manifest directory. Relative traversal is normalized after interpolation. Another valid layout can target a named sibling content tree:

```yaml
basePathExpression: "../../services/${domain.id}/${subdomain.id}/${service.id}"
```

For `/work/architecture/manifests/zenwave.yml` and a direct `commerce/orders` service, this starts at `/work/services/commerce/orders` after the two parent traversals.

When workspace directories follow repository names instead of architecture IDs, use the explicit repository variable:

```yaml
sources:
  workspace:
    basePathExpression: "../${service.repository}"
```

With a manifest at `/work/manifests/zenwave.yml` and `repository: catalog-products-api`, content resolves below `/work/catalog-products-api`. Omitting `repository` makes this selected expression unresolved.

## Git over HTTP

Git resolution performs HTTP reads only; it never clones a repository. Known providers supply a server and complete URL default. Every default uses immutable tag `v${version}`.

### GitHub

```yaml
git:
  provider: github
  server: https://github.com
```

Default expression:

```text
${server}/${domain.id}/${service.id}/raw/v${version}/${content.path}
```

`domain.id` is the owner and `service.id` the repository. Example final URL:

```text
https://github.com/commerce/orders/raw/v1.2.3/contracts/api.yml
```

The known-provider default retains `service.id` for backward-compatible layouts. When the repository name differs, override the complete expression and select `service.repository` explicitly:

```yaml
git:
  provider: github
  server: https://github.com
  contentUrlExpression: "${server}/arcadia-editions/${service.repository}/raw/main/${content.path}"
```

For the domain-qualified service example above, this resolves through the `catalog-products-api` repository. The same complete-expression override supports GitHub Enterprise, an organization that differs from the domain, or another tag policy.

### GitLab

```yaml
git:
  provider: gitlab
  server: https://gitlab.com
```

Default expression:

```text
${server}/${domain.id}/${subdomain.id}/${service.id}/-/raw/v${version}/${content.path}
```

Domain, subdomain, and service form the nested namespace/repository. A direct service omits the empty subdomain. Final URLs are:

```text
https://gitlab.com/commerce/orders/-/raw/v1.2.3/contracts/api.yml
https://gitlab.com/commerce/fulfillment/shipping/-/raw/v2.0.0/contracts/events.yml
```

Self-managed GitLab or a different namespace layout uses `server` and/or a complete custom expression.

### Bitbucket Cloud

```yaml
git:
  provider: bitbucket
  server: https://api.bitbucket.org/2.0/repositories
```

Default expression:

```text
${server}/${domain.id}/${service.id}/src/v${version}/${content.path}
```

`domain.id` is the workspace. Example:

```text
https://api.bitbucket.org/2.0/repositories/commerce/orders/src/v1.2.3/contracts/api.yml
```

Override the expression for a repository name that differs from the service or a non-`v` tag policy.

### Generic Git hosts

`provider: generic` has no server or layout default and requires a complete expression:

```yaml
git:
  provider: generic
  server: https://git.example.com
  contentUrlExpression: "${server}/raw/platform/${service.id}/${version}/${content.path}"
```

The server is optional when the expression contains a complete literal host.

## Apicurio Registry v3

```yaml
apicurio:
  server: https://registry.example.com
```

Apicurio is artifact-only. Its fixed default is:

```text
${server}/apis/registry/v3/groups/${service.id}/artifacts/${artifact.path}/versions/${version}/content
```

The endpoint follows v3 version-content semantics. `service.id` is the group ID and the complete artifact path is the artifact ID. For `contracts/orders api.yaml`, the final URL is:

```text
https://registry.example.com/apis/registry/v3/groups/orders/artifacts/contracts%2Forders%20api.yaml/versions/1.0/content
```

`contentUrlExpression` may override the complete URL, but provider encoding still treats the artifact path as one ID parameter.

## Generic Artifactory

Generic Artifactory requires only `server`. When `contentUrlExpression` is omitted, it uses this preset:

```yaml
artifactory:
  server: https://artifacts.example.com
  contentUrlExpression: "${server}/artifactory/contracts/${domain.id}/${subdomain.id}/${service.id}/${version}/${content.path}"
```

The repository key (`contracts`) belongs in the expression. This layout supports artifacts and docs. Example final URLs are:

```text
https://artifacts.example.com/artifactory/contracts/commerce/orders/1.0/contracts/api.yml
https://artifacts.example.com/artifactory/contracts/commerce/orders/1.0/docs/SUMMARY.md
https://artifacts.example.com/artifactory/contracts/commerce/orders/1.0/docs/EVENT_CATALOG.md
```

Override `contentUrlExpression` with a complete URL expression when the repository key or layout differs. Nothing is appended to either the preset or an override after interpolation.

## Maven reads

Maven is artifact-only and uses common `groupId`, `artifactId`, and `version`. The primary JAR layout is:

```text
{groupId with dots replaced by slashes}/{artifactId}/{version}/{artifactId}-{version}.jar
```

`server` is a static value, resolved once at load time. `repository` is required, must be non-blank, and is a runtime expression: static `config.properties` expand first, then the remaining `${name}` placeholders are interpolated once per artifact, exactly like `git.contentUrlExpression`. An unresolved name fails with the standard unresolved-runtime-variable diagnostic before any I/O. Slashes in the resolved value stay path separators; every segment between them is URL-encoded.

That makes a single Maven source serve services that publish to different repositories, such as GitHub Packages, whose Maven URLs embed the owning GitHub repository.

The three providers build the same JAR URL and differ only in who opens it. `artifactory` appends `!/${artifact.path}` so the server returns the entry directly. `central` and `github` download the complete JAR and read the declared entry locally.

### Artifactory Maven

```yaml
maven:
  provider: artifactory
  server: https://artifacts.example.com/artifactory
  repository: maven-releases
```

Artifactory Archive Entry Download retrieves the entry directly. For `io.arcadia.orders:orders-openapi:1.1.0` and `contracts/orders.openapi.yaml`:

```text
https://artifacts.example.com/artifactory/maven-releases/io/arcadia/orders/orders-openapi/1.1.0/orders-openapi-1.1.0.jar!/contracts/orders.openapi.yaml
```

The slash after `!` is mandatory.

### Maven Central

```yaml
maven:
  provider: central
  server: https://repo.maven.apache.org
  repository: maven2
```

The server and repository have the shown defaults and may be overridden for a mirror, compatible proxy, or a per-service repository expression. The resolver downloads the primary JAR:

```text
https://repo.maven.apache.org/maven2/io/arcadia/orders/orders-openapi/1.1.0/orders-openapi-1.1.0.jar
```

It then reads `contracts/orders.openapi.yaml` locally. A missing entry is a candidate load failure, so ordered fallback continues. JVM provides extraction directly; a JavaScript host supplies an archive-entry loader when it needs Central extraction.

### GitHub Packages

```yaml
maven:
  provider: github
  repository: "arcadia-editions/${service.repository}"
```

GitHub Packages serves a standard Maven layout over plain HTTP and has no Artifactory-style archive-entry endpoint, so entries are extracted client-side. `provider: github` defaults `server` to `https://maven.pkg.github.com` and requires `repository` as `{owner}/{repo}`. Set `server` explicitly only for GitHub Enterprise. Because `repository` is a runtime expression, one declaration serves every service that publishes to its own repository.

For `${service.repository}` resolving to `catalog-products-api`, `io.arcadia:products:1.1.0`, and artifact `contracts/api.yml`, the JAR URL is:

```text
https://maven.pkg.github.com/arcadia-editions/catalog-products-api/io/arcadia/products/1.1.0/products-1.1.0.jar
```

Like `central`, the resolver downloads the complete JAR and reads the declared entry locally. A missing entry is a candidate load failure, so ordered fallback continues.

## Source applicability

| Source | Artifact candidate | Document candidate |
| --- | --- | --- |
| Workspace | Yes | Yes |
| Git | Yes | Yes |
| Apicurio | Yes | Skipped |
| Generic Artifactory | Yes | Yes |
| Maven | Yes | Skipped |

All tests use in-memory, recording, temporary-file, or local JAR transports. They perform no real network access.
