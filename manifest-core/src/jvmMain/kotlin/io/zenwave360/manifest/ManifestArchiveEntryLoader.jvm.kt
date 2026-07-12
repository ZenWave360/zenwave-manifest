package io.zenwave360.manifest

import java.io.ByteArrayInputStream
import java.net.URI
import java.util.jar.JarInputStream

internal actual fun defaultManifestArchiveEntryLoader(): ManifestArchiveEntryLoader? =
    ManifestArchiveEntryLoader { uri, entryPath ->
        val bytes = URI(uri).toURL().openStream().use { it.readBytes() }
        val normalizedEntry = entryPath.replace('\\', '/').trimStart('/')
        JarInputStream(ByteArrayInputStream(bytes)).use { jar ->
            var entry = jar.nextJarEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == normalizedEntry) {
                    return@ManifestArchiveEntryLoader jar.readBytes().decodeToString()
                }
                entry = jar.nextJarEntry
            }
        }
        throw IllegalStateException("JAR entry '$normalizedEntry' was not found in $uri")
    }
