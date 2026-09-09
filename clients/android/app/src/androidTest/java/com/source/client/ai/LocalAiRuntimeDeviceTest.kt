package com.source.client.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAiRuntimeDeviceTest {
    @Test
    fun packagedModelAnswersOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val response = LocalAiRuntime(context).chat(
            listOf(ChatMessage.user("Svara med en kort svensk hälsning.")),
        )

        assertEquals(ChatRole.ASSISTANT, response.role)
        assertTrue(response.content.isNotBlank())
    }
}
