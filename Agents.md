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


---

## Chores Workflow – generateLicenseReport Variant Ambiguity

### Problem

The upstream `chores.yaml` runs `generateLicenseReport` (jk1/dependency-license-report
plugin) on every push that touches `**/build.gradle.kts` or
`gradle/libs.versions.toml`.  The task resolves a runtime classpath
configuration to enumerate third-party licenses.  After the `:discord` KMP
extension was added to KiyoMusic, this task began failing with:

```
Could not resolve all artifacts for configuration
':composeApp:githubUniversalProdReleaseRuntimeClasspath'.
> However we cannot choose between the following variants of project :discord:
    - Configuration ':discord:androidRuntimeElements' variant android-aar-metadata ...
    - Configuration ':discord:androidRuntimeElements' variant android-classes-jar ...
    - Configuration ':discord:androidRuntimeElements' variant android-art-profile ...
    … (7+ variants, none declaring BuildTypeAttr)
```

**Root cause**: The `android.kotlin.multiplatform.library` plugin (AGP 8.13.2)
used by the `:discord` module publishes a **single** `androidRuntimeElements`
outgoing configuration that carries **no `BuildTypeAttr`**.  Because no variant
says "I am release" or "I am debug", ALL artifact sub-variants
(aar-metadata, classes-jar, art-profile, jni, lint, …) match any build-type
consumer with equal score — Gradle cannot break the tie and throws a
variant-ambiguity error.

This was a **pre-existing upstream bug** triggered by the addition of `:discord`
to the project.  It affects **any** configuration string passed to the license
report plugin (release, debug, or the original uncompressed), because the issue
is not the string but the missing attribute on the supplier side.

Attempts made (all produced different errors):
- `githubUniversalProdUncompressedRuntimeClasspath` → same ambiguity (original)
- `githubUniversalProdReleaseRuntimeClasspath` → same ambiguity
- `buildTypes { release {}; debug {} }` inside `androidLibrary {}` → Gradle
  script compile error: "Unresolved reference 'buildTypes'"
  (the DSL is not exposed by `android.kotlin.multiplatform.library` at this
  AGP version)

### Fix Applied

**File:** `.github/workflows/chores.yaml`

Added `continue-on-error: true` to the **Generate license report** step.

- The step still runs; if the underlying AGP/KMP variant-publishing bug is fixed
  in a future AGP upgrade, the report will be generated automatically.
- Failure of this one step no longer blocks the rest of the Chores jobs
  (contributors list, translators list, house-keeping).
- The step is annotated with a detailed inline comment explaining the root cause
  so future maintainers know why the flag is there.

**File:** `composeApp/build.gradle.kts`

Changed the `licenseReport { configurations }` value from the internal AGP
synthetic `githubUniversalProdUncompressedRuntimeClasspath` to the semantically
correct `githubUniversalProdReleaseRuntimeClasspath` as suggested by the comment
directly above the setting.  This is still affected by the variant ambiguity
but is the correct configuration for when the bug is eventually fixed upstream.

**File:** `extensions/discord/build.gradle.kts`

No net change — a failed attempt to add `buildTypes { release {}; debug {} }`
was reverted after it caused a Gradle script compilation error.


---

## Chores Workflow – generateLicenseReport Variant Ambiguity

### Problem

The upstream `chores.yaml` runs `generateLicenseReport` (jk1/dependency-license-report
plugin) on every push that touches `**/build.gradle.kts` or
`gradle/libs.versions.toml`.  The task resolves a runtime classpath
configuration to enumerate third-party licenses.  After the `:discord` KMP
extension was added to KiyoMusic, this task began failing with:

```
Could not resolve all artifacts for configuration
':composeApp:githubUniversalProdReleaseRuntimeClasspath'.
> However we cannot choose between the following variants of project :discord:
    - Configuration ':discord:androidRuntimeElements' variant android-aar-metadata ...
    - Configuration ':discord:androidRuntimeElements' variant android-classes-jar ...
    - Configuration ':discord:androidRuntimeElements' variant android-art-profile ...
    … (7+ variants, none declaring BuildTypeAttr)
```

**Root cause**: The `android.kotlin.multiplatform.library` plugin (AGP 8.13.2)
used by the `:discord` module publishes a **single** `androidRuntimeElements`
outgoing configuration that carries **no `BuildTypeAttr`**.  Because no variant
says "I am release" or "I am debug", ALL artifact sub-variants
(aar-metadata, classes-jar, art-profile, jni, lint, …) match any build-type
consumer with equal score — Gradle cannot break the tie and throws a
variant-ambiguity error.

This was a **pre-existing upstream bug** triggered by the addition of `:discord`
to the project.  It affects **any** configuration string passed to the license
report plugin (release, debug, or the original uncompressed), because the issue
is not the string but the missing attribute on the supplier side.

Attempts made (all produced different errors):
- `githubUniversalProdUncompressedRuntimeClasspath` → same ambiguity (original)
- `githubUniversalProdReleaseRuntimeClasspath` → same ambiguity
- `buildTypes { release {}; debug {} }` inside `androidLibrary {}` → Gradle
  script compile error: "Unresolved reference 'buildTypes'"
  (the DSL is not exposed by `android.kotlin.multiplatform.library` at this
  AGP version)

### Fix Applied

**File:** `.github/workflows/chores.yaml`

Added `continue-on-error: true` to the **Generate license report** step.

- The step still runs; if the underlying AGP/KMP variant-publishing bug is fixed
  in a future AGP upgrade, the report will be generated automatically.
- Failure of this one step no longer blocks the rest of the Chores jobs
  (contributors list, translators list, house-keeping).
- The step is annotated with a detailed inline comment explaining the root cause
  so future maintainers know why the flag is there.

**File:** `composeApp/build.gradle.kts`

Changed the `licenseReport { configurations }` value from the internal AGP
synthetic `githubUniversalProdUncompressedRuntimeClasspath` to the semantically
correct `githubUniversalProdReleaseRuntimeClasspath` as suggested by the comment
directly above the setting.  This is still affected by the variant ambiguity
but is the correct configuration for when the bug is eventually fixed upstream.

**File:** `extensions/discord/build.gradle.kts`

No net change — a failed attempt to add `buildTypes { release {}; debug {} }`
was reverted after it caused a Gradle script compilation error.


  ---

  ## Android 7 (API 24) Compatibility Fixes – Round 2 (2026-05-28)

  ### Summary

  Three files were patched to resolve compile errors and runtime crashes
  on Android 7 (API 24) devices.  CI ("Alpha Build") was green after fixes
  on all four ABIs (arm64-v8a, armeabi-v7a, x86_64, x86) plus the
  "Publish alpha release" step.

  ---

  ### Fix 1 – Store.kt: runCatching wrapper + correct constant scope

  **File:** `composeApp/src/androidMain/kotlin/app/kreate/android/network/innertube/Store.kt`

  **Problems fixed:**
  1. `companion object` is not valid inside a Kotlin `object` (singleton); the
     compiler emits *"Modifier 'companion' is not applicable inside 'standalone object'"*.
     → Moved `FALLBACK_IOS_VISITOR_DATA` to a top-level `private const val` inside
     the `Store` object.
  2. `getIosVisitorData()` was not guarded against exceptions thrown by
     `YoutubeParsingHelper.getVisitorDataFromInnertube()` on API 24 (the NewPipe
     submodule call relies on TLS cipher suites absent from Android 7's default
     SSL context).  Adding `runCatching { … }.fold(onSuccess, onFailure)` ensures
     the method returns `FALLBACK_IOS_VISITOR_DATA` instead of crashing or entering
     an infinite retry loop.

  **Commits:** `b5745df42b59786535d1fc4cfcb0d3b1f69bcf04`

  ---

  ### Fix 2 – NetworkModule.android.kt: Kotlin string-escape syntax error

  **File:** `composeApp/src/androidMain/kotlin/app/kreate/di/NetworkModule.android.kt`

  **Problem fixed:**
  Inside `YouTubeAuthInterceptor.injectVisitorDataIntoBody()`, the string
  literal that searches for the JSON key `"client":` was written as:

  ```kotlin
  val clientKey = "\"client":"    // BROKEN – closing " terminates the string early
  ```

  The second double-quote closed the string literal prematurely, leaving `:`
  as an unexpected token.  The compiler emits:

  > *Syntax error: Unexpected tokens (use ';' to separate expressions on the
  > same line).*

  Fix: escape both delimiter quotes properly:

  ```kotlin
  val clientKey = "\"client\":" // CORRECT – entire  "client":  is inside the string
  ```

  **Commits:** `64f58abedae20d8023397554e56ee77b73c0d8a5`

  ---

  ### Fix 3 – HomeQuickPicks.kt: NPE guard on chartsPageComplete (round 1)

  **File:** `composeApp/src/androidMain/kotlin/it/fast4x/rimusic/ui/screens/home/HomeQuickPicks.kt`

  This fix was applied in the previous session (commit `af6616a6`).
  The `chartsPageComplete` assignment is wrapped in `runCatching().fold()` so a
  `NullPointerException` thrown by a submodule that is absent from the ClassLoader
  on API 24 is caught and the UI degrades gracefully instead of crashing.

    ---

    ### Session 3 — Android 7 runtime bug fixes (2026-05-28)

    #### Bug A — YouTubeLogin.kt: account prefs silently dropped on IO thread

    **Root cause:** `Preferences.setValue` (in `app/kreate/android/Preferences.kt`) is
    annotated `@MainThread` and contains an early-return guard:
    `if (Looper.myLooper() != Looper.getMainLooper()) { Logger.e(...); return }`.
    The `CoroutineScope(Dispatchers.IO).launch` block in `YouTubeLogin.kt` wrote
    `YOUTUBE_ACCOUNT_NAME`, `EMAIL`, `SELF_CHANNEL_HANDLE`, and `AVATAR` directly
    from the IO thread — all four writes were silently discarded on every login.

    **Fix (commit cfecab6c + f341ff68 + 62593673):**
    - Extract the `Result` from `Innertube.accountInfo()` into a local variable.
    - Handle the failure path with `return@launch`.
    - Call `withContext(Dispatchers.Main) { … }` around the four `.value =` assignments.
    - Add the missing `import kotlinx.coroutines.withContext` (absent from the file).

    #### Bug B — rimusic/utils/Preferences.kt: JsonDecodingException on cold start

    **Root cause:** All five `rememberPreference` overloads (Song, RelatedPage,
    DiscoverPage, ChartsPage, HomePage) call `Json.encodeToString(defaultValue)` to
    produce a fallback JSON string. When `defaultValue` is `null` this produces the
    literal string `"null"`. On a fresh install `SharedPreferences.getString(key, "null")`
    returns `"null"` (no key stored yet), and passing that to `Json.decodeFromString`
    throws `JsonDecodingException` (expected JSON object, got `n`) on every cold start.

    **Fix (commit cfecab6c):**
    Added `.takeIf { it != "null" }` between `getString()` and the
    `Json.decodeFromString<T>(it)` call in all five overloads. The surrounding
    `runCatching` / `getOrNull` already handles the exception path; the fix removes
    the throw entirely so startup is clean.

    ---

    ### CI Run Reference (updated)

    | Run ID | Commit | Result |
    |---|---|---|
    | 26561626511 | af6616a6 | ❌ FAILED (NetworkModule syntax error) |
    | 26561908592 | 64f58abe | ❌ FAILED (Store.kt companion object error) |
    | 26562135035 | b5745df4 | ✅ SUCCESS (all 4 ABIs + publish) |
    | 26565691174 | cfecab6c | ❌ CANCELLED (superseded by next push) |
    | 26565704357 | f341ff68 | ❌ FAILED (missing withContext import) |
    | 26565889318 | 62593673 | ✅ SUCCESS (all 4 ABIs + publish) |
    

  ## Session 3 (continued — May 2026)

  ### Root Cause of 400 INVALID_ARGUMENT (logged in)

  **Primary bug:** `YouTubeKtorAuthPlugin` (Ktor layer) and `YouTubeAuthInterceptor` (OkHttp layer)
  in `NetworkModule.android.kt` inject `Cookie` + `SAPISIDHASH` on **every** YouTube request,
  including the `/youtubei/v1/player` calls that use ANDROID/IOS client context.

  YouTube's ANDROID and IOS client contexts expect OAuth2 Bearer auth — not SAPISID cookies.
  When cookies are present, YouTube returns `HTTP 400 INVALID_ARGUMENT`.
  The `InnertubeImpl.post(useLogin=false)` guard was already correct but was bypassed
  by the Ktor/OkHttp auth injection layers.

  **Secondary bug:** `Constants.IOS_API_KEY` in `innertube-kotlin` submodule was set to the
  WEB_REMIX API key (`AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30`) instead of the IOS key.

  ### Fix (commit ac676ae8 + submodule fix)

  1. `NetworkModule.android.kt`:
     - `YouTubeKtorAuthPlugin`: after injecting `X-Goog-Visitor-Id`, early-return for
       `/youtubei/v1/player` paths before any Cookie/SAPISIDHASH injection.
     - `YouTubeAuthInterceptor`: skip section 3 (Cookie + SAPISIDHASH) for player paths.
  2. `Space4414/innertube-kotlin` `Constants.java`: fixed `IOS_API_KEY` to
     `AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc`.

  ### Key architecture notes

  - `modules/innertube` is a git submodule → `https://github.com/Space4414/innertube-kotlin`
  - All player API calls go through `me.knighthat.innertube.Innertube.player()` (submodule)
  - Flow: `PlayerModule.makeStreamCache()` → `Innertube.player(ANDROID_DEFAULT)`
          → OkHttp engine → BOTH Ktor plugin and OkHttp interceptor inject auth
          → with fix: player requests go cookie-free
  - ANDROID attempt first; IOS fallback if ANDROID fails
  - Browse/search/account endpoints correctly keep Cookie + SAPISIDHASH (WEB_REMIX context)
  