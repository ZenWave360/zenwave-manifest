# ZenWave Manifest 1.0 read-resolution specification

Status: current contract  
Date: 2026-07-15

ZenWave Manifest 1.0 is read-only. A conforming implementation resolves content but does not publish, upload, register, clone, push, commit, tag, sign, or deploy manifest-managed content.

## 1. Manifest identity and content

Domain, subdomain, and service `id` fields are optional non-blank strings. The resolved ID is the explicit value or the node's YAML map key. A service directly below a domain has `subdomain.id = ""` in path-expression contexts.

A service `repository` field is an optional non-blank string naming its source repository. It is distinct from the stable architecture `id`, is explicit-only, and MUST NOT default to or be derived from `service.id`.

Artifact `name` is optional and explicit-only. It is never derived from the artifact path. Service `docs` is a map whose non-blank keys use letters, numbers, `.`, `_`, and `-`; values are non-blank paths or supported absolute URIs.

String and numeric versions normalize to strings. Content version inheritance is artifact, service, subdomain, domain, unresolved. Documents start at service. `config.version` and inspected content are not version sources.

## 2. Static properties and runtime expressions

Static `config.properties` expansion precedes runtime interpolation. Properties MUST NOT override canonical values, keyed docs lookups, or source-local `server`.

Canonical runtime variables are:

```text
${domain.id}
${subdomain.id}
${service.id}
${service.repository}
${domain.version}
${subdomain.version}
${service.version}
${artifact.version}
${artifact.path}
${artifact.name}
${artifact.fileName}
${artifact.fileNameWithoutExtension}
${content.path}
${service.docs[key]}
${groupId}
${artifactId}
${version}
```

`service.repository` exists only for an explicitly declared service repository. A selected expression using it when absent MUST fail as an unresolved runtime variable; implementations MUST NOT substitute `service.id`. `artifact.name` exists only for an explicitly named artifact. `artifact.fileName` is the final path segment. `artifact.fileNameWithoutExtension` removes only the final extension; dotfiles and extensionless filenames are unchanged. `${version}` is the inherited effective version. Each qualified version expression exposes only the explicit version on its named node and is unresolved when that declaration is absent.

Bracket contents in `service.docs[key]` are literal. A missing key is unresolved before I/O; `${service.docs}` is invalid. `content.path` is the artifact path for an artifact load and the selected docs-map value for a document load. Loading all docs evaluates expressions separately for every entry.

Interpolation is literal and has no functions, conditionals, nesting, or fallback syntax. Missing values fail before I/O. `${server}` is supplied only by the active source and is not a global canonical value.

Provider encoding occurs after value selection. Git and generic Artifactory preserve content-path `/` separators; Apicurio encodes the complete artifact path as one REST path parameter; hierarchy and coordinates are path segments. Schemes, query delimiters, and Artifactory `!/` remain intact.

## 3. Common coordinates

Config defaults are:

```yaml
groupIdExpression: "${service.id}"
artifactIdExpression: "${artifact.fileNameWithoutExtension}"
```

Coordinates resolve with this precedence:

```text
groupId    = service.groupId, otherwise config.groupIdExpression
artifactId = artifact.artifactId, otherwise config.artifactIdExpression
```

Coordinate expressions MUST NOT recursively select `groupId` or `artifactId`. After resolution, both are available to every artifact-provider URL expression. An operation selecting a missing coordinate fails before I/O.

## 4. Ordered resolution

`config.contentResolution` is an ordered list containing any of `workspace`, `git`, `apicurio`, `artifactory`, and `maven`; it defaults to `[workspace]`. Configuration does not activate a source. Unknown names, active unconfigured sources, missing required fields, and invalid providers are deterministic errors.

A preferred source can reorder or restrict only already-active sources. Transport or not-found failure advances to the next applicable candidate. Apicurio and Maven are skipped for document loads. Terminal errors list redacted candidates in attempted order and the final error.

A supported absolute artifact or document URI bypasses `contentResolution`.

## 5. Sources

### 5.1 Workspace

The default base expression is:

```text
${domain.id}/${subdomain.id}/${service.id}
```

Authoring examples SHOULD favor an explicit relative path when manifests live in a nested architecture directory:

```yaml
basePathExpression: "../../${domain.id}/${subdomain.id}/${service.id}"
```

The final path is the manifest directory plus the resolved base expression plus `content.path`. Empty slash-separated segments are removed without rewriting non-empty content. Filesystem paths are normalized after interpolation. An empty base resolves below the manifest directory; relative traversal such as `../../` is allowed. No host local-root input exists.

For `/work/architecture/manifests/zenwave-architecture.yml`, that explicit `../../` expression, direct service `commerce/orders-api`, and artifact `contracts/orders.openapi.yaml`, the result is `/work/commerce/orders-api/contracts/orders.openapi.yaml`.

Repositories may define the workspace location explicitly:

```yaml
basePathExpression: "../${service.repository}"
```

For a manifest at `/work/manifests/zenwave.yml`, service `id: catalog.catalog-management.catalog-products`, and `repository: catalog-products-api`, the base resolves to `/work/catalog-products-api`. Without the repository declaration, selecting this expression is an unresolved-variable error.

### 5.2 Git

Git is HTTP-only. Providers are `github`, `gitlab`, `bitbucket`, and `generic`. Known defaults use tag `v${version}`:

```text
github:    ${server}/${domain.id}/${service.id}/raw/v${version}/${content.path}
gitlab:    ${server}/${domain.id}/${subdomain.id}/${service.id}/-/raw/v${version}/${content.path}
bitbucket: ${server}/${domain.id}/${service.id}/src/v${version}/${content.path}
```

Default servers are `https://github.com`, `https://gitlab.com`, and `https://api.bitbucket.org/2.0/repositories`. A direct GitLab service omits the empty subdomain. Known providers may override the complete `contentUrlExpression` for enterprise hosts, organizations, repository names, and tag layouts. When repository identity differs from `service.id`, a complete expression MAY select the explicit repository:

```yaml
git:
  provider: github
  server: https://github.com
  contentUrlExpression: "${server}/arcadia-editions/${service.repository}/raw/main/${content.path}"
```

For service `id: catalog.catalog-management.catalog-products` with `repository: catalog-products-api`, the repository URL segment is `catalog-products-api`. `generic` has no defaults and requires a complete expression.

### 5.3 Apicurio Registry v3

Apicurio is artifact-only. It requires `server`; its default complete expression is:

```text
${server}/apis/registry/v3/groups/${service.id}/artifacts/${artifact.path}/versions/${version}/content
```

This is the v3 version-content endpoint. `service.id` is the group ID and the entire `artifact.path` is one encoded artifact-ID parameter. A complete custom expression may be configured.

### 5.4 Generic Artifactory

Generic Artifactory requires `server`. Its preset `contentUrlExpression` is:

```text
${server}/artifactory/contracts/${domain.id}/${subdomain.id}/${service.id}/${version}/${content.path}
```

The repository key belongs in the expression. A manifest may override the complete expression for another repository key or layout. The implementation appends nothing after interpolation. This source supports artifacts and documents.

### 5.5 Maven

Maven is artifact-only. Providers are `artifactory` and `central`. It uses common coordinates and the standard primary JAR path:

```text
{groupId with dots replaced by slashes}/{artifactId}/{version}/{artifactId}-{version}.jar
```

Artifactory requires `server` and `repository`. It reads the entry directly with Archive Entry Download:

```text
${server}/${repository}/{jar path}!/${artifact.path}
```

The slash after `!` is mandatory.

Central defaults to `server: https://repo.maven.apache.org` and `repository: maven2`; either may be overridden for a mirror. It downloads `${server}/${repository}/{jar path}` and reads `artifact.path` from the JAR locally. A missing entry is a candidate load failure and permits fallback.

## 6. Applicability

| Source | Artifacts | Documents |
| --- | --- | --- |
| workspace | read | read |
| git | read | read |
| apicurio | read | skipped |
| artifactory | read | read |
| maven | read | skipped |

## 7. Deterministic diagnostics

Implementations MUST diagnose before transport I/O where possible:

- unknown or active unconfigured source;
- invalid Git or Maven provider;
- missing source server, repository, or required expression;
- unresolved or invalid runtime expression and docs lookup;
- recursive or missing common coordinate;
- missing content version when selected by a source;
- preferred source not active in the ordered list.

Candidate transport failure MUST continue in order. Exhaustion MUST report redacted attempted candidates and the final failure. Tests MUST use in-memory, recording, or local file/archive transports and MUST NOT require real network access.
