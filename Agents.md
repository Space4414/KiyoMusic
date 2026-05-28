# Agents.md – KiyoMusic Android 7 Compatibility Fixes

## Overview

This document records all changes made to fix two Android 7 (API 24-25) crash
bugs in the KiyoMusic fork of Kreate.

---

## Bug 1 – YouTube Music Login Failure on Android 7

### Root Causes (three layered issues found from crash + app logs)

#### 1a. Missing WebView settings (fixed in round 1)

| Missing setting | Effect on Android 7 |
|---|---|
| `domStorageEnabled = false` (default) | Google's OAuth page stores session tokens in `localStorage`. Without DOM storage the JS auth flow silently failed, causing the WebView to end up in an undefined state that crashed the host process. |
| No `onReceivedSslError` override | Android 7's system WebView ships an older TLS stack. Some intermediate CAs used by Google are not recognised, triggering an SSL error. Without an override the WebView surfaces an unhandled exception that crashes the app. |
| No explicit user-agent string | Google detects an ancient UA and serves a legacy login flow that the WebView JS bridge cannot parse, triggering a crash. |
| `loadUrl("javascript:…")` called on ambient receiver | Inside the anonymous `WebViewClient`, calling bare `loadUrl()` relied on implicit outer-scope receiver capture, which is fragile. |

#### 1b. JavaBridge thread violation (fixed in round 2)

`@JavascriptInterface` callbacks (`onRetrieveVisitorData`, `onRetrieveDataSyncId`) run
on WebView's **JavaBridge** background thread. `Preferences.setValue` requires the
**main thread**. Without correct dispatch, the Preference writes were rejected by
the thread-safety guard, so `YOUTUBE_VISITOR_DATA` and `YOUTUBE_SYNC_ID` were
never persisted.

**Evidence from log:**
```
Error: (Preferences) YouTubeVisitorData is being written on thread "JavaBridge"
Error: (Preferences) YouTubeSyncId is being written on thread "JavaBridge"
```

#### 1c. Race condition: `accountInfo` called before JS callbacks complete (fixed in round 2)

`onPageFinished` queued JS injections, then **immediately** launched the
`accountInfo` coroutine. Log timestamps confirm the JS callbacks arrive ~6 seconds
after `onPageFinished`. By then, `accountInfo` had already read stale/empty
`YOUTUBE_VISITOR_DATA` from Preferences, so YouTube's server returned
`"yt_li": "0"` (logged-in = false) causing:
```
IllegalArgumentException: missing activeAccountHeaderRenderer while parsing accountInfo
```

### Fixes Applied

**File:** `composeApp/src/androidMain/kotlin/it/fast4x/rimusic/extensions/youtubelogin/YouTubeLogin.kt`

**Round 1:**
- Added `settings.domStorageEnabled = true` and `databaseEnabled = true`
- Added modern Chrome 128 user-agent
- Added `onReceivedSslError` override (Google/YouTube/googleapis/gstatic domains allowed, all others cancelled)
- Changed `loadUrl("javascript:…")` to `view.loadUrl(…)` for explicit receiver

**Round 2:**
- Added `import android.os.Handler` and `import android.os.Looper`
- Changed both `@JavascriptInterface` callbacks to dispatch via
  `Handler(Looper.getMainLooper()).post { … }` so Preferences writes land on
  the main thread correctly
- Added `delay(1_500L)` before the `accountInfo` call so JS callbacks have
  time to complete their main-thread posts before the Innertube request is built
- Moved JS injections inside the `url.startsWith("https://music.youtube.com")` block
  so they only run on the final authenticated page, not on intermediate Google OAuth pages
- Added null-guard in JS expressions: `(window.yt && window.yt.config_) ? … : null`
  so a missing `window.yt` returns `null` instead of throwing a JS error that
  would silently drop the callback

---

## Bug 2 – Discord RPC Crash on Android 7

### Root Causes

#### 2a. Ktor 3.x `SecureRandom.getInstanceStrong()` – API 26+ method (primary crash)

**Evidence from crashlog (HUAWEI VNS-L31, Android 7.0, API 24):**
```
java.lang.NoSuchMethodError: No static method getInstanceStrong()Ljava/security/SecureRandom;
    in class Ljava/security/SecureRandom; or its super classes
    at io.ktor.util.NonceKt.lookupSecureRandom(Nonce.kt:137)
    at io.ktor.util.NonceKt$nonceGeneratorJob$1.invokeSuspend(Nonce.kt:50)
```

Ktor 3.5.0's nonce generator calls `SecureRandom.getInstanceStrong()` which was
added in Java 8 / Android API 26. Android 7 (API 24) does not have this method.
The nonce generator starts as a background coroutine the moment **any** Ktor
`HttpClient` is created, crashing the entire process immediately after Discord login.

#### 2b. TLS 1.0/1.1 negotiation on Android 7 (fixed in round 1)

Android 7's default SSL context can negotiate TLS 1.0/1.1 instead of 1.2.
Discord's WSS gateway requires TLS 1.2, so the handshake fails with
`SSLHandshakeException`. Fixed in round 1 with `SSLContext.setDefault(TLSv1.2)`.

### Fixes Applied

**Round 1 (TLS bootstrap):**

*File:* `composeApp/src/androidMain/kotlin/it/fast4x/rimusic/MainApplication.kt`
- Added `bootstrapTls12ForAndroid7()` called from `onCreate()` – sets JVM-wide
  default `SSLContext` to TLS 1.2 before any networking initialises.

*File:* `extensions/discord/src/androidMain/kotlin/me/knighthat/discord/DiscordImpl.kt`
- Added same TLS bootstrap in companion object as belt-and-suspenders guard.

**Round 2 (SecureRandom desugaring – primary crash fix):**

*File:* `gradle/libs.versions.toml`
- Changed `desugar_jdk_libs_nio` → `desugar_jdk_libs` (same version 2.1.5, no other changes)

`desugar_jdk_libs_nio` is a **NIO-only subset** of the Android desugaring library.
It does **not** backport `SecureRandom.getInstanceStrong()`.
The full `desugar_jdk_libs` includes `java.security` backports and makes
`SecureRandom.getInstanceStrong()` available on API 24+, fixing the crash.
The `coreLibraryDesugaring` directive in `composeApp/build.gradle.kts` already
references this entry (`libs.desugaring.nio`) so no Gradle build file change was needed.

---

## CI – Per-Architecture Alpha Release Workflow

### Problem

The fork had no active CI. Building a universal APK produced a ~37 MB artifact.
Each architecture-specific APK is ~14 MB.

### Fix

**File:** `.github/workflows/build-debug.yml`

Four parallel jobs (arm64, arm32, x86_64, x86) each build their own arch variant
and upload it as a workflow artifact. A fifth **release** job waits for all four,
then publishes them together as a GitHub pre-release with auto-tag:

```
alpha-YYYYMMDD-<short-sha>   e.g.  alpha-20260528-90fe7ac
```

| Gradle task | Output APK |
|---|---|
| assembleGithubArm64ProdDebug | KiyoMusic-arm64-v8a.apk (~14 MB) |
| assembleGithubArm32ProdDebug | KiyoMusic-armeabi-v7a.apk (~14 MB) |
| assembleGithubX86_64ProdDebug | KiyoMusic-x86_64.apk (~14 MB) |
| assembleGithubX86ProdDebug | KiyoMusic-x86.apk (~14 MB) |

**Android 7 users:** download `KiyoMusic-armeabi-v7a.apk`.

---

## What was NOT changed

- **The music player** – no changes to `PlayerServiceModern`, `ExoPlayer`,
  `Media3`, or any playback-related code.
- **kizzy submodule** – not modified.
- **Signing configuration** – the release/nightly signing flow is untouched.
