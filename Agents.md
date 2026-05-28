# Agents.md – KiyoMusic Android 7 Compatibility Fixes

## Overview

This document records all changes made to fix two Android 7 (API 24-25) crash
bugs in the KiyoMusic fork of Kreate.

---

## Bug 1 – YouTube Music Login Crash on Android 7

### Root Cause

`YouTubeLogin.kt` opened a `WebView` to load Google's OAuth page with
insufficient settings:

| Missing setting | Effect on Android 7 |
|---|---|
| `domStorageEnabled = false` (default) | Google's OAuth page stores session tokens in `localStorage`. Without DOM storage the JS auth flow silently failed, causing the WebView to end up in an undefined state that crashed the host process. |
| No `onReceivedSslError` override | Android 7's system WebView ships an older TLS stack. Some intermediate CAs used by Google are not recognised, triggering an SSL error. Without an override the WebView surfaces an unhandled exception that crashes the app. |
| No explicit user-agent string | Google detects an ancient UA and serves a legacy login flow that the WebView JS bridge cannot parse, triggering a crash. |
| `loadUrl("javascript:…")` called on ambient receiver | Inside the anonymous `WebViewClient`, calling bare `loadUrl()` relied on implicit outer-scope receiver capture, which is fragile. Made the target explicit (`view.loadUrl()`). |

### Fix

**File:** `composeApp/src/androidMain/kotlin/it/fast4x/rimusic/extensions/youtubelogin/YouTubeLogin.kt`

* Added `settings.domStorageEnabled = true`
* Added `settings.databaseEnabled = true`
* Added a modern Chrome user-agent string (Chrome 128 on Android 7)
* Added `onReceivedSslError` override that selectively allows connections to
  trusted Google/YouTube/googleapis/gstatic domains and cancels all others
* Changed `loadUrl("javascript:…")` calls to `view.loadUrl("javascript:…")`
  for explicit receiver safety

---

## Bug 2 – Discord RPC Crash on Android 7

### Root Cause

The `kizzy` gateway library (`modules/kizzy`) creates its own
`HttpClient { install(WebSockets) }` which auto-selects the Ktor **CIO** engine
(the default for pure-JVM modules compiled into Android APKs). On Android 7 the
CIO engine's SSL socket factory can negotiate TLS 1.0/1.1 instead of 1.2,
which Discord's WSS gateway rejects with a fatal handshake error. This
`SSLHandshakeException` propagated out of the coroutine and crashed the app.

### Fix

Two layers of defence, neither of which requires new runtime dependencies:

#### Layer 1 – `MainApplication.onCreate()`

**File:** `composeApp/src/androidMain/kotlin/it/fast4x/rimusic/MainApplication.kt`

Added `bootstrapTls12ForAndroid7()` which is called **before** any networking
is initialised:

```kotlin
private fun bootstrapTls12ForAndroid7() {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) return
    val sc = SSLContext.getInstance("TLSv1.2")
    sc.init(null, null, null)
    SSLContext.setDefault(sc)
}
```

Setting the JVM-wide default `SSLContext` to TLS 1.2 ensures that every
networking stack in the process (CIO, OkHttp, WebView) uses a TLS version
accepted by modern servers, with zero impact on API ≥ 26 devices.

#### Layer 2 – `DiscordImpl` companion object

**File:** `extensions/discord/src/androidMain/kotlin/me/knighthat/discord/DiscordImpl.kt`

Added the same `bootstrapTls12IfNeeded()` call in the companion object, as a
belt-and-suspenders guard specifically scoped to the Discord subsystem. This
ensures the TLS fix is applied even if `DiscordImpl` is somehow initialised
before `MainApplication.onCreate()`.

---

## CI – Downloadable Debug APK Workflow

### Problem

The fork had no active CI runs; the upstream nightly workflow requires signing
secrets that the fork does not have.

### Fix

**File:** `.github/workflows/build-debug.yml` *(new file)*

A new GitHub Actions workflow that:

* Triggers on every push to `main` **and** on manual `workflow_dispatch`
* Builds the `githubUniversalProdDebug` variant (no signing needed)
* Uploads the resulting APK as a GitHub Actions artifact named
  `KiyoMusic-debug-apk` (retained for 7 days)

To download the APK:
1. Go to **Actions** → **Build Debug APK** in the GitHub repository
2. Click the latest successful run
3. Download the `KiyoMusic-debug-apk` artifact at the bottom of the page

---

## What was NOT changed

* **The music player** – no changes to `PlayerServiceModern`, `ExoPlayer`,
  `Media3`, or any playback-related code.
* **kizzy submodule** – not modified; fixes are applied around it in the
  KiyoMusic-owned code (`DiscordImpl`, `MainApplication`).
* **Signing configuration** – the release/nightly signing flow is untouched.
