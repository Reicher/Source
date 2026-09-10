package com.source.client.ai

import android.content.res.AssetManager

internal object ModelAssetDiagnostics {
    init {
        System.loadLibrary("source-ai-pad")
    }

    fun probe(assetManager: AssetManager, loadTensors: Boolean = false): String =
        probeNative(assetManager, BundledModelAssets.partNames, loadTensors)

    fun benchmark(
        assetManager: AssetManager,
        prompt: String,
        contextTokens: Int = 1_024,
        maximumOutputTokens: Int = 512,
        threads: Int = 4,
    ): String = benchmarkNative(
        assetManager,
        BundledModelAssets.partNames,
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
