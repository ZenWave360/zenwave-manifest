package io.zenwave360.manifest

import kotlinx.coroutines.runBlocking
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** Synchronous JVM facade for [ManifestApiConsumptions]. */
object BlockingManifestApiConsumptions {
    @JvmStatic
    @JvmOverloads
    fun build(
        manifest: ZenWaveManifest,
        loader: ZenWaveManifestLoader = ZenWaveManifestLoader(),
        options: ApiConsumptionOptions = ApiConsumptionOptions(),
    ): ManifestApiConsumptions = runBlocking {
        ManifestApiConsumptions.build(manifest, loader, options)
    }
}
