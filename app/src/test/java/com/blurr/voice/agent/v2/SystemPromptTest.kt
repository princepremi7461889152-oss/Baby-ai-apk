package com.blurr.voice.agent.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blurr.voice.v2.AgentSettings
import com.blurr.voice.v2.SystemPromptLoader
import com.blurr.voice.v2.llm.MessageRole
import com.blurr.voice.v2.llm.TextPart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemPromptTest {

    private lateinit var context: Context
    private lateinit var loader: SystemPromptLoader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        loader = SystemPromptLoader(context)
    }

    private fun extractText(settings: AgentSettings): String {
        val message = loader.getSystemMessage(settings)
        assertTrue(message.role == MessageRole.MODEL)
        return (message.parts.first() as TextPart).text
    }

    @Test
    fun `default parameters load template and replace placeholders`() {
        val content = extractText(AgentSettings(maxActionsPerStep = 7))
        assertTrue(content.isNotBlank())
        assertFalse(content.contains("{max_actions}"))
        assertFalse(content.contains("{available_actions}"))
        assertFalse(content.contains("{user_info}"))
        assertTrue(content.contains("7"))
        assertTrue(content.contains("<intents_catalog>"))
    }

    @Test
    fun `overrideSystemMessage replaces template`() {
        val override = "Custom override system instruction"
        val content = extractText(AgentSettings(overrideSystemMessage = override))
        assertTrue(content.startsWith(override))
    }

    @Test
    fun `extendSystemMessage appends extra instructions`() {
        val extension = "Extra test instruction for Baby agent"
        val content = extractText(AgentSettings(extendSystemMessage = extension))
        assertTrue(content.contains(extension))
    }

    @Test
    fun `distinct maxActionsPerStep values produce distinct prompts`() {
        val prompt1 = extractText(AgentSettings(maxActionsPerStep = 3))
        val prompt2 = extractText(AgentSettings(maxActionsPerStep = 12))
        assertNotEquals(prompt1, prompt2)
        assertTrue(prompt1.contains("3"))
        assertTrue(prompt2.contains("12"))
    }
}
