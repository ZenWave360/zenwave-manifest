package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.RefParser

object ManifestReferenceResolver {
    fun normalizeUri(uri: String): String = RefParser.normalizeUri(uri)

    fun resolveReference(baseUri: String, reference: String): String {
        if (reference.isBlank()) return normalizeUri(baseUri)
        if (hasScheme(reference) || reference.startsWith("#")) return reference

        val normalizedBase = normalizeUri(baseUri)
        val basePrefix = schemePrefix(normalizedBase)
        val basePath = stripScheme(normalizedBase)
        val baseDir = basePath.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDir.isEmpty()) reference else "$baseDir/$reference"
        return withScheme(basePrefix, normalizePath(combined))
    }

    fun appendPath(base: String, child: String): String {
        if (child.isBlank()) return normalizeUri(base)
        if (hasScheme(child)) return child

        val normalizedBase = normalizeUri(base)
        val prefix = schemePrefix(normalizedBase)
        val basePath = stripScheme(normalizedBase).trimEnd('/')
        return withScheme(prefix, normalizePath("$basePath/$child"))
    }

    fun hasScheme(value: String): Boolean =
        Regex("""^[A-Za-z][A-Za-z0-9+.-]*:(//)?""").containsMatchIn(value)

    private fun schemePrefix(value: String): String =
        when {
            value.contains("://") -> value.substringBefore("://") + "://"
            value.contains(':') -> value.substringBefore(':') + ":"
            else -> ""
        }

    private fun stripScheme(value: String): String =
        when {
            value.contains("://") -> value.substringAfter("://")
            Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""").containsMatchIn(value) -> value.substringAfter(':')
            else -> value
        }

    private fun withScheme(prefix: String, path: String): String =
        when {
            prefix.isEmpty() -> path
            prefix.endsWith("://") -> "$prefix$path"
            path.startsWith("/") -> "$prefix$path"
            else -> "$prefix/$path"
        }

    private fun normalizePath(path: String): String {
        val segments = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isEmpty() && segments.isEmpty() -> segments += ""
                part.isEmpty() || part == "." -> Unit
                part == ".." && segments.size > 1 -> segments.removeAt(segments.lastIndex)
                part != ".." -> segments += part
            }
        }
        return segments.joinToString("/")
    }
}

