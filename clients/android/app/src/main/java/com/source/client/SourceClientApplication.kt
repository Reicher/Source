package com.source.client

import android.app.Application
import com.source.client.ai.LocalAiRuntime
import com.source.client.discovery.LocalNetworkMonitor
import com.source.client.discovery.NsdNodeDiscovery
import com.source.client.protocol.SourceNodeApi
import com.source.client.security.SecureVault

class SourceClientApplication : Application() {
    val secureVault by lazy { SecureVault(this) }
    val localAiRuntime by lazy { LocalAiRuntime(this) }
    val nodeApi by lazy { SourceNodeApi() }
    val nodeDiscovery by lazy { NsdNodeDiscovery(this) }
    val networkMonitor by lazy { LocalNetworkMonitor(this) }
}
