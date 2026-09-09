package com.source.client.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCamera(onQrCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val processing = remember { AtomicBoolean(false) }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    DisposableEffect(lifecycleOwner) {
        val disposed = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        val setup = Runnable {
            if (disposed.get()) return@Runnable
            val provider = runCatching { future.get() }.getOrNull() ?: return@Runnable
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { imageProxy ->
                if (!processing.compareAndSet(false, true)) {
                    imageProxy.close()
                } else {
                    try {
                        QrDecoder.decode(imageProxy)?.let { value -> mainExecutor.execute { onQrCode(value) } }
                    } finally {
                        processing.set(false)
                        imageProxy.close()
                    }
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }
        future.addListener(setup, mainExecutor)
        onDispose {
            disposed.set(true)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdownNow()
        }
    }
}

internal object QrDecoder {
    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val buffer = plane.buffer.duplicate()
        val start = buffer.position()
        val lastIndex = start + (height - 1) * plane.rowStride + (width - 1) * plane.pixelStride
        if (plane.rowStride <= 0 || plane.pixelStride <= 0 || lastIndex >= buffer.limit()) return null

        val luminance = ByteArray(width * height)
        if (plane.pixelStride == 1 && plane.rowStride == width) {
            buffer.get(luminance)
        } else {
            for (row in 0 until height) {
                val rowOffset = start + row * plane.rowStride
                for (column in 0 until width) {
                    luminance[row * width + column] = buffer.get(rowOffset + column * plane.pixelStride)
                }
            }
        }
        return decodeLuminance(luminance, width, height)
    }

    internal fun decodeLuminance(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luminance.size != width * height) return null
        val source = com.google.zxing.PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        return runCatching { com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text }.getOrNull()
    }
}
