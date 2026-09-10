package com.source.client.ai

import android.content.res.AssetManager

object PadModelProbe {
    val partNames = arrayOf(
        "source-client-model-1-of-3.gguf.part",
        "source-client-model-2-of-3.gguf.part",
        "source-client-model-3-of-3.gguf.part",
    )

    init {
        System.loadLibrary("source-ai-pad")
    }

    fun probe(assetManager: AssetManager, loadTensors: Boolean = false): String =
        probeNative(assetManager, partNames, loadTensors)

    fun benchmark(
        assetManager: AssetManager,
        prompt: String,
        contextTokens: Int = 1_024,
        maximumOutputTokens: Int = 512,
        threads: Int = 4,
    ): String = benchmarkNative(
        assetManager,
        partNames,
        prompt,
        contextTokens,
        maximumOutputTokens,
        threads,
    )

    private external fun probeNative(
        assetManager: AssetManager,
        partNames: Array<String>,
        loadTensors: Boolean,
    ): String

    private external fun benchmarkNative(
        assetManager: AssetManager,
        partNames: Array<String>,
        prompt: String,
        contextTokens: Int,
        maximumOutputTokens: Int,
        threads: Int,
    ): String
}
