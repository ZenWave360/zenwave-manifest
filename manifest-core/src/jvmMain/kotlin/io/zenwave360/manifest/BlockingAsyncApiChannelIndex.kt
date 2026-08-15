package io.zenwave360.manifest

import kotlinx.coroutines.runBlocking
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** Synchronous JVM facade for parsing a standalone AsyncAPI channel index. */
object BlockingAsyncApiChannelIndex {
    @JvmStatic
    @JvmOverloads
    fun parse(text: String, location: String = "asyncapi"): AsyncApiChannelIndex =
        runBlocking { AsyncApiChannelIndex.parse(text, location) }
}
