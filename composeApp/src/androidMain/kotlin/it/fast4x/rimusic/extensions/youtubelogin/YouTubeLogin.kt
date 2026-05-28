package it.fast4x.rimusic.extensions.youtubelogin

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.kreate.android.Preferences
import app.kreate.android.R
import app.kreate.android.utils.innertube.CURRENT_LOCALE
import co.touchlab.kermit.Logger
import it.fast4x.rimusic.LocalPlayerAwareWindowInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.knighthat.innertube.Innertube
import me.knighthat.utils.Toaster

@OptIn(
    DelicateCoroutinesApi::class,
    ExperimentalMaterial3Api::class
)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLogin( onDone: () -> Unit ) {
    var webView: WebView? = null

    // This section is ripped from Metrolist - Full credit to their team
    // Small changes were made in order to make it work with Kreate
    // https://github.com/mostafaalagamy/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/ui/screens/LoginScreen.kt
    AndroidView(
        modifier = Modifier.windowInsetsPadding( LocalPlayerAwareWindowInsets.current )
                           .fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished( view: WebView, url: String? ) {
                        if ( url?.startsWith("https://music.youtube.com") == true ) {
                            // Save cookies immediately – this runs on the main thread
                            // inside onPageFinished, so no dispatch needed.
                            Preferences.YOUTUBE_COOKIES.value =
                                CookieManager.getInstance().getCookie( url )

                            // Inject JS to pull visitorData and dataSyncId from the
                            // YouTube Music page config.  Guard window.yt so that a
                            // missing object returns null instead of throwing a JS error
                            // that would silently drop the callback.
                            view.loadUrl(
                                "javascript:Android.onRetrieveVisitorData(" +
                                "(window.yt && window.yt.config_) ? window.yt.config_.VISITOR_DATA : null)"
                            )
                            view.loadUrl(
                                "javascript:Android.onRetrieveDataSyncId(" +
                                "(window.yt && window.yt.config_) ? window.yt.config_.DATASYNC_ID : null)"
                            )

                            // Delay accountInfo so the JS bridge callbacks have time to
                            // complete and post their Preference writes to the main thread.
                            // Log evidence: callbacks arrive ~6 s after onPageFinished;
                            // 1 500 ms is a safe margin for the write to land before the
                            // Innertube request is built (which reads the saved visitorData).
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(1_500L)
                                Innertube.accountInfo(CURRENT_LOCALE)
                                    .onSuccess {
                                        Preferences.YOUTUBE_ACCOUNT_NAME.value = it.name
                                        Preferences.YOUTUBE_ACCOUNT_EMAIL.value = it.email.orEmpty()
                                        Preferences.YOUTUBE_SELF_CHANNEL_HANDLE.value = it.channelHandle.orEmpty()
                                        Preferences.YOUTUBE_ACCOUNT_AVATAR.value = it.thumbnailUrl.firstOrNull()?.url.orEmpty()
                                    }
                                    .onFailure { err ->
                                        Logger.e( "", err, "YouTubeLogin" )
                                        Toaster.e( R.string.error_failed_to_acquire_account_info )
                                    }
                            }

                            onDone()
                        }
                    }

                    /**
                     * Handle SSL certificate errors gracefully on Android 7 (API 24-25).
                     *
                     * Android 7's system WebView ships an old TLS stack that can fail
                     * certificate validation for Google/YouTube domains (e.g. due to
                     * intermediate-CA changes). Without this override the WebView
                     * surfaces an unhandled exception that crashes the host process.
                     *
                     * We allow the connection to proceed ONLY for first-party Google /
                     * YouTube / googleapis domains so we do not introduce a blanket
                     * SSL bypass for arbitrary third-party sites.
                     */
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError
                    ) {
                        Logger.w("YouTubeLogin") {
                            "SSL error on ${error.url} primaryError=${error.primaryError}"
                        }
                        val host = runCatching {
                            Uri.parse(error.url).host.orEmpty()
                        }.getOrDefault("")
                        val isTrustedDomain =
                            host.endsWith("google.com")      ||
                            host.endsWith("youtube.com")     ||
                            host.endsWith("googleapis.com")  ||
                            host.endsWith("gstatic.com")     ||
                            host.endsWith("accounts.google.com")
                        if (isTrustedDomain) handler.proceed() else handler.cancel()
                    }
                }
                settings.apply {
                    javaScriptEnabled   = true
                    // Required: Google's OAuth page stores session data in localStorage.
                    // On Android 7, omitting domStorageEnabled causes the auth flow to
                    // silently fail or throw, crashing the host process.
                    domStorageEnabled   = true
                    databaseEnabled     = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    // Send a modern Chrome UA so Google serves the standard login flow
                    // instead of the legacy unsupported one.
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 7.0; Android SDK built for x86) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/128.0.0.0 Mobile Safari/537.36"
                }
                addJavascriptInterface(
                    object {
                        /**
                         * Called by JS on the WebView JavaBridge thread.
                         * Preferences.setValue requires the main thread, so we post
                         * via Handler(Looper.getMainLooper()) instead of writing
                         * directly, which would trigger the thread-safety guard and
                         * leave the value unset (causing accountInfo to use stale
                         * visitorData and the server to return logged_in=0).
                         */
                        @Suppress("unused")
                        @JavascriptInterface
                        fun onRetrieveVisitorData( newVisitorData: String? ) {
                            Handler(Looper.getMainLooper()).post {
                                Preferences.YOUTUBE_VISITOR_DATA.value = newVisitorData.orEmpty()
                            }
                        }

                        @Suppress("unused")
                        @JavascriptInterface
                        fun onRetrieveDataSyncId( newDataSyncId: String? ) {
                            Handler(Looper.getMainLooper()).post {
                                Preferences.YOUTUBE_SYNC_ID.value =
                                    newDataSyncId.orEmpty().substringBefore("||")
                            }
                        }
                    },
                    "Android"
                )
                webView = this
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        }
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}
