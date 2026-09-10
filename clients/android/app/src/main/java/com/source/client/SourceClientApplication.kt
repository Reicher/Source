package com.source.client

import android.app.Application
import com.source.client.ai.LocalAiRuntime
import com.source.client.discovery.LocalNetworkMonitor
import com.source.client.discovery.NsdNodeDiscovery
import com.source.client.protocol.SourceNodeApi
import com.source.client.security.SecureVault
import java.io.File

class SourceClientApplication : Application() {
    val secureVault by lazy { SecureVault(this) }
    private val localAiRuntimeDelegate = lazy { LocalAiRuntime(this) }
    val localAiRuntime by localAiRuntimeDelegate
    val nodeApi by lazy { SourceNodeApi() }
    val nodeDiscovery by lazy { NsdNodeDiscovery(this) }
    val networkMonitor by lazy { LocalNetworkMonitor(this) }

    override fun onCreate() {
        super.onCreate()
        cleanupLegacyLocalAi()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN && localAiRuntimeDelegate.isInitialized()) {
            localAiRuntime.releaseMemory()
        }
    }

    private fun cleanupLegacyLocalAi() {
        val modelDirectory = File(filesDir, "models")
        listOf(
            File(modelDirectory, "source-client-model.litertlm"),
            File(modelDirectory, ".source-client-model.litertlm.tmp"),
        ).forEach(File::delete)
        if (modelDirectory.listFiles()?.isEmpty() == true) modelDirectory.delete()
        cacheDir.listFiles()
            ?.filter { it.name.startsWith("source-client-model.litertlm_") }
            ?.forEach(File::deleteRecursively)
    }
}
