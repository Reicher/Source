package com.source.client.ai

import android.content.res.AssetManager

internal object BundledModelAssets {
    val partNames = arrayOf(
        "source-client-model-1-of-3.gguf.part",
        "source-client-model-2-of-3.gguf.part",
        "source-client-model-3-of-3.gguf.part",
    )

    fun areInstalled(assetManager: AssetManager): Boolean = partNames.all { name ->
        runCatching { assetManager.open(name).use { it.read() } }.isSuccess
    }
}
