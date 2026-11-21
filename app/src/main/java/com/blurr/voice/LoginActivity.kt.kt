package com.blurr.voice

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.utilities.FirebaseProviders
import com.blurr.voice.utilities.FreemiumManager
import com.blurr.voice.utilities.OnboardingManager
import com.blurr.voice.utilities.UserProfileManager
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.material.button.MaterialButton
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    private var firebaseAuth: FirebaseAuth? = null
    private lateinit var credentialManager: CredentialManager
    private lateinit var signInButton: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseProviders.configureAppCheck(this, intent)
        setContentView(R.layout.activity_onboarding)

        signInButton = findViewById(R.id.googleSignInButton)
        progressBar = findViewById(R.id.progressBar)
        loadingText = findViewById(R.id.loadingText)

        credentialManager = CredentialManager.create(this)

        if (FirebaseApp.getApps(this).isNotEmpty()) {
            firebaseAuth = Firebase.auth
        } else {
            Toast.makeText(
                this,
                "Firebase is not configured yet.",
                Toast.LENGTH_LONG
            ).show()
        }

        signInButton.setOnClickListener {
            signInWithGoogle()
        }
    }

    private fun showProgress(show: Boolean, text: String = "Signing you in...") {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        loadingText.visibility = if (show) View.VISIBLE else View.GONE
        loadingText.text = text
        signInButton.isEnabled = !show
    }

    private fun signInWithGoogle() {
        val auth = firebaseAuth
        if (auth == null) {
            Toast.makeText(this, "Firebase is not configured.", Toast.LENGTH_SHORT).show()
            return
        }

        val webClientId = runCatching { getString(R.string.default_web_client_id) }.getOrNull()
        if (webClientId.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Google Sign-In is not configured (missing default_web_client_id).",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        lifecycleScope.launch {
            showProgress(true, "Signing in with Google...")
            try {
                val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInWithGoogleOption)
                    .build()

                val result = credentialManager.getCredential(
                    request = request,
                    context = this@LoginActivity
                )

                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCredential =
                        GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                    auth.signInWithCredential(firebaseCredential).await()
                    completePostAuthFlow()
                } else {
                    Log.w(TAG, "Unexpected credential type: ${credential::class.java.simpleName}")
                    showProgress(false)
                    Toast.makeText(
                        this@LoginActivity,
                        "Unsupported Google credential returned.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: GetCredentialCancellationException) {
                Log.d(TAG, "User cancelled Google Sign-In")
                showProgress(false)
            } catch (e: NoCredentialException) {
                Log.w(TAG, "No Google credentials available on device", e)
                showProgress(false)
                Toast.makeText(
                    this@LoginActivity,
                    "No Google account found on this device. Please add a Google account in device settings.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(TAG, "Failed to parse Google ID token", e)
                showProgress(false)
                Toast.makeText(
                    this@LoginActivity,
                    "Google Sign-In failed to verify your token.",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager sign-in error", e)
                showProgress(false)
                Toast.makeText(
                    this@LoginActivity,
                    "Google Sign-In failed: ${e.errorMessage ?: e.localizedMessage ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase sign-in failed", e)
                showProgress(false)
                Toast.makeText(
                    this@LoginActivity,
                    "Authentication failed: ${e.localizedMessage ?: "Please try again"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun completePostAuthFlow() {
        val user = firebaseAuth?.currentUser
        val profileManager = UserProfileManager(this@LoginActivity)
        val name = user?.displayName?.takeIf { it.isNotBlank() } ?: "User"
        val email = user?.email?.takeIf { it.isNotBlank() } ?: ""
        profileManager.saveProfile(name, email)

        val freemiumManager = FreemiumManager()
        freemiumManager.provisionUserIfNeeded()

        val onboardingManager = OnboardingManager(this@LoginActivity)
        showProgress(false)
        if (onboardingManager.isOnboardingCompleted()) {
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        } else {
            startActivity(Intent(this@LoginActivity, OnboardingPermissionsActivity::class.java))
        }
        finish()
    }
}
