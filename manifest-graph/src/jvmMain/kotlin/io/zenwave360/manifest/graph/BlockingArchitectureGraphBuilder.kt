package io.zenwave360.manifest.graph

import io.zenwave360.manifest.ZenWaveManifest
import io.zenwave360.manifest.ZenWaveManifestLoader
import kotlinx.coroutines.runBlocking
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** Synchronous JVM facade for Java-based generators, MCP servers, and CI tooling. */
class BlockingArchitectureGraphBuilder @JvmOverloads constructor(
    val delegate: ArchitectureGraphBuilder = ArchitectureGraphBuilder(),
) {
    @JvmOverloads
    fun build(
        manifest: ZenWaveManifest,
        options: ArchitectureGraphBuildOptions = ArchitectureGraphBuildOptions(),
    ): ArchitectureGraphResult = runBlocking { delegate.build(manifest, options) }
}

object BlockingArchitectureGraph {
    @JvmStatic
    @JvmOverloads
    fun build(
        manifest: ZenWaveManifest,
        loader: ZenWaveManifestLoader = ZenWaveManifestLoader(),
        options: ArchitectureGraphBuildOptions = ArchitectureGraphBuildOptions(),
    ): ArchitectureGraphResult = BlockingArchitectureGraphBuilder(ArchitectureGraphBuilder(loader)).build(manifest, options)
}
