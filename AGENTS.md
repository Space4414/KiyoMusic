# AGENTS.md — KiyoMusic Agent Handoff Notes

This file documents fixes applied by AI agents, root-cause analyses, and constraints
future agents must respect. Read this before touching any playback or network code.

---

## Do NOT Touch

- `YouTubeLogin.kt` — YouTube authentication flow; hands off.
- `Store.kt` — Credential/session store; hands off.
- Any other YouTube login or auth files.

---

## Fix History

### Session 1 — Android 7 YouTube Playback (2026-05-31)

**Commit:** `04cb52b0dc79e34f373da37a8ec5f0ac322dc313`
**CI:** run `26717146006` — **success**

#### Root Causes Diagnosed

1. **"Radio Failed" on first tap (Android 7 TLS bug)**
   `validateStreamUrl()` used a Ktor HTTP client to HEAD the resolved stream URL.
   On Android 7 (API 24), the TLS stack occasionally throws `SSLHandshakeException`
   wrapping "Bad file descriptor" — a known transient OS-level race. The exception
   propagated upward and caused the entire stream-resolution path to fail.

2. **Instant 403 on second tap (IOS CDN rejects `cpn` parameter)**
   The resolver appended `&cpn=<token>` (a WEB-client-specific tracking parameter)
   to all stream URLs, including those resolved with `c=IOS` (iOS client).
   The YouTube IOS CDN accepts cpn-free requests (HEAD 200) but rejects
   requests that carry it (GET 403). This caused every second playback attempt
   to hard-fail.

#### Fixes Applied (`PlayerModule.kt`)

1. **`validateStreamUrl` retry on transient exception**
   Added a single retry whenever the HEAD request throws any `Exception`.
   Isolates Android 7 TLS transient failures without masking persistent errors
   (a second failure still propagates and falls through to the next URL candidate).

2. **Conditional `cpn` omission for IOS stream URLs**
   Before appending `&cpn=...`, the resolver now checks whether the URL already
   contains `c=IOS` (flag: `isIosUrl`). If true, `cpn` is omitted entirely.
   IOS CDN URLs are identified by the presence of "c=IOS" in the query string.

#### Prior Session Fixes (must remain)

- `VisitorIdFallbackInterceptor` in `NetworkModule.android.kt` (commit `0e11f80e`) —
  ensures a visitor-ID header is always present; Android 7 lacks the background
  initialisation path that newer versions rely on.
- Cookie guard in `HomeLibrary.kt` — prevents stale cookie injection on library load.

---

## Architecture Notes

- All Android-specific player wiring lives in
  `composeApp/src/androidMain/kotlin/app/kreate/di/PlayerModule.kt`.
- Network interceptors live in
  `composeApp/src/androidMain/kotlin/app/kreate/di/NetworkModule.android.kt`.
- Changes to player/network code on Android must not break the iOS/desktop
  source-set equivalents (`iosMain`, `desktopMain`).
- Never use shell metacharacters in commit messages when committing via the GitHub REST API.