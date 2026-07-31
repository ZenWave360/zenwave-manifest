package io.zenwave360.manifest

import io.zenwave360.jsonrefparser.RefParser

@Suppress("unused")
object ManifestReferenceResolver {
    fun normalizeUri(uri: String): String = RefParser.normalizeUri(uri)

    fun resolveReference(baseUri: String, reference: String): String {
        if (reference.isBlank()) return normalizeUri(baseUri)
        if (hasScheme(reference) || reference.startsWith("#")) return normalizeUri(reference)

        val base = normalizeUri(baseUri)
        val prefix = schemePrefix(base)
        val basePath = stripScheme(base)
        val baseDir = basePath.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDir.isEmpty()) reference else "$baseDir/$reference"
        return withScheme(prefix, normalizeFilesystemPath(combined))
    }

    fun appendPath(base: String, child: String): String {
        if (child.isBlank()) return normalizeUri(base)
        if (hasScheme(child)) return normalizeUri(child)
        return normalizeEmptySegments("${base.trimEnd('/')}/${child.trimStart('/')}")
    }

    /** Resolves one slash-separated path against another without introducing a URI scheme. */
    fun resolvePathReference(basePath: String, reference: String): String {
        if (reference.isBlank()) return normalizeFilesystemPath(basePath)
        val normalizedReference = reference.replace('\\', '/')
        if (normalizedReference.startsWith('/')) {
            return normalizeFilesystemPath(normalizedReference).trimStart('/')
        }
        val normalizedBase = basePath.replace('\\', '/')
        val baseDirectory = normalizedBase.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDirectory.isEmpty()) normalizedReference else "$baseDirectory/$normalizedReference"
        return normalizeFilesystemPath(combined).trimStart('/')
    }

    /** Removes empty slash-separated path segments without damaging a URI scheme. */
    fun normalizeEmptySegments(value: String): String {
        val scheme = when {
            value.contains("://") -> value.substringBefore("://") + "://"
            else -> ""
        }
        val remainder = if (scheme.isEmpty()) value else value.substringAfter("://")
        val suffixIndex = remainder.indexOfFirst { it == '?' || it == '#' }
        val path = if (suffixIndex < 0) remainder else remainder.substring(0, suffixIndex)
        val suffix = if (suffixIndex < 0) "" else remainder.substring(suffixIndex)
        val leadingSlash = scheme.isEmpty() && path.startsWith('/')
        val normalized = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")
        return scheme + (if (leadingSlash) "/" else "") + normalized + suffix
    }

    fun encodePathSegment(value: String): String = value.encodeToByteArray().joinToString("") { byte ->
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if ((character in 'a'..'z') || (character in 'A'..'Z') || (character in '0'..'9') ||
            character == '-' || character == '.' || character == '_' || character == '~'
        ) {
            character.toString()
        } else {
            "%" + unsigned.toString(16).uppercase().padStart(2, '0')
        }
    }

    fun encodePath(value: String): String =
        value.replace('\\', '/').split('/').joinToString("/") { encodePathSegment(it) }

    fun hasScheme(value: String): Boolean =
        Regex("""^[A-Za-z][A-Za-z0-9+.-]*:(//)?""").containsMatchIn(value)

    private fun schemePrefix(value: String): String = when {
        value.contains("://") -> value.substringBefore("://") + "://"
        value.contains(':') -> value.substringBefore(':') + ":"
        else -> ""
    }

    private fun stripScheme(value: String): String = when {
        value.contains("://") -> value.substringAfter("://")
        Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""").containsMatchIn(value) -> value.substringAfter(':')
        else -> value
    }

    private fun withScheme(prefix: String, path: String): String = when {
        prefix.isEmpty() -> path
        prefix.endsWith("://") -> "$prefix$path"
        path.startsWith("/") -> "$prefix$path"
        else -> "$prefix/$path"
    }

    private fun normalizeFilesystemPath(path: String): String {
        val absolute = path.replace('\\', '/').startsWith('/')
        val segments = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." && segments.isNotEmpty() && segments.last() != ".." -> segments.removeAt(segments.lastIndex)
                part == ".." && !absolute -> segments += part
                part != ".." -> segments += part
            }
        }
        return (if (absolute) "/" else "") + segments.joinToString("/")
    }
}
