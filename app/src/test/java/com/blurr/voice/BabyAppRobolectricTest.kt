package com.blurr.voice

import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.blurr.voice.api.AiRepository
import com.blurr.voice.api.GeminiRestAiRepository
import com.blurr.voice.api.TextPart
import com.blurr.voice.data.LocalUserDataStore
import com.blurr.voice.publicapis.PublicApisActivity
import com.blurr.voice.publicapis.PublicApisRepository
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.utilities.GeminiKeyManager
import com.blurr.voice.utilities.OnboardingManager
import com.blurr.voice.v2.llm.GeminiMessage
import com.blurr.voice.v2.llm.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BabyAppRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GeminiKeyManager(context).clearApiKey()
        OnboardingManager(context).setOnboardingCompleted(true)
    }

    private fun collectAllText(view: View, out: MutableList<String>) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.isNotBlank()) out.add(text)
            val hint = view.hint?.toString().orEmpty()
            if (hint.isNotBlank()) out.add(hint)
        }
        val desc = view.contentDescription?.toString().orEmpty()
        if (desc.isNotBlank()) out.add(desc)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectAllText(view.getChildAt(i), out)
            }
        }
    }

    @Test
    fun appBranding_replacesAllUserFacingPandaTextWithBaby() {
        assertEquals("Baby", context.getString(R.string.app_name))
        assertEquals("Wake up Baby", context.getString(R.string.shortcut_wake_up_short))
        assertEquals("+ Ask Baby", context.getString(R.string.input_box_hint))

        val layouts = listOf(
            R.layout.activity_main_content,
            R.layout.pro_upgrade_banner,
            R.layout.dialog_wake_word_failure,
            R.layout.activity_settings,
            R.layout.floating_panda_button,
            R.layout.activity_moments,
            R.layout.fragment_moments,
            R.layout.activity_pro_purchase,
            R.layout.activity_memories,
            R.layout.activity_privacy,
            R.layout.activity_create_trigger,
            R.layout.panda_widget_layout
        )

        val themedContext = ContextThemeWrapper(context, R.style.Theme_Blurr)
        val inflater = LayoutInflater.from(themedContext)
        layouts.forEach { layoutRes ->
            val root = inflater.inflate(layoutRes, null, false)
            val texts = mutableListOf<String>()
            collectAllText(root, texts)
            val pandaMatches = texts.filter { it.contains("panda", ignoreCase = true) }
            assertTrue(
                "Layout $layoutRes still contains user-facing 'Panda': $pandaMatches",
                pandaMatches.isEmpty()
            )
        }
    }

    @Test
    fun mainActivity_launchesWithBabyLabelsAndNoLoginGate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertFalse("MainActivity should not redirect to LoginActivity", activity.isFinishing)
        assertNull("MainActivity should not start another activity on launch", shadowOf(activity).nextStartedActivity)

        val texts = mutableListOf<String>()
        collectAllText(activity.window.decorView, texts)
        assertTrue(texts.any { it.contains("Get more with Baby Pro") })
        assertTrue(texts.any { it.contains("How to wake up Baby") })
        assertTrue(texts.any { it.contains("Make Baby do something") })
        assertFalse(
            "MainActivity UI must not contain Panda: ${texts.filter { it.contains("panda", true) }}",
            texts.any { it.contains("panda", ignoreCase = true) }
        )

        val tvPermissionStatus = activity.findViewById<TextView>(R.id.tv_permission_status)
        val managePermissionsButton = activity.findViewById<TextView>(R.id.btn_manage_permissions)
        assertEquals(View.VISIBLE, tvPermissionStatus.visibility)
        assertEquals(View.VISIBLE, managePermissionsButton.visibility)
    }

    @Test
    fun geminiKeyManager_andRepositoryDefaultModelBehavior() = runBlocking {
        assertEquals("gemini-3.8-flash", AiRepository.DEFAULT_MODEL)

        val keyManager = GeminiKeyManager(context)
        assertFalse(keyManager.hasApiKey())
        assertFalse(ApiKeyManager.hasKeys())
        assertEquals("Not configured", keyManager.getMaskedKey())

        keyManager.saveApiKey("  AIzaSyTestKey12345678  ")
        assertTrue(keyManager.hasApiKey())
        assertTrue(ApiKeyManager.hasKeys())
        assertEquals("AIzaSyTestKey12345678", keyManager.getApiKey())
        assertEquals("••••••5678", keyManager.getMaskedKey())

        keyManager.clearApiKey()
        assertFalse(keyManager.hasApiKey())
        assertNull(keyManager.getApiKey())

        val noKeyReply = GeminiRestAiRepository.generateContent(
            chat = listOf("user" to listOf(TextPart("Hello Baby"))),
            context = context
        )
        assertTrue(noKeyReply?.contains("Settings") == true)
        assertTrue(noKeyReply?.contains("Gemini 3.8 Flash", ignoreCase = true) == true)

        val noKeyAgentJson = GeminiRestAiRepository.generateAgentJson(
            messages = listOf(GeminiMessage(role = MessageRole.USER, parts = listOf(com.blurr.voice.v2.llm.TextPart("Open browser")))),
            context = context
        )
        assertNotNull(noKeyAgentJson)
        assertTrue(noKeyAgentJson!!.contains("done"))
        assertTrue(noKeyAgentJson.contains("Settings"))
    }

    @Test
    fun settingsActivity_savesAndClearsGeminiApiKeyAndUsesBabyLabel() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val editGemini = activity.findViewById<EditText>(R.id.editGeminiApiKey)
        val btnSaveGemini = activity.findViewById<TextView>(R.id.btnSaveGeminiKey)
        val btnClearGemini = activity.findViewById<Button>(R.id.btnClearGeminiKey)
        val statusText = activity.findViewById<TextView>(R.id.textGeminiKeyStatus)
        val wakeButton = activity.findViewById<TextView>(R.id.wakeWordButton)
        val texts = mutableListOf<String>()
        collectAllText(activity.window.decorView, texts)

        assertTrue(wakeButton.text.toString().isNotBlank())
        assertTrue(texts.any { it.contains("Baby") })
        assertFalse(texts.any { it.contains("panda", ignoreCase = true) })
        assertTrue(statusText.text.toString().contains("Not configured"))
        assertTrue(statusText.text.toString().contains("gemini-3.8-flash"))

        editGemini.setText("AIzaSyBabyTest9999")
        btnSaveGemini.performClick()

        assertEquals("AIzaSyBabyTest9999", GeminiKeyManager(context).getApiKey())
        assertTrue(statusText.text.toString().contains("Configured"))
        assertTrue(statusText.text.toString().contains("9999"))
        assertTrue(statusText.text.toString().contains("gemini-3.8-flash"))

        btnClearGemini.performClick()
        assertFalse(GeminiKeyManager(context).hasApiKey())
        assertEquals("", editGemini.text.toString())
        assertTrue(statusText.text.toString().contains("Not configured"))
    }

    @Test
    fun publicApis_catalogAndActivityFilteringWorkOffline() = runBlocking {
        val entries = PublicApisRepository.loadCatalog(context)
        assertTrue("Bundled public_apis_catalog.json should load multiple entries", entries.size >= 20)

        val controller = Robolectric.buildActivity(PublicApisActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val countView = activity.findViewById<TextView>(R.id.tvPublicApisCount)
        val searchBox = activity.findViewById<EditText>(R.id.editSearchPublicApis)
        val noAuthButton = activity.findViewById<Button>(R.id.btnToggleNoAuth)
        val httpsButton = activity.findViewById<Button>(R.id.btnToggleHttps)

        assertTrue(countView.text.toString().contains("public-apis"))

        noAuthButton.performClick()
        httpsButton.performClick()
        searchBox.setText("weather")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val weatherCountText = countView.text.toString()
        assertTrue(weatherCountText.contains("of"))
        assertFalse(weatherCountText.startsWith("0 of"))
    }

    @Test
    fun localUserDataStore_crudAndTaskHistoryWorkCorrectly() {
        val m1 = LocalUserDataStore.addMemory(context, "Likes cricket", "Chat")
        val m2 = LocalUserDataStore.addMemory(context, "Lives in Delhi", "Voice")

        var memories = LocalUserDataStore.getMemories(context)
        assertEquals(2, memories.size)
        assertTrue(memories.any { it.id == m1.id && it.text == "Likes cricket" })

        LocalUserDataStore.updateMemory(context, m1.id, "Loves test cricket")
        memories = LocalUserDataStore.getMemories(context)
        assertTrue(memories.any { it.id == m1.id && it.text == "Loves test cricket" })

        LocalUserDataStore.deleteMemory(context, m2.id)
        memories = LocalUserDataStore.getMemories(context)
        assertEquals(1, memories.size)

        LocalUserDataStore.recordTaskStart(context, "Check weather")
        var history = LocalUserDataStore.getTaskHistory(context)
        assertEquals("started", history.first().status)

        LocalUserDataStore.recordTaskCompletion(context, "Check weather", success = true)
        history = LocalUserDataStore.getTaskHistory(context)
        assertEquals("completed", history.first().status)
        assertEquals(true, history.first().success)

        LocalUserDataStore.recordTaskCompletion(context, "Failing task", success = false, errorMessage = "Timeout")
        history = LocalUserDataStore.getTaskHistory(context)
        val failedItem = history.first { it.task == "Failing task" }
        assertEquals("failed", failedItem.status)
        assertEquals("Timeout", failedItem.errorMessage)
    }

    @Test
    fun chatAndPrivacyAndRoleActivities_behaveSafely() {
        val chatController = Robolectric.buildActivity(ChatActivity::class.java).setup()
        val chatActivity = chatController.get()
        val texts = mutableListOf<String>()
        collectAllText(chatActivity.window.decorView, texts)
        assertTrue(texts.any { it.contains("How can I help you?") })
        assertFalse(texts.any { it.contains("helpy") })

        val privacyController = Robolectric.buildActivity(PrivacyActivity::class.java).setup()
        val privacyActivity = privacyController.get()
        assertEquals("How Baby Remembers", privacyActivity.supportActionBar?.title?.toString())

        val roleController = Robolectric.buildActivity(RoleRequestActivity::class.java, Intent()).setup()
        val roleActivity = roleController.get()
        assertTrue("RoleRequestActivity fallback should finish cleanly", roleActivity.isFinishing)
    }
}
