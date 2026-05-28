package app.kreate.di

import app.kreate.android.BuildConfig
import app.kreate.android.Preferences
import app.kreate.android.R
import app.kreate.android.enums.DohServer
import app.kreate.android.service.NewPipeDownloaderImpl
import app.kreate.logging.OkHttpLogger
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import me.knighthat.innertube.Constants
import me.knighthat.utils.Toaster
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import org.koin.core.module.Module
import org.koin.dsl.module
import org.schabi.newpipe.extractor.NewPipe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit


private const val LOGGING_TAG = "Networking"

@ExperimentalSerializationApi
private val JSON: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false

    // Exclude ("type": "me.knighthat.innertube.*")
    // since there's no intention to deserialize json
    // string back to the class
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
}

private fun verifyProxy( proxy: Proxy, url: String = "https://httpbin.org/ip" ): Boolean =
    runCatching {
        OkHttpClient.Builder()
                    .proxy( proxy )
                    .connectTimeout( 3, TimeUnit.SECONDS )
                    .callTimeout( 5, TimeUnit.SECONDS )
                    .build()
                    .newCall(
                        Request.Builder()
                               .head()
                               .url( url )
                               .build()
                    )
                    .execute()
                    .use( Response::isSuccessful )
    }.onFailure { err ->
        Logger.e( err, LOGGING_TAG ) { "Failed to connect to $url via proxy $proxy" }
        Toaster.w( R.string.error_failed_to_verify_proxy )
    }.getOrDefault( false )

private fun verifyDoH( resolver: DnsOverHttps, addresses: List<InetAddress>, domain: String = "google.com" ): Boolean =
    runCatching {
        val results = resolver.lookup( domain )
        Logger.d( tag = LOGGING_TAG ) { "Resolved $domain to ${results.size} addresses" }

        return results.isNotEmpty()
    }.onFailure { err ->
        // Failed to resolve "google.com" with [/1.1.1.1, /1.0.0.1, /2606:4700:4700::1111, /2606:4700:4700::1001]
        Logger.e( err, LOGGING_TAG ) { "Failed to resolve \"$domain\" with $addresses" }
        Toaster.w( R.string.error_failed_to_verify_doh )
    }.getOrDefault( false )


  // ── Fallback visitor token if none exists in preferences ─────────────────────
  private const val FALLBACK_VISITOR_DATA = "CgtvRlVmdTlydm45NCis6ZayBgoM"

  // ── Helper: resolve the best available visitorData token ─────────────────────
  private fun resolveVisitorData(): String =
      Preferences.YOUTUBE_VISITOR_DATA.value.takeIf { it.isNotBlank() && it != "null" }
          ?: FALLBACK_VISITOR_DATA

  // ── Helper: compute SAPISIDHASH from a cookie string ─────────────────────────
  private fun computeSapisidHash(cookieValue: String): Triple<Long, String, String>? {
      val sapisid = cookieValue.split(";")
          .map { it.trim() }
          .firstOrNull { part ->
              part.startsWith("SAPISID=") ||
              part.startsWith("__Secure-1PAPISID=") ||
              part.startsWith("__Secure-3PAPISID=")
          }?.substringAfter("=") ?: return null

      val ts = System.currentTimeMillis() / 1000
      val origin = "https://music.youtube.com"
      val sha1 = java.security.MessageDigest
          .getInstance("SHA-1")
          .digest("$ts $sapisid $origin".toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }

      return Triple(ts, sha1, origin)
  }

  // ── Ktor-layer auth plugin ────────────────────────────────────────────────────
  // Injects Cookie + SAPISIDHASH + X-Goog-Visitor-Id at the Ktor level on every
  // outgoing request to youtube.com or youtubei.googleapis.com.
  private val YouTubeKtorAuthPlugin = createClientPlugin("YouTubeKtorAuth") {
      onRequest { request, _ ->
          val host = request.url.host
          val isYouTubeHost = host.endsWith("youtube.com") ||
                              host.endsWith("youtubei.googleapis.com")
          if (host.isBlank() || !isYouTubeHost) return@onRequest

          val visitorData = resolveVisitorData()

          // Always inject X-Goog-Visitor-Id (prevents NewPipe visitorData crash)
          if (!request.headers.contains("X-Goog-Visitor-Id")) {
              request.headers.append("X-Goog-Visitor-Id", visitorData)
          }

          // Resolve active cookie fresh at request time
          val cookieValue: String? = Preferences.YOUTUBE_COOKIES.value.takeIf { it.isNotBlank() }

          if (cookieValue.isNullOrBlank()) {
              android.util.Log.d("KiyoKtorAuth", "No cookie for $host — skipping SAPISIDHASH")
              return@onRequest
          }

          // Inject Cookie if not already present
          if (!request.headers.contains(HttpHeaders.Cookie)) {
              request.headers.append(HttpHeaders.Cookie, cookieValue)
          }

          // Compute and inject SAPISIDHASH + companion headers
          if (!request.headers.contains(HttpHeaders.Authorization)) {
              val (ts, sha1, origin) = computeSapisidHash(cookieValue) ?: run {
                  android.util.Log.w("KiyoKtorAuth", "No SAPISID in cookie for $host")
                  return@onRequest
              }
              request.headers.append(HttpHeaders.Authorization, "SAPISIDHASH ${ts}_${sha1}")
              request.headers.append("X-Origin", origin)
              request.headers.append("Referer", "$origin/")
              request.headers.append("X-Goog-Authuser", "0")
              android.util.Log.d("KiyoKtorAuth", "SAPISIDHASH injected at Ktor layer for $host")
          }
      }
  }

  // ── OkHttp network-level interceptor ─────────────────────────────────────────
  // Catches requests that bypass Ktor (e.g. from NewPipeExtractor) at the socket layer.
  private class YouTubeAuthInterceptor : okhttp3.Interceptor {

      private fun injectVisitorDataIntoBody(bodyString: String, visitorData: String): String {
          val clientKey = "\"client\":"
          val idx = bodyString.indexOf(clientKey)
          if (idx == -1) return bodyString

          val closingBraceIdx = bodyString.indexOf("}", idx)
          if (closingBraceIdx != -1 && bodyString.substring(idx, closingBraceIdx).contains("visitorData")) {
              return bodyString
          }

          val spliceIdx = idx + clientKey.length
          return bodyString.substring(0, spliceIdx) +
                 "\"visitorData\":\"${visitorData}\"," +
                 bodyString.substring(spliceIdx)
      }

      override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
          val original = chain.request()
          val host = original.url.host

          val isYouTubeHost = host.endsWith("youtube.com") ||
                              host.endsWith("youtubei.googleapis.com")
          if (host.isBlank() || !isYouTubeHost) {
              return chain.proceed(original)
          }

          val visitorData = resolveVisitorData()
          val requestBuilder = original.newBuilder()

          // 1. Always inject X-Goog-Visitor-Id
          requestBuilder.header("X-Goog-Visitor-Id", visitorData)

          // 2. POST body: inject visitorData into context.client if absent
          val originalBody = original.body
          if (original.method == "POST" && originalBody != null) {
              runCatching {
                  val buffer = Buffer()
                  originalBody.writeTo(buffer)
                  val bodyStr = buffer.readUtf8()
                  val newBodyStr = injectVisitorDataIntoBody(bodyStr, visitorData)
                  if (newBodyStr != bodyStr) {
                      requestBuilder.method(
                          original.method,
                          newBodyStr.toRequestBody(originalBody.contentType())
                      )
                      android.util.Log.d("KiyoAuth", "visitorData injected into POST body for $host")
                  }
              }.onFailure { err ->
                  android.util.Log.w("KiyoAuth", "Body injection failed for $host: ${err.message}")
              }
          }

          // 3. Cookie + SAPISIDHASH
          val existingCookie: String? = original.header("Cookie")
          val cookieValue: String? = when {
              !existingCookie.isNullOrBlank() -> existingCookie
              else -> Preferences.YOUTUBE_COOKIES.value.takeIf { it.isNotBlank() }
          }

          if (cookieValue.isNullOrBlank()) {
              return chain.proceed(requestBuilder.build())
          }

          if (existingCookie.isNullOrBlank()) {
              requestBuilder.header("Cookie", cookieValue)
          }

          val (ts, sha1, origin) = computeSapisidHash(cookieValue) ?: run {
              android.util.Log.w("KiyoAuth", "No SAPISID in cookie for $host")
              return chain.proceed(requestBuilder.build())
          }

          android.util.Log.d("KiyoAuth", "SAPISIDHASH added for $host")

          requestBuilder.apply {
              header("Authorization", "SAPISIDHASH ${ts}_${sha1}")
              header("X-Origin", origin)
              header("Referer", "$origin/")
              header("X-Goog-Authuser", "0")
          }

          return chain.proceed(requestBuilder.build())
      }
  }

  actual val networkModule: Module = module {
    factory<Proxy> {       // Recreate proxy instance every time it's called
        if( !Preferences.IS_PROXY_ENABLED.value ) {
            Logger.d( tag = LOGGING_TAG ) { "Proxy is not enabled" }
            return@factory Proxy.NO_PROXY
        }

        val proxy = Proxy(
            Preferences.PROXY_SCHEME.value,
            InetSocketAddress(Preferences.PROXY_HOST.value, Preferences.PROXY_PORT.value)
        )
        // Must verify to prevent network failure
        runBlocking( Dispatchers.IO ) { proxy.takeIf( ::verifyProxy ) ?: Proxy.NO_PROXY }
    }
    factory<Dns> {
        if( Preferences.DOH_SERVER.value == DohServer.NONE ) {
            Logger.d( tag = LOGGING_TAG ) { "DoH is not enabled. Using system's DNS" }
            return@factory Dns.SYSTEM
        }

        val client = OkHttpClient.Builder().build()
        val url = Preferences.DOH_SERVER.value.url!!        // Cannot be null if other than NONE
        val addresses = Preferences.DOH_SERVER.value.address.map( InetAddress::getByName )

        val dns = DnsOverHttps
            .Builder()
            .client( client )
            .url( url )
            .bootstrapDnsHosts( addresses )
            .build()
        // Must verify to prevent network failure
        runBlocking( Dispatchers.IO ) {
            dns.takeIf { verifyDoH(it, addresses) } ?: Dns.SYSTEM
        }
    }

    single {
        val interceptor = HttpLoggingInterceptor(OkHttpLogger())
        interceptor.setLevel(
            if( BuildConfig.DEBUG )
                HttpLoggingInterceptor.Level.BODY
            else
                // Production doesn't need full body to be logged because
                // it may contain user's credential(s).
                // Basic request's destination and response code is enough
                HttpLoggingInterceptor.Level.BASIC
        )

        OkHttpClient.Builder()
                    .proxy( get() )
                    .dns( get() )
                    .addInterceptor( interceptor )
                    .addNetworkInterceptor( YouTubeAuthInterceptor() )
                    .build()
                    .also {
                        NewPipe.init( NewPipeDownloaderImpl(it) )
                    }
    }
    single {
        HttpClient(OkHttp) {
            expectSuccess = true

            @OptIn(ExperimentalSerializationApi::class)
            install( ContentNegotiation ) {
                protobuf()
                json( JSON )
            }

            install( ContentEncoding ) {
                gzip( 1f )
                deflate( 0.9F )
            }

            @OptIn(ExperimentalSerializationApi::class)
            install(WebSockets ) {
                contentConverter = KotlinxWebsocketSerializationConverter(JSON)
            }

            // Install the YouTube / InnerTube credential loader plugin
            install(YouTubeKtorAuthPlugin)

            engine {
                preconfigured = get()
            }

            defaultRequest {
                url( Constants.YOUTUBE_MUSIC_URL )
                contentType( ContentType.Application.Json )

                url {
                    parameters.append("prettyPrint", "false")
                }
            }
        }
    }
}