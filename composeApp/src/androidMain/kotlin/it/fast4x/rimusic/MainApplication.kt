package it.fast4x.rimusic

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.getSystemService
import androidx.lifecycle.ProcessLifecycleOwner
import app.kreate.android.Preferences
import app.kreate.android.drawable.AppIcon
import app.kreate.android.service.innertube.InnertubeProvider
import app.kreate.android.utils.ConnectivityUtils
import app.kreate.android.utils.CrashHandler
import app.kreate.di.THUMBNAIL_SIZE
import app.kreate.di.initKoin
import app.kreate.logging.CoilLogger
import app.kreate.logging.KoinBufferedLogger
import app.kreate.logging.setupLogging
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import it.fast4x.rimusic.utils.AppLifecycleTracker
import kotlinx.coroutines.Dispatchers
import me.knighthat.innertube.Innertube
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import javax.net.ssl.SSLContext


class MainApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()

        // ── Android 7 (API 24-25) TLS compatibility ──────────────────────────
        // Android 7's system TLS stack may default to TLS 1.0/1.1 for some
        // server configurations.  Modern services (Discord gateway, Google OAuth,
        // YouTube) require TLS 1.2.  Setting an explicit TLS 1.2 SSLContext as
        // the JVM default fixes WebView SSL errors AND Ktor/OkHttp handshake
        // failures – both of which crashed the app on Android 7.
        // This is a no-op on API >= 26 where TLS 1.2 is already the minimum.
        bootstrapTls12ForAndroid7()

        Thread.setDefaultUncaughtExceptionHandler( CrashHandler(this) )

        val koinLogger = KoinBufferedLogger()
        initKoin {
            logger( koinLogger )

            androidContext( this@MainApplication )
        }

        setupLogging( koinLogger )

        Innertube.setProvider( InnertubeProvider() )

        // Register network callback
        getSystemService<ConnectivityManager>()?.run {
            val networkRequest: NetworkRequest = NetworkRequest.Builder()
                                                               .addCapability( NetworkCapabilities.NET_CAPABILITY_INTERNET )
                                                               .build()
            registerNetworkCallback( networkRequest, ConnectivityUtils )
        }
        // Register app lifecycle tracker
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleTracker)
    }

    override fun onTerminate() {
        Preferences.unload()

        super.onTerminate()
    }

    override fun newImageLoader( context: PlatformContext ): ImageLoader {
        val client: HttpClient by inject()
        val diskCache: DiskCache by inject()
        val memoryCache: MemoryCache by inject()
        val appIcon = AppIcon.bitmap( context, THUMBNAIL_SIZE ).asImage()

        return ImageLoader.Builder(context)
                          .logger( CoilLogger() )
                          .coroutineContext( Dispatchers.IO )
                          .decoderCoroutineContext( Dispatchers.Default )
                          .crossfade( true )
                          .error( appIcon )
                          .memoryCache( memoryCache )
                          .diskCache {
                              if( diskCache.maxSize > 1 )
                                  diskCache
                              else
                                  null
                          }
                          .components {
                              add(
                                  KtorNetworkFetcherFactory(client)
                              )
                          }
                          .build()
    }

    /**
     * On Android 7 (API 24-25) explicitly set TLS 1.2 as the default
     * SSLContext so that every networking stack in the process (WebView,
     * Ktor CIO, OkHttp) uses a TLS version accepted by modern servers.
     */
    private fun bootstrapTls12ForAndroid7() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) return
        try {
            val sc = SSLContext.getInstance("TLSv1.2")
            sc.init(null, null, null)
            SSLContext.setDefault(sc)
            Logger.i("MainApplication") { "TLS 1.2 bootstrapped (Android ${Build.VERSION.SDK_INT})" }
        } catch (e: Exception) {
            Logger.w("MainApplication") { "Could not bootstrap TLS 1.2: ${e.message}" }
        }
    }
}
