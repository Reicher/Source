package com.source.self

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val BACKGROUND_SYNC_WORK = "source-pending-bronze-sync"
private const val BACKGROUND_DISCOVERY_SECONDS = 15L

data class SourceEndpoint(val address: String, val port: Int)

object BackgroundSyncScheduler {
    fun enqueueIfPending(context: Context, state: PairingState, bronze: BronzeStore) {
        try {
            if (!state.isPaired() || state.source() == null || !bronze.hasPending()) return
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequest.Builder(SourceSyncWorker::class.java)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                BACKGROUND_SYNC_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        } catch (error: Exception) {
            Log.w("SelfBackgroundSync", "Could not schedule background sync: ${error.message}")
        }
    }
}

class SourceSyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    @Volatile private var discovery: BackgroundSourceDiscovery? = null
    @Volatile private var synchronizer: SourceSynchronizer? = null

    override fun doWork(): Result {
        val state = PairingState(applicationContext)
        val source = state.source() ?: return Result.success()
        if (!state.isPaired()) return Result.success()
        val bronze = SelfStores.bronze(applicationContext)
        if (!bronze.hasPending()) return Result.success()
        if (ForegroundSyncState.active) return Result.retry()

        return try {
            val finder = BackgroundSourceDiscovery(applicationContext, source.id)
            discovery = finder
            val endpoint = finder.find(BACKGROUND_DISCOVERY_SECONDS, TimeUnit.SECONDS)
                ?: return Result.retry()
            discovery = null
            if (isStopped || ForegroundSyncState.active) return Result.retry()

            val session = SourceSynchronizer(state, bronze, SelfStores.silver(applicationContext))
            synchronizer = session
            session.run(source, endpoint.address, endpoint.port, {
                !isStopped && !ForegroundSyncState.active
            })
            synchronizer = null
            if (isStopped || bronze.hasPending()) Result.retry() else Result.success()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.retry()
        } catch (error: PairingHttpException) {
            Log.w("SelfBackgroundSync", "Source returned ${error.status} for ${error.path}")
            if (error.status == 401 || error.status == 403 || error.status == 409) Result.failure()
            else Result.retry()
        } catch (error: Exception) {
            Log.w("SelfBackgroundSync", "Background sync failed: ${error.javaClass.simpleName}: ${error.message}")
            Result.retry()
        } finally {
            discovery?.cancel()
            discovery = null
            synchronizer = null
        }
    }

    override fun onStopped() {
        discovery?.cancel()
        synchronizer?.cancel()
        super.onStopped()
    }
}

@Suppress("DEPRECATION")
private class BackgroundSourceDiscovery(context: Context, private val sourceId: String) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val done = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val resolveLock = Any()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var started = false
    @Volatile private var endpoint: SourceEndpoint? = null
    @Volatile private var listener: NsdManager.DiscoveryListener? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    fun find(timeout: Long, unit: TimeUnit): SourceEndpoint? {
        val current = listener()
        listener = current
        multicastLock = runCatching {
            wifi?.createMulticastLock("SelfBackgroundSync")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        try {
            nsd.discoverServices("_sourceself._tcp.", NsdManager.PROTOCOL_DNS_SD, current)
            done.await(timeout, unit)
            return endpoint
        } finally {
            cancel()
        }
    }

    fun cancel() {
        if (closed.compareAndSet(false, true)) {
            stopDiscovery()
            done.countDown()
            multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
            multicastLock = null
        }
    }

    private fun listener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            started = true
            if (closed.get()) stopDiscovery()
        }

        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = cancel()

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(resolveLock) {
                if (closed.get()) return
                pending.addLast(serviceInfo)
                resolveNextLocked()
            }
        }
    }

    private fun resolveNextLocked() {
        if (closed.get() || resolving || pending.isEmpty()) return
        resolving = true
        val serviceInfo = pending.removeFirst()
        try {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolutionFinished()
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    if (!closed.get()) {
                        val announcedId = resolved.attributes["id"]?.toString(Charsets.UTF_8)
                        if (announcedId == sourceId) {
                            val hosts = if (Build.VERSION.SDK_INT >= 34) {
                                resolved.hostAddresses
                            } else {
                                listOfNotNull(resolved.host)
                            }
                            val host = hosts.firstOrNull { it is Inet4Address }
                                ?: hosts.firstOrNull()
                            if (host != null && resolved.port > 0) {
                                endpoint = host.hostAddress?.let { SourceEndpoint(it, resolved.port) }
                                if (endpoint != null) done.countDown()
                            }
                        }
                    }
                    resolutionFinished()
                }
            })
        } catch (_: Exception) {
            resolutionFinished()
        }
    }

    private fun resolutionFinished() {
        synchronized(resolveLock) {
            resolving = false
            resolveNextLocked()
        }
    }

    private fun stopDiscovery() {
        val current = listener ?: return
        if (!started || !stopped.compareAndSet(false, true)) return
        try {
            nsd.stopServiceDiscovery(current)
        } catch (_: Exception) {}
    }
}
