package io.zenwave360.manifest

// Archive extraction is supplied by the JavaScript host. Candidate construction,
// ordered fallback, and all other providers remain fully multiplatform.
internal actual fun defaultManifestArchiveEntryLoader(): ManifestArchiveEntryLoader? = null
