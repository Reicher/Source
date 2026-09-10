package com.source.client.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PadModelAssetDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test
    fun installTimePartsFormOneDirectlyReadableGguf() {
        val result = PadModelProbe.probe(target.assets)
        assertTrue(result, result.contains("logicalBytes=3013027808"))
        assertTrue(result, result.contains("parts=3"))
        assertTrue(result, result.contains("officialChatTemplate=true"))
    }

    @Test
    fun fullTensorLoadUsesTheVirtualPadStreamWhenExplicitlyRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sourceFullModelProbe") == "true")
        val result = PadModelProbe.probe(target.assets, loadTensors = true)
        assertTrue(result, result.contains("tensorsLoaded=true"))
    }

    @Test
    fun benchmarkRealQwenGenerationWhenExplicitlyRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sourceQwenBenchmark") == "true")
        val result = PadModelProbe.benchmark(
            target.assets,
            prompt = "hello",
        )
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "$result\n") })
        assertTrue(result, result.contains("promptTokens="))
        assertTrue(result, result.contains("outputTokens="))
        assertTrue(result, result.contains("tokensPerSecond="))
        assertTrue(result, !result.contains("outputTokens=0;"))
        assertTrue(result, result.contains("seed=42"))
    }
}
