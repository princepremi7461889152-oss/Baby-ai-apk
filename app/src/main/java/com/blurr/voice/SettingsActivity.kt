package com.blurr.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.api.GoogleTts
import com.blurr.voice.api.PicovoiceKeyManager
import com.blurr.voice.api.TTSVoice
import com.blurr.voice.ecc.EccToolkitActivity
import com.blurr.voice.openhands.OpenHandsActivity
import com.blurr.voice.publicapis.PublicApisActivity
import com.blurr.voice.utilities.GeminiKeyManager
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.VoicePreferenceManager
import com.blurr.voice.utilities.WakeWordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class SettingsActivity : BaseNavigationActivity() {

    private lateinit var ttsVoicePicker: NumberPicker
    private lateinit var switchShowThoughts: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var permissionsInfoButton: TextView
    private lateinit var batteryOptimizationHelpButton: TextView
    private lateinit var appVersionText: TextView
    private lateinit var editUserName: EditText
    private lateinit var editUserEmail: EditText
    private lateinit var editWakeWordKey: EditText
    private lateinit var textGetPicovoiceKeyLink: TextView
    private lateinit var wakeWordButton: TextView
    private lateinit var buttonSignOut: Button
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private lateinit var editGeminiApiKey: EditText
    private lateinit var textGeminiKeyStatus: TextView
    private lateinit var textGetGeminiKeyLink: TextView
    private lateinit var btnSaveGeminiKey: TextView
    private lateinit var btnClearGeminiKey: Button
    private lateinit var btnOpenPublicApis: TextView
    private lateinit var btnOpenEccToolkit: TextView
    private lateinit var btnOpenOpenHands: TextView
    private lateinit var geminiKeyManager: GeminiKeyManager

    private lateinit var sc: SpeechCoordinator
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var availableVoices: List<TTSVoice>
    private var voiceTestJob: Job? = null

    companion object {
        private const val PREFS_NAME = "BlurrSettings"
        private const val KEY_SELECTED_VOICE = "selected_voice"
        private const val TEST_TEXT = "Hello, I'm Baby, and this is a test of the selected voice."
        private val DEFAULT_VOICE = TTSVoice.CHIRP_PUCK
        const val KEY_SHOW_THOUGHTS = "show_thoughts"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
                wakeWordManager.handleWakeWordButtonClick(wakeWordButton)
                updateWakeWordButtonState()
            } else {
                Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

        initialize()
        setupUI()
        loadAllSettings()
        setupAutoSavingListeners()
        cacheVoiceSamples()
    }

    override fun onStop() {
        super.onStop()
        sc.stop()
        voiceTestJob?.cancel()
    }

    private fun initialize() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sc = SpeechCoordinator.getInstance(this)
        availableVoices = GoogleTts.getAvailableVoices()
        wakeWordManager = WakeWordManager(this, requestPermissionLauncher)
        geminiKeyManager = GeminiKeyManager(this)
    }

    private fun setupUI() {
        ttsVoicePicker = findViewById(R.id.ttsVoicePicker)
        switchShowThoughts = findViewById(R.id.switchShowThoughts)
        permissionsInfoButton = findViewById(R.id.permissionsInfoButton)
        appVersionText = findViewById(R.id.appVersionText)
        batteryOptimizationHelpButton = findViewById(R.id.batteryOptimizationHelpButton)

        editWakeWordKey = findViewById(R.id.editWakeWordKey)
        wakeWordButton = findViewById(R.id.wakeWordButton)

        editGeminiApiKey = findViewById(R.id.editGeminiApiKey)
        textGeminiKeyStatus = findViewById(R.id.textGeminiKeyStatus)
        textGetGeminiKeyLink = findViewById(R.id.textGetGeminiKeyLink)
        btnSaveGeminiKey = findViewById(R.id.btnSaveGeminiKey)
        btnClearGeminiKey = findViewById(R.id.btnClearGeminiKey)
        btnOpenPublicApis = findViewById(R.id.btnOpenPublicApis)
        btnOpenEccToolkit = findViewById(R.id.btnOpenEccToolkit)
        btnOpenOpenHands = findViewById(R.id.btnOpenOpenHands)

        buttonSignOut = findViewById(R.id.buttonSignOut)

        editUserName = findViewById(R.id.editUserName)
        editUserEmail = findViewById(R.id.editUserEmail)
        textGetPicovoiceKeyLink = findViewById(R.id.textGetPicovoiceKeyLink)

        setupClickListeners()
        setupVoicePicker()

        kotlin.runCatching {
            val pm = UserProfileManager(this)
            editUserName.setText(pm.getName() ?: "")
            editUserEmail.setText(pm.getEmail() ?: "")
        }

        val versionName = BuildConfig.VERSION_NAME
        appVersionText.text = "Version $versionName"
    }

    private fun setupVoicePicker() {
        val voiceDisplayNames = availableVoices.map { it.displayName }.toTypedArray()
        ttsVoicePicker.minValue = 0
        ttsVoicePicker.maxValue = voiceDisplayNames.size - 1
        ttsVoicePicker.displayedValues = voiceDisplayNames
        ttsVoicePicker.wrapSelectorWheel = false
    }

    private fun setupClickListeners() {
        permissionsInfoButton.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        batteryOptimizationHelpButton.setOnClickListener {
            showBatteryOptimizationDialog()
        }

        btnSaveGeminiKey.setOnClickListener {
            val key = editGeminiApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter a Gemini API key first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            geminiKeyManager.saveApiKey(key)
            updateGeminiKeyStatus()
            Toast.makeText(this, "Gemini 3.8 Flash API key saved.", Toast.LENGTH_SHORT).show()
        }

        btnClearGeminiKey.setOnClickListener {
            geminiKeyManager.clearApiKey()
            editGeminiApiKey.setText("")
            updateGeminiKeyStatus()
            Toast.makeText(this, "Gemini API key cleared.", Toast.LENGTH_SHORT).show()
        }

        textGetGeminiKeyLink.setOnClickListener {
            openExternalUrl("https://aistudio.google.com/apikey")
        }

        btnOpenPublicApis.setOnClickListener {
            startActivity(Intent(this, PublicApisActivity::class.java))
        }

        btnOpenEccToolkit.setOnClickListener {
            startActivity(Intent(this, EccToolkitActivity::class.java))
        }

        btnOpenOpenHands.setOnClickListener {
            startActivity(Intent(this, OpenHandsActivity::class.java))
        }

        wakeWordButton.setOnClickListener {
            val keyManager = PicovoiceKeyManager(this)
            val userKey = editWakeWordKey.text.toString().trim()
            if (userKey.isNotEmpty()) {
                keyManager.saveUserProvidedKey(userKey)
                Toast.makeText(this, "Wake word key saved.", Toast.LENGTH_SHORT).show()
            }

            val hasKey = !keyManager.getUserProvidedKey().isNullOrBlank()
            if (!hasKey) {
                showPicovoiceKeyRequiredDialog()
                return@setOnClickListener
            }

            wakeWordManager.handleWakeWordButtonClick(wakeWordButton)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateWakeWordButtonState() }, 500)
        }

        textGetPicovoiceKeyLink.setOnClickListener {
            openExternalUrl("https://console.picovoice.ai/login")
        }

        buttonSignOut.setOnClickListener {
            showResetSettingsConfirmationDialog()
        }

        findViewById<TextView>(R.id.viewTaskLogsButton).setOnClickListener {
            startActivity(Intent(this, TaskLogsListActivity::class.java))
        }
    }

    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link. No browser found.", Toast.LENGTH_SHORT).show()
            Log.e("SettingsActivity", "Failed to open external URL: $url", e)
        }
    }

    private fun setupAutoSavingListeners() {
        var isInitialLoad = true

        ttsVoicePicker.setOnValueChangedListener { _, _, newVal ->
            val selectedVoice = availableVoices[newVal]
            saveSelectedVoice(selectedVoice)

            if (!isInitialLoad) {
                voiceTestJob?.cancel()
                voiceTestJob = lifecycleScope.launch {
                    delay(400L)
                    sc.stop()
                    playVoiceSample(selectedVoice)
                }
            }
        }

        ttsVoicePicker.post {
            isInitialLoad = false
        }

        switchShowThoughts.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_SHOW_THOUGHTS, isChecked).apply()
        }

        val profileWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                UserProfileManager(this@SettingsActivity).saveProfile(
                    editUserName.text?.toString().orEmpty(),
                    editUserEmail.text?.toString().orEmpty()
                )
            }
        }
        editUserName.addTextChangedListener(profileWatcher)
        editUserEmail.addTextChangedListener(profileWatcher)
    }

    private fun playVoiceSample(voice: TTSVoice) {
        lifecycleScope.launch {
            val cacheDir = File(cacheDir, "voice_samples")
            val voiceFile = File(cacheDir, "${voice.name}.wav")

            try {
                if (voiceFile.exists()) {
                    val audioData = voiceFile.readBytes()
                    sc.playAudioData(audioData)
                } else {
                    sc.testVoice(TEST_TEXT, voice)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e("SettingsActivity", "Error playing voice sample", e)
                    Toast.makeText(this@SettingsActivity, "Error playing voice", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cacheVoiceSamples() {
        if (!GoogleTts.isConfigured()) {
            Log.d("SettingsActivity", "Google TTS API key not configured; skipping remote voice sample caching.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir = File(cacheDir, "voice_samples")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            var downloadedCount = 0
            for (voice in availableVoices) {
                val voiceFile = File(cacheDir, "${voice.name}.wav")
                if (!voiceFile.exists()) {
                    try {
                        val audioData = GoogleTts.synthesize(TEST_TEXT, voice)
                        voiceFile.writeBytes(audioData)
                        downloadedCount++
                    } catch (e: Exception) {
                        Log.e("SettingsActivity", "Failed to cache voice ${voice.name}", e)
                    }
                }
            }
            if (downloadedCount > 0) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "$downloadedCount voice samples prepared.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadAllSettings() {
        val keyManager = PicovoiceKeyManager(this)
        editWakeWordKey.setText(keyManager.getUserProvidedKey() ?: "")

        editGeminiApiKey.setText(geminiKeyManager.getApiKey() ?: "")
        updateGeminiKeyStatus()

        val savedVoiceName = sharedPreferences.getString(KEY_SELECTED_VOICE, DEFAULT_VOICE.name)
        val savedVoice = availableVoices.find { it.name == savedVoiceName } ?: DEFAULT_VOICE
        ttsVoicePicker.value = availableVoices.indexOf(savedVoice)

        updateWakeWordButtonState()
        switchShowThoughts.isChecked = sharedPreferences.getBoolean(KEY_SHOW_THOUGHTS, false)
    }

    private fun updateGeminiKeyStatus() {
        if (geminiKeyManager.hasApiKey()) {
            textGeminiKeyStatus.text = "Status: Configured (${geminiKeyManager.getMaskedKey()}) • Model: gemini-3.8-flash"
            textGeminiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
        } else {
            textGeminiKeyStatus.text = "Status: Not configured • Model: gemini-3.8-flash"
            textGeminiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.text_gray))
        }
    }

    private fun saveSelectedVoice(voice: TTSVoice) {
        VoicePreferenceManager.saveSelectedVoice(this, voice)
    }

    private fun showPicovoiceKeyRequiredDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Picovoice Key Required")
            .setMessage("To enable wake word functionality, you need a Picovoice AccessKey. You can get a free key from the Picovoice Console.")
            .setPositiveButton("Get Key") { _, _ ->
                openExternalUrl("https://console.picovoice.ai/login")
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.white))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun updateWakeWordButtonState() {
        wakeWordManager.updateButtonState(wakeWordButton)
    }

    private fun showBatteryOptimizationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_optimization_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.learn_how)) { _, _ ->
                openExternalUrl("https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html#00")
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.white))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun showResetSettingsConfirmationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Reset Local Settings")
            .setMessage("Are you sure you want to reset your local settings, profile, and saved Gemini API key?")
            .setPositiveButton("Reset") { _, _ ->
                resetLocalSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()

        val white = ContextCompat.getColor(this, R.color.white)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(white)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(white)
    }

    private fun resetLocalSettings() {
        UserProfileManager(this).clearProfile()
        geminiKeyManager.clearApiKey()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun getContentLayoutId(): Int = R.layout.activity_settings

    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem = BaseNavigationActivity.NavItem.SETTINGS
}