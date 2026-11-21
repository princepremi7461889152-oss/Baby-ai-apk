# Baby — Personal AI Phone Operator for Android

**Baby** is an Android AI voice and phone-automation assistant that understands natural-language commands, reads on-screen context, asks clarifying questions when needed, and performs multi-step UI actions across your apps.

---

## Core Capabilities

- **Intelligent UI Automation:** Reads screen hierarchy and screenshots (XML mode or Screenshot mode) through Android's Accessibility Service to tap, type, scroll, open apps, and complete multi-step tasks.
- **Built-in Gemini AI (No API Key Needed):** Powered by **Firebase AI Logic** (`gemini-3.8-flash`) with **Firebase App Check** for conversational reasoning, action planning, clarification, and memory extraction.
- **Google Sign-In & Cloud Firestore Sync:** Uses Jetpack Credential Manager (`GetSignInWithGoogleOption`) for authentication and Cloud Firestore with per-user security rules for user profiles, task allowance, memories, moments, and inference/task history.
- **Voice, Floating Controls & Triggers:** Supports voice conversations, floating assistant controls, wake-word/notification triggers, and configurable TTS voices.
- **Custom Adaptive Identity:** Includes a custom adaptive & legacy launcher icon set (`Baby`) and edge-to-edge Material 3 + View-based screens.

---

## Tech Stack

| Component | Details |
| :--- | :--- |
| **Language** | Kotlin |
| **UI** | Jetpack Compose (Material 3) & Android Views |
| **Android Gradle Plugin** | `9.1.1` (`compileSdk 36.1`, `minSdk 24`, `targetSdk 36`) |
| **Gradle / JDK** | Gradle `9.3.1`, Java `17+` |
| **AI & Backend** | Firebase AI Logic (`gemini-3.8-flash`), Firebase App Check, Firebase Auth, Cloud Firestore |

---

## Architecture Overview

1. **Actuator (`ScreenInteractionService` & `AgentService`):** Connects to Android's Accessibility Service to inspect UI trees/screenshots and execute touch gestures, text input, and system navigation.
2. **Reasoning & Memory (`GeminiApi`, `ClarificationAgent`, `MemoryExtractor`):** Runs multimodal task planning, dialogue clarification, and conversation memory extraction through Firebase AI Logic.
3. **Auth & Persistence (`FirebaseProviders`, `FreemiumManager`):** Secures user data under `/users/{userId}` and `/users/{userId}/gemini_logs/{logId}` with hardened Firestore security rules (`firestore.rules`).

---

## Getting Started

### Prerequisites

- **JDK 17+** (Temurin recommended)
- **Android SDK** with `platforms;android-36.1` and `build-tools;36.0.0`
- **Gradle 9.3.1** (or Android Studio supporting AGP `9.1.1`)
- An Android device or emulator (`minSdk 24`, Android 11+ recommended for full gesture/screenshot flows)

### Build Debug APK Locally

```bash
gradle :app:assembleDebug --stacktrace --no-daemon
```

The generated APK is placed at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Optional Third-Party Keys (`.env`)

Core **Gemini AI** and **Firebase Auth / Firestore** work out of the box without any manual Gemini API key. If you want optional extra integrations, copy `.env.example` to `.env` and fill in only the keys you use:

```env
TAVILY_API=your_tavily_api_key_here
MEM0_API=your_mem0_api_key_here
PICOVOICE_ACCESS_KEY=your_picovoice_access_key_here
GOOGLE_TTS_API_KEY=your_google_tts_api_key_here
GCLOUD_GATEWAY_PICOVOICE_KEY=your_gcloud_gateway_picovoice_key_here
GCLOUD_GATEWAY_URL=your_gcloud_gateway_url_here
```

### First Launch Setup on Device

1. Sign in with your **Google** account on the onboarding screen.
2. Grant the requested permissions (**Accessibility Service**, **Microphone**, **Display Over Other Apps**, and **Notifications**) so **Baby** can hear commands and operate on-screen tasks.

---

## Automated GitHub Actions APK Builds

This repository includes `.github/workflows/build-apk.yml`, which runs automatically on every push to `main` (and via manual `workflow_dispatch`):

- Configures **Java 17 (Temurin)**, **Android SDK (`android-36.1` / `build-tools;36.0.0`)**, and **Gradle `9.3.1`**
- Generates a temporary runner-only `debug.keystore`
- Builds the APK with `gradle :app:assembleDebug --stacktrace --no-daemon`
- Uploads the backup artifact `app-debug-apk`
- Publishes `app-debug.apk` to the latest **GitHub Release** (`/releases/latest/download/app-debug.apk`)

---

## License

This project is distributed under the terms in [LICENSE](./LICENSE) (Personal & Educational Use).
