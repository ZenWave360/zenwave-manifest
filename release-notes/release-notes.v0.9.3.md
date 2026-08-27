## What's Changed

Patch release **0.9.3** fixes a JVM runtime incompatibility between the JSON Schema reference parser and Kotlin coroutines.

## Dependency Updates

- Upgrades `kotlinx-coroutines-core` and `kotlinx-coroutines-test` from `1.8.1` to `1.11.0`.
- Aligns the coroutines runtime with `json-schema-ref-parser-kmp` `0.9.23`, preventing `NoSuchMethodError` failures when invoking its blocking parser API.

**Full Changelog**: https://github.com/ZenWave360/zenwave-manifest/compare/v0.9.2...v0.9.3
