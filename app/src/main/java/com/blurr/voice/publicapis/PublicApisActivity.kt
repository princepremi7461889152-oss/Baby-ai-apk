package com.blurr.voice.publicapis

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.R
import com.blurr.voice.SettingsActivity
import com.blurr.voice.api.GeminiApi
import com.blurr.voice.api.TextPart
import com.blurr.voice.utilities.GeminiKeyManager
import kotlinx.coroutines.launch

class PublicApisActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvCount: TextView
    private lateinit var btnSync: Button
    private lateinit var editSearch: EditText
    private lateinit var btnPickCategory: Button
    private lateinit var btnToggleNoAuth: Button
    private lateinit var btnToggleHttps: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: PublicApisAdapter

    private var allEntries: List<PublicApiEntry> = emptyList()
    private var selectedCategory: String = "All"
    private var noAuthOnly: Boolean = false
    private var httpsOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_public_apis)

        bindViews()
        setupListeners()
        loadInitialCatalog()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBackPublicApis)
        tvCount = findViewById(R.id.tvPublicApisCount)
        btnSync = findViewById(R.id.btnSyncPublicApis)
        editSearch = findViewById(R.id.editSearchPublicApis)
        btnPickCategory = findViewById(R.id.btnPickCategory)
        btnToggleNoAuth = findViewById(R.id.btnToggleNoAuth)
        btnToggleHttps = findViewById(R.id.btnToggleHttps)
        progressBar = findViewById(R.id.progressPublicApis)
        recyclerView = findViewById(R.id.recyclerPublicApis)
        tvEmpty = findViewById(R.id.tvEmptyPublicApis)

        adapter = PublicApisAdapter(emptyList()) { entry ->
            showApiDetailDialog(entry)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })

        btnPickCategory.setOnClickListener {
            showCategoryPickerDialog()
        }

        btnToggleNoAuth.setOnClickListener {
            noAuthOnly = !noAuthOnly
            updateFilterChipColors()
            applyFilters()
        }

        btnToggleHttps.setOnClickListener {
            httpsOnly = !httpsOnly
            updateFilterChipColors()
            applyFilters()
        }

        btnSync.setOnClickListener {
            syncFromGitHub()
        }
    }

    private fun loadInitialCatalog() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = PublicApisRepository.loadCatalog(this@PublicApisActivity)
            if (isFinishing || isDestroyed) return@launch
            allEntries = loaded
            progressBar.visibility = View.GONE
            updateFilterChipColors()
            applyFilters()
        }
    }

    private fun syncFromGitHub() {
        btnSync.isEnabled = false
        btnSync.text = "Syncing..."
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = PublicApisRepository.syncFromGitHub(this@PublicApisActivity)
            if (isFinishing || isDestroyed) return@launch
            progressBar.visibility = View.GONE
            btnSync.isEnabled = true
            btnSync.text = "Sync GitHub"

            result.onSuccess { synced ->
                allEntries = synced
                applyFilters()
                Toast.makeText(
                    this@PublicApisActivity,
                    "Synced ${synced.size} APIs from public-apis/public-apis",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@PublicApisActivity,
                    "Sync failed: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateFilterChipColors() {
        val activeColor = ContextCompat.getColor(this, R.color.accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.white)
        btnToggleNoAuth.setTextColor(if (noAuthOnly) activeColor else inactiveColor)
        btnToggleHttps.setTextColor(if (httpsOnly) activeColor else inactiveColor)
    }

    private fun applyFilters() {
        val filtered = PublicApisRepository.filter(
            entries = allEntries,
            query = editSearch.text?.toString().orEmpty(),
            category = selectedCategory,
            noAuthOnly = noAuthOnly,
            httpsOnly = httpsOnly
        )
        adapter.updateItems(filtered)
        tvCount.text = "${filtered.size} of ${allEntries.size} APIs • public-apis"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showCategoryPickerDialog() {
        val categories = listOf("All") + allEntries.map { it.category }.distinct().sorted()
        val array = categories.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Category")
            .setItems(array) { _, which ->
                selectedCategory = array[which]
                btnPickCategory.text = "Category: $selectedCategory"
                applyFilters()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showApiDetailDialog(entry: PublicApiEntry) {
        val authText = if (entry.auth.equals("No", ignoreCase = true)) "None" else entry.auth
        val details = buildString {
            appendLine(entry.description)
            appendLine()
            appendLine("Category: ${entry.category}")
            appendLine("Auth: $authText")
            appendLine("HTTPS: ${if (entry.https) "Yes" else "No"}")
            appendLine("CORS: ${entry.cors}")
            appendLine("URL: ${entry.link}")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.api)
            .setMessage(details.trim())
            .setPositiveButton("Open Docs") { _, _ ->
                openUrl(entry.link)
            }
            .setNeutralButton("Ask Gemini 3.8 Flash") { _, _ ->
                askGeminiAboutApi(entry)
            }
            .setNegativeButton("Test GET") { _, _ ->
                probeApiEndpoint(entry)
            }
            .show()

        val white = ContextCompat.getColor(this, R.color.white)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(white)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(white)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(white)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found to open $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun probeApiEndpoint(entry: PublicApiEntry) {
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "Testing ${entry.api}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = PublicApisRepository.probeEndpoint(entry.link)
            if (isFinishing || isDestroyed) return@launch
            progressBar.visibility = View.GONE
            val msg = buildString {
                appendLine("HTTP Status: ${if (result.statusCode > 0) result.statusCode else "Error"}")
                appendLine("Content-Type: ${result.contentType}")
                appendLine()
                append(result.bodyPreview)
            }
            val dialog = AlertDialog.Builder(this@PublicApisActivity)
                .setTitle("Endpoint Response: ${entry.api}")
                .setMessage(msg)
                .setPositiveButton("Close", null)
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                ContextCompat.getColor(this@PublicApisActivity, R.color.white)
            )
        }
    }

    private fun askGeminiAboutApi(entry: PublicApiEntry) {
        if (!GeminiKeyManager(this).hasApiKey()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Gemini API Key Required")
                .setMessage("Add your Gemini API key in Settings to generate Gemini 3.8 Flash integration guides for ${entry.api}.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
            val white = ContextCompat.getColor(this, R.color.white)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(white)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(white)
            return
        }

        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "Asking Gemini 3.8 Flash about ${entry.api}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val prompt = """
                You are an Android developer assistant. Briefly explain how to use the "${entry.api}" public API in Kotlin using OkHttp.
                Details:
                - Description: ${entry.description}
                - Docs/Endpoint URL: ${entry.link}
                - Auth: ${entry.auth}
                - HTTPS: ${entry.https}
                Keep it concise (under 180 words) with one small Kotlin snippet.
            """.trimIndent()

            val reply = GeminiApi.generateContent(
                chat = listOf("user" to listOf(TextPart(prompt))),
                context = applicationContext
            ) ?: "Could not reach Gemini 3.8 Flash. Please check your network and API key in Settings."

            if (isFinishing || isDestroyed) return@launch
            progressBar.visibility = View.GONE
            val dialog = AlertDialog.Builder(this@PublicApisActivity)
                .setTitle("Gemini 3.8 Flash • ${entry.api}")
                .setMessage(reply)
                .setPositiveButton("Close", null)
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                ContextCompat.getColor(this@PublicApisActivity, R.color.white)
            )
        }
    }
}
