package com.source.self

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

private const val MAX_THUMBNAIL_SOURCE_BYTES = 32L * 1024 * 1024
private const val MAX_THUMBNAIL_SOURCE_PIXELS = 120_000_000L

/** Decodes only requested thumbnails, away from the UI thread, and reuses them by content hash. */
class ThumbnailLoader(private val bronze: BronzeStore, private val maxWidth: Int, private val maxHeight: Int) {
    private data class Target(
        val key: String,
        val view: WeakReference<ImageView>,
        val onUnavailable: () -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val waiting = mutableMapOf<String, MutableList<Target>>()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = maxOf(1, value.byteCount / 1024)
    }
    private var closed = false

    fun bind(item: BronzeItem, target: ImageView, onUnavailable: () -> Unit) {
        val key = item.hash
        target.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            show(target, key, cached)
            return
        }
        target.setImageDrawable(null)
        target.layoutParams = LinearLayout.LayoutParams(maxWidth, minOf(maxWidth, maxHeight))
        val listener = Target(key, WeakReference(target), onUnavailable)
        val listeners = waiting[key]
        if (listeners != null) {
            listeners.add(listener)
            return
        }
        waiting[key] = mutableListOf(listener)
        val file = bronze.content(item)
        if (!file.isFile || !file.canRead() || item.size <= 0 || item.size > MAX_THUMBNAIL_SOURCE_BYTES) {
            waiting.remove(key)?.forEach(::showUnavailable)
            return
        }
        workers.execute {
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    require(info.size.width > 0 && info.size.height > 0)
                    require(info.size.width.toLong() * info.size.height <= MAX_THUMBNAIL_SOURCE_PIXELS)
                    val sample = maxOf(1, maxOf(info.size.width, info.size.height) / 360)
                    decoder.setTargetSampleSize(sample)
                }
            }.getOrNull()
            main.post {
                if (closed) return@post
                if (bitmap != null) cache.put(key, bitmap)
                waiting.remove(key)?.forEach { reference ->
                    reference.view.get()?.let { view ->
                        if (bitmap != null) show(view, key, bitmap)
                        else if (view.tag == key) reference.onUnavailable()
                    }
                }
            }
        }
    }

    private fun show(target: ImageView, key: String, bitmap: Bitmap) {
        if (target.tag != key) return
        val scale = minOf(1f, maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        target.layoutParams = LinearLayout.LayoutParams(
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
        )
        target.setImageBitmap(bitmap)
    }

    private fun showUnavailable(target: Target) {
        target.view.get()?.let { view ->
            if (view.tag == target.key) target.onUnavailable()
        }
    }

    fun close() {
        closed = true
        waiting.clear()
        cache.evictAll()
        workers.shutdownNow()
    }
}
