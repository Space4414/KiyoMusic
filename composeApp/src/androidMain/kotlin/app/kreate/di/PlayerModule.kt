@file:androidx.media3.common.util.UnstableApi

  package app.kreate.di

  import android.annotation.SuppressLint
  import android.content.Context
  import android.net.ConnectivityManager
  import androidx.compose.runtime.getValue
  import androidx.compose.ui.util.fastFilter
  import androidx.core.content.getSystemService
  import androidx.core.net.toUri
  import androidx.media3.common.AudioAttributes
  import androidx.media3.common.C
  import androidx.media3.datasource.DataSource
  import androidx.media3.datasource.DefaultDataSource
  import androidx.media3.datasource.ResolvingDataSource
  import androidx.media3.datasource.cache.Cache
  import androidx.media3.datasource.cache.CacheDataSink
  import androidx.media3.datasource.cache.CacheDataSource
  import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
  import androidx.media3.datasource.okhttp.OkHttpDataSource
  import androidx.media3.exoplayer.DefaultRenderersFactory
  import androidx.media3.exoplayer.ExoPlayer
  import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
  import androidx.media3.extractor.DefaultExtractorsFactory
  import app.kreate.android.Preferences
  import app.kreate.android.R
  import app.kreate.android.service.DownloadHelper
  import app.kreate.android.service.player.StatefulPlayerImpl
  import app.kreate.android.service.player.ErrorHandlingPolicy
  import app.kreate.android.service.player.StatefulPlayer
  import app.kreate.android.service.player.VolumeObserver
  import app.kreate.android.utils.CharUtils
  import app.kreate.android.utils.ConnectivityUtils
  import app.kreate.android.utils.innertube.CURRENT_LOCALE
  import app.kreate.android.utils.isLocalFile
  import app.kreate.database.models.Format
  import co.touchlab.kermit.Logger
  import io.ktor.client.HttpClient
  import io.ktor.client.request.head
  import io.ktor.http.URLBuilder
  import io.ktor.http.isSuccess
  import io.ktor.http.parseQueryString
  import io.ktor.util.collections.ConcurrentMap
  import io.ktor.util.network.UnresolvedAddressException
  import it.fast4x.rimusic.Database
  import it.fast4x.rimusic.enums.AudioQualityFormat
  import it.fast4x.rimusic.service.LoginRequiredException
  import it.fast4x.rimusic.service.MissingDecipherKeyException
  import it.fast4x.rimusic.service.NoInternetException
  import it.fast4x.rimusic.service.PlayableFormatNotFoundException
  import it.fast4x.rimusic.service.UnplayableException
  import it.fast4x.rimusic.utils.isNetworkAvailable
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.runBlocking
  import kotlinx.serialization.ExperimentalSerializationApi
  import kotlinx.serialization.MissingFieldException
  import me.knighthat.impl.DownloadHelperImpl
  import me.knighthat.innertube.Endpoints
  import me.knighthat.innertube.Innertube
  import me.knighthat.innertube.UserAgents
  import me.knighthat.innertube.response.PlayerResponse
  import me.knighthat.utils.Toaster
  import okhttp3.OkHttpClient
  import org.koin.core.module.dsl.singleOf
  import org.koin.core.qualifier.Qualifier
  import org.koin.core.qualifier.QualifierValue
  import org.koin.dsl.module
  import org.koin.java.KoinJavaComponent.inject
  import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
  import java.net.UnknownHostException
  import java.util.concurrent.atomic.AtomicLong
  import java.util.concurrent.atomic.AtomicReference
  import kotlin.time.Duration.Companion.seconds


  private const val CHUNK_LENGTH = 512 * 1024L     // 512KB
  private const val ONE_HOUR = 3_600_000L
  private const val METHOD_ANDROID = 1
  private const val METHOD_IOS = 2

  /**
   * Acts as a lock to keep [upsertSongFormat] from starting before
   * [upsertSongInfo] finishes.
   */
  private var databaseWorker: Job = Job()

  /**
   * Store id of song just added to the database.
   * This is created to reduce load to Room
   */
  private val justInserted = AtomicReference("")
  private val cachedStreamUrl = ConcurrentMap<String, StreamCache>()

  /**
   * Visitor-data token obtained from a real YouTube player response.
   *
   * Starts as null (falls back to the hardcoded constant baked into
   * [me.knighthat.innertube.request.body.Context.WEB_REMIX_DEFAULT] /
   * [me.knighthat.innertube.request.body.Context.IOS_DEFAULT]).
   *
   * Updated after every successful [Innertube.player] call so that
   * subsequent requests carry a freshly issued token. This makes the
   * app resilient to YouTube rotating the hardcoded fallback values.
   *
   * Never touches [Preferences.YOUTUBE_VISITOR_DATA] — YouTube login
   * state is completely unrelated and is managed separately by
   * InnertubeProvider.
   */
  private val cachedVisitorData = AtomicReference<String?>(null)
    /** Epoch ms when [cachedVisitorData] was last refreshed. */
    private val cachedVisitorDataTime = AtomicLong(0L)
    /** Invalidate anonymous visitor-data cache after 30 minutes. */
    private val VISITOR_DATA_TTL_MS = 30L * 60L * 1_000L

  private val client: HttpClient by inject(HttpClient::class.java)
  private val context: Context by inject(Context::class.java)
  private val logger = Logger.withTag( "dataspec" )

  //<editor-fold desc="Database handlers">
  /**
   * Reach out to [Endpoints.NEXT] endpoint for song's information.
   *
   * Info includes:
   * - Titles
   * - Artist(s)
   * - Album
   * - Thumbnails
   * - Duration
   *
   * ### If song IS already inside database
   *
   * It'll replace unmodified columns with fetched data
   *
   * ### If song IS NOT already inside database
   *
   * New record will be created and insert into database
   *
   */
  private fun upsertSongInfo( context: Context, videoId: String ) {       // Use this to prevent suspension of thread while waiting for response from YT
      // Skip adding if it's just added in previous call
      if( videoId == justInserted.get() || !isNetworkAvailable( context ) )
          return

      logger.v { "fetching and upserting ${videoId}'s information to the database" }

      databaseWorker = CoroutineScope(Dispatchers.IO ).launch {
          Innertube.songBasicInfo( videoId, CURRENT_LOCALE )
              .onSuccess{
                  logger.v { "${videoId}'s information successfully found and parsed" }

                  Database.upsert( it )

                  logger.d { "${videoId}'s information successfully upserted to the database" }
              }
              .onFailure {
                  logger.e( "failed to upsert ${videoId}'s information to database", it )
                  Toaster.e( R.string.error_failed_to_fetch_songs_info )
              }
      }

      // Must not modify [JustInserted] to [upsertSongFormat] let execute later
  }

  /**
   * Upsert provided format to the database
   */
  private fun upsertSongFormat( videoId: String, format: PlayerResponse.StreamingData.Format ) {
      // Skip adding if it's just added in previous call
      if( videoId == justInserted.get() ) return

      logger.v { "upserting format ${format.itag} of song ${videoId} to the database" }

      CoroutineScope(Dispatchers.IO ).launch {
          // Wait until this job is finish to make sure song's info
          // is in the database before continuing
          databaseWorker.join()

          Database.asyncTransaction {
              formatTable.upsert(
                  Format(
                      videoId,
                      format.itag.toInt(),
                      format.mimeType,
                      format.bitrate.toLong(),
                      format.contentLength?.toLong(),
                      format.lastModified.toLong(),
                      format.loudnessDb
                  )
              )

              logger.d { "${videoId} is successfully upserted to the database" }

              // Format must be added successfully before setting variable
              justInserted.set( videoId )
          }
      }
  }
  //</editor-fold>
  //<editor-fold desc="Extractors">
  @Throws(PlayableFormatNotFoundException::class)
  private fun extractFormat(
      streamingData: PlayerResponse.StreamingData?,
      audioQualityFormat: AudioQualityFormat,
      connectionMetered: Boolean
  ): PlayerResponse.StreamingData.Format {
      logger.v { "extracting format with quality ${audioQualityFormat} and metered connection: ${connectionMetered}" }

      val sortedAudioFormats =
          streamingData?.adaptiveFormats
              ?.fastFilter {
                  it.mimeType.startsWith( "audio" )
              }
              ?.sortedBy(PlayerResponse.StreamingData.Format::bitrate )
              .orEmpty()
      if( sortedAudioFormats.isEmpty() )
          throw PlayableFormatNotFoundException()

      return when( audioQualityFormat ) {
          AudioQualityFormat.High -> sortedAudioFormats.last()
          AudioQualityFormat.Medium -> sortedAudioFormats[sortedAudioFormats.size / 2]
          AudioQualityFormat.Low -> sortedAudioFormats.first()
          AudioQualityFormat.Auto ->
              if ( connectionMetered && Preferences.IS_CONNECTION_METERED.value )
                  sortedAudioFormats[sortedAudioFormats.size / 2]
              else
                  sortedAudioFormats.last()
      }.also {
          logger.d { "extracted format ${it.itag}" }
      }
  }

  @Throws(MissingDecipherKeyException::class)
  private fun extractStreamUrl( videoId: String, format: PlayerResponse.StreamingData.Format ): String =
      format.signatureCipher?.let { signatureCipher ->
          logger.v { "deobfuscating signature ${signatureCipher}" }

          val (s, sp, url) = with( parseQueryString( signatureCipher ) ) {
              val signature = this["s"] ?: throw MissingDecipherKeyException("s")
              val signatureParam = this["sp"] ?: throw MissingDecipherKeyException("sp")
              val signatureUrl = this["url"] ?: throw MissingDecipherKeyException("url")
              Triple(
                  signature,
                  signatureParam,
                  URLBuilder(signatureUrl)
              )
          }
          url.parameters[sp] = YoutubeJavaScriptPlayerManager.deobfuscateSignature( videoId, s )
          url.toString()
      } ?: format.url!!
  //</editor-fold>
  //<editor-fold desc="Validators">
  /**
   * Validate the stream URL by making a HEAD request.
   *
   * Retries once on SSL exceptions: Android 7 (API 24) has a TLS bug where
   * concurrent SSL handshakes can fail with "Bad file descriptor". The first
   * attempt to a new googlevideo.com server occasionally triggers this; a
   * single retry on the same (now warmed-up) TLS session succeeds.
   */
  private suspend fun validateStreamUrl( streamUrl: String ): Boolean {
      // Probe with a small initial range to avoid downloading large data during validation
      val probeUrl = "${streamUrl}&range=0-${CHUNK_LENGTH}"
      repeat(2) { attempt ->
          try {
              val status = client.head( probeUrl ).status
              if( status.isSuccess() ) {
                  logger.d { "Stream url validated successfully" }
                  return true
              } else {
                  logger.w { "Stream url validation returns code ${status.value} - ${status.description}" }
                  return false
              }
          } catch( e: Exception ) {
              if( attempt == 0 ) {
                  // Android 7 TLS "Bad file descriptor" transient failure — retry once
                  logger.w { "validateStreamUrl attempt 1 failed (${e.message}), retrying..." }
              } else {
                  logger.e( "validateStreamUrl retry also failed, treating url as invalid", e )
              }
          }
      }
      return false
  }
  //</editor-fold>
  //<editor-fold desc="Get response">
  @OptIn(ExperimentalSerializationApi::class)
  private suspend fun makeStreamCache(
      songId: String,
      isConnectionMetered: Boolean,
      audioQuality: AudioQualityFormat,
      method: Int = METHOD_ANDROID
  ): StreamCache {
      logger.v { "Getting online stream url for \"${songId}\" with method ${method}" }
      logger.d { "Is connection metered: ${isConnectionMetered}" }
      logger.d { "Audio format: ${audioQuality}" }

      val cpn = CharUtils.randomString( 12 )
      try {
          //<editor-fold desc="Getting response">
          // Use the forked innertube-kotlin library which carries hardcoded visitor-data
          // constants per client type, bypassing the broken /visitor_id YouTube endpoint
          // (which now returns HTTP 400 for all known request formats).
          //
          // spc (Signed Playback Context) consistency:
          //   OkHttp's cookie jar sends SAPISID on every request — including both the
          //   player API call and the subsequent stream chunk fetches.  YouTube therefore
          //   produces an authenticated SPC in the player response, and the CDN accepts it
          //   when the stream chunks also carry the SAPISID cookie.  No explicit auth
          //   headers are needed in the player request; the cookie jar is sufficient.
          //
          // visitorData when NOT logged in:
          //   InnertubeImpl.getContext() falls back to template.client.userAgent (a user-agent
          //   string, not a valid protobuf token) when visitorData=null is passed and
          //   useLogin=false.  We pass the real hardcoded constant explicitly to avoid this.
          //   After the first successful response we update the cache with the fresh token
          //   YouTube echoes back in responseContext.visitorData.
          val isLoggedIn = Preferences.YOUTUBE_COOKIES.value.contains( "SAPISID" )

          val playerContext = when {
                // First attempt: WEB_REMIX.  Fallback: IOS.
                //
                // Why WEB_REMIX instead of ANDROID?
                // YouTube recently changed ANDROID stream URLs to include svpuc=1 (Signed
                // Video Player URL Challenge), which causes every HEAD validation to return
                // HTTP 403 regardless of login state.  The ANDROID path is therefore broken
                // for all users on any Android API level.
                //
                // Why WEB_REMIX instead of IOS?
                // IOS stream URLs include rqh=1 (Range-Query-Hash required).  The CDN uses
                // a challenge-response protocol: after the first 512 KB chunk is served, all
                // subsequent chunk GETs must include a hash provided by the CDN in the first
                // response.  ExoPlayer does not implement this protocol, so chunk 2 always
                // returns HTTP 416 (Range Not Satisfiable) even though the byte range is
                // within the file.  This is why playback cuts out after ~1 minute.
                //
                // Why not cookies with WEB_REMIX?
                // Both YouTubeKtorAuthPlugin (Ktor layer) and YouTubeAuthInterceptor (OkHttp
                // layer) already skip cookie and SAPISIDHASH injection for the player
                // endpoint.  The WEB_REMIX player request goes out unauthenticated, which is
                // exactly what we want: YouTube returns a cookie-free stream URL without
                // svpuc=1 or rqh=1, compatible with chunked ExoPlayer streaming.
                method == METHOD_ANDROID ->
                    me.knighthat.innertube.request.body.Context.WEB_REMIX_DEFAULT
                else ->
                    me.knighthat.innertube.request.body.Context.IOS_DEFAULT
            }

          // When logged in: pass null → getContext() uses provider.visitorData (account data)
          // When not logged in: pass the cached/hardcoded token so getContext() uses the
          //   correct base64 protobuf string instead of falling back to the userAgent string.
          //   Invalidate the cache after VISITOR_DATA_TTL_MS (30 min) so a stale token
          //   never gets stuck in memory.
          val storedVd = cachedVisitorData.get()
          val vdExpired = (System.currentTimeMillis() - cachedVisitorDataTime.get()) > VISITOR_DATA_TTL_MS
          val visitorData = if( isLoggedIn ) null
                            else if( storedVd != null && !vdExpired ) storedVd
                            else playerContext.client.visitorData

          val response = Innertube.player(
              songId = songId,
              context = playerContext,
              localization = CURRENT_LOCALE,
              signatureTimestamp = null,
              visitorData = visitorData,
              useLogin = false          // No SAPISIDHASH header; OkHttp cookie jar sends SAPISID implicitly
          ).getOrThrow()

          // Cache the fresh visitor-data token YouTube echoes in every player response so
          // the next anonymous request carries a live token instead of the hardcoded fallback.
          // (Not needed when logged in — provider.visitorData already holds the account token.)
          // Stamp the refresh time so the 30-min TTL window resets on each successful fetch.
          if( !isLoggedIn ) {
              response.responseContext.visitorData?.also {
                  cachedVisitorData.set( it )
                  cachedVisitorDataTime.set( System.currentTimeMillis() )
              }
          }
          //</editor-fold>
          //<editor-fold desc="Verify playability">
          val playabilityStatus = requireNotNull( response.playabilityStatus ) {
              "playabilityStatus is null!"
          }
          when( playabilityStatus.status ) {
              "OK"                -> logger.d { "playabilityStatus is OK" }
              "LOGIN_REQUIRED"    -> throw LoginRequiredException(playabilityStatus.reason)
              else                -> throw UnplayableException(playabilityStatus.reason)
          }
          //</editor-fold>
          //<editor-fold desc="Extract and validate stream url">
          val format = extractFormat( response.streamingData, audioQuality, isConnectionMetered )
          val streamUrl = extractStreamUrl( songId, format )
          val validateResult = validateStreamUrl( streamUrl )
          //</editor-fold>

          return if( validateResult ) {
              upsertSongFormat( songId, format )

              val contentLength = format.contentLength?.toLong() ?: CHUNK_LENGTH
              val expiresInSeconds = response.streamingData?.expiresInSeconds?.toLong() ?: 3600L
              val expiredTimeMillis = System.currentTimeMillis() + expiresInSeconds * 1000L
              StreamCache(cpn, contentLength, streamUrl, expiredTimeMillis)
          } else
              // Try again with IOS setup
              makeStreamCache( songId, isConnectionMetered, audioQuality, METHOD_IOS )
      } catch( e: Exception ) {
          if( method == METHOD_ANDROID )
              return makeStreamCache( songId, isConnectionMetered, audioQuality, METHOD_IOS )

          when( e ) {
              is UnknownHostException,
              is UnresolvedAddressException -> {
                  // Make sure it's not a temporary network fluctuation
                  if( !ConnectivityUtils.isAvailable.value )
                      throw NoInternetException(e)
              }

              // Only show this exception because this needs update
              // Other errors might be because of unsuccessful stream extraction
              is MissingFieldException -> {
                  e.message?.also( Toaster::e )
                  logger.e( "", e )
              }
          }

          throw e
      }
  }

  private fun getPlayableUrl( songId: String ): StreamCache = runBlocking( Dispatchers.IO ) {
      logger.v { "Processing ${songId}" }

      val cache: StreamCache
      if( cachedStreamUrl.contains(songId) ) {
          cache = cachedStreamUrl[songId]!!
          // Handle expired url with 30secs offset
          if( cache.expiredTimeMillis - 30.seconds.inWholeMilliseconds <= System.currentTimeMillis() ) {
              logger.d { "Cached stream url of ${songId} expired" }

              cachedStreamUrl.remove( songId )
              return@runBlocking getPlayableUrl( songId )
          } else
              logger.d { "Stream url of ${songId} is cached" }
      } else {
          val connManager = context.getSystemService<ConnectivityManager>()
          val isConnectionMetered = connManager?.isActiveNetworkMetered ?: false
          val audioQuality by Preferences.AUDIO_QUALITY

          cache = makeStreamCache( songId, isConnectionMetered, audioQuality )
          cachedStreamUrl[songId] = cache
      }

      cache
  }
  //</editor-fold>
  //<editor-fold desc="Resolvers">
  private fun resolver( queryInChunks: Boolean, vararg cashes: Cache ) =
      ResolvingDataSource.Resolver { dataSpec ->
          if( dataSpec.uri.isLocalFile() ) {
              logger.d { "playing local song: ${dataSpec.uri}" }
              return@Resolver dataSpec
          }

          val songId = dataSpec.uri.toString()
          upsertSongInfo( context, songId )

          // Delay this block until called. Song can be local too
          val position = dataSpec.position
          val length = dataSpec.length.takeIf { it > C.LENGTH_UNSET } ?: CHUNK_LENGTH
          val isCached = cashes.any {
              it.isCached( songId, position, length )
          }
          if( isCached ) {
              logger.v { "Chunk ${position} - ${position + length} of ${songId} is cached" }
              // No need to fetch online for already cached data
              dataSpec
          } else {
              val cache = getPlayableUrl( songId )
              // IOS stream URLs (c=IOS) carry rqh=1 (range-query-hash).  The CDN uses a
              // challenge-response protocol where a hash embedded in the first-chunk response
              // must be included in every subsequent chunk request.  ExoPlayer does not
              // implement this protocol, so any non-zero start position returns HTTP 416.
              // Fix: skip n-deobfuscation for IOS (it uses a different token scheme and
              // applying the WEB deobfuscator corrupts the URL), and request the entire
              // remaining content in a single GET so the CDN never needs to issue a
              // challenge for chunk 2+.
              //
              // WEB_REMIX/WEB URLs: apply n-parameter deobfuscation (NewPipeExtractor) and
              // use normal 512 KB chunked streaming — no rqh=1, no svpuc=1.
              val isIosUrl = cache.playableUrl.contains("c=IOS")
              val deobUrl = if (isIosUrl) {
                  cache.playableUrl
              } else {
                  YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated( songId, cache.playableUrl )
              }
              // IOS: request everything from the current position to end-of-file in one shot.
              // WEB_REMIX: standard 512 KB chunks (or full file when queryInChunks=false).
              val chunkLen = when {
                  isIosUrl     -> cache.contentLength - position
                  queryInChunks -> CHUNK_LENGTH
                  else         -> cache.contentLength
              }
                // YouTube CDN sets rqh=1 (range-query-hash required): HTTP Range headers
                // are rejected for non-zero start positions.  Only a request starting at
                // byte 0 survives via HTTP Range header; every subsequent chunk must encode
                // its byte range as a URL query parameter (&range=START-END) instead.
                //
                // Fix: for every chunk, append &range=chunkStart-chunkEnd to the URL, then
                // set both position and uriPositionOffset to chunkStart so that OkHttp
                // computes:  httpRangePosition = position - uriPositionOffset = 0
                // and therefore sends NO HTTP Range header.  The CDN serves the correct
                // bytes via the URL parameter, and ExoPlayer/CacheDataSource store them at
                // the right absolute offset (chunkStart) in the playback stream.
                val chunkStart = position           // absolute byte offset in the stream
                // Cap chunkEnd at contentLength-1 to prevent an out-of-bounds range that
                // would cause HTTP 416 when position + chunkLen exceeds the file size.
                val chunkEnd   = minOf(chunkStart + chunkLen - 1L, cache.contentLength - 1L)
                val actualLen  = chunkEnd - chunkStart + 1L
                // IOS stream URLs (c=IOS) reject the `cpn` (Client Playback Nonce)
                // parameter — it is a WEB-client session tracking param and causes HTTP 403
                // on IOS CDN validation.  WEB_REMIX URLs expect it for session affinity.
                val uri = if( isIosUrl ) {
                    "${deobUrl}&range=${chunkStart}-${chunkEnd}".toUri()
                } else {
                    "${deobUrl}&cpn=${cache.cpn}&range=${chunkStart}-${chunkEnd}".toUri()
                }
                dataSpec.buildUpon()
                    .setUri( uri )
                    .setPosition( chunkStart )
                    .setUriPositionOffset( chunkStart )
                    .setLength( actualLen )
                    .build()
            }
      }
  //</editor-fold>

  /**
   * Short-circuit function to quickly make a [DataSource.Factory] from
   * designated [cache]
   */
  private fun dataSourceFactoryFrom( cache: Cache ): CacheDataSource.Factory =
      CacheDataSource.Factory().setCache( cache )

  /**
   * Remove cached url of [songId].
   *
   * @return `true` if song's url was cached, and is deleted, `false` otherwise.
   */
  fun clearCachedStreamUrlOf( songId: String ): Boolean =
      cachedStreamUrl.remove( songId ) != null

  val playerModule = module {
      // [DefaultDataSource.Factory] with [context] is required to read
      // data from local files.
      // Normal HTTP requests are handled by [OkHttpDataSource.Factory]
      single {
          val engine: OkHttpClient = get()
          // ExoPlayer stream requests go to *.googlevideo.com CDN servers.
          // Sending SAPISID auth cookies to CDN causes HTTP 403 on chunk GETs:
          // the CDN load-balancer redirects to a different edge server that
          // rejects requests carrying auth cookies alongside an IOS/ANDROID
          // spc token.  The spc already encodes auth — cookies are redundant
          // on CDN requests and actively break CDN session routing.  Fix:
          // use a cookie-free client for all stream/chunk fetches while the
          // main (cookie-bearing) client continues to handle YouTube API calls.
          val streamClient = engine.newBuilder()
              .cookieJar( okhttp3.CookieJar.NO_COOKIES )
              .build()
          DefaultDataSource.Factory(
              get(),
              OkHttpDataSource.Factory( streamClient )
                  .setUserAgent( UserAgents.CHROME_WINDOWS )
          )
      }
      single( DatasourceType.PLAYER ) {
          val cache: Cache = get(CacheType.CACHE)
          val downloadCache: Cache = get(CacheType.DOWNLOAD)
          val defaultDatasource: DefaultDataSource.Factory = get()

          ResolvingDataSource.Factory(
              dataSourceFactoryFrom( downloadCache )
                  .setCacheWriteDataSinkFactory( null )
                  .setFlags( FLAG_IGNORE_CACHE_ON_ERROR )
                  .setUpstreamDataSourceFactory(
                      dataSourceFactoryFrom( cache )
                          .setUpstreamDataSourceFactory( defaultDatasource )
                          .setCacheWriteDataSinkFactory(
                              CacheDataSink.Factory()
                                  .setCache( cache )
                                  .setFragmentSize( CHUNK_LENGTH )
                          )
                          .setFlags( FLAG_IGNORE_CACHE_ON_ERROR )
                  ),
              resolver( true, cache, downloadCache )
          )
      }
      single( DatasourceType.DOWNLOADER ) {
          val downloadCache: Cache = get(CacheType.DOWNLOAD)
          val defaultDatasource: DefaultDataSource.Factory = get()

          ResolvingDataSource.Factory(
              dataSourceFactoryFrom( downloadCache )
                  .setUpstreamDataSourceFactory( defaultDatasource )
                  .setCacheWriteDataSinkFactory( null ),
              resolver( false, downloadCache )
          )
      }

      singleOf( ::VolumeObserver )
      single<StatefulPlayer> {
          val context: Context = get()
          val resolvingFactory: ResolvingDataSource.Factory = get(DatasourceType.PLAYER)
          val handleAudioFocus by Preferences.AUDIO_SMART_PAUSE_DURING_CALLS

          val renderersFactory = DefaultRenderersFactory(context)
          val datasourceFactory = DefaultMediaSourceFactory(
              resolvingFactory,
              DefaultExtractorsFactory()
          )
          datasourceFactory.setLoadErrorHandlingPolicy( ErrorHandlingPolicy() )
          val audioAttributes = AudioAttributes.Builder()
              .setUsage( C.USAGE_MEDIA )
              .setContentType( C.AUDIO_CONTENT_TYPE_MUSIC )
              .build()

          StatefulPlayerImpl(
              ExoPlayer.Builder(context)
                  .setMediaSourceFactory( datasourceFactory )
                  .setRenderersFactory( renderersFactory )
                  .setHandleAudioBecomingNoisy( true )
                  .setWakeMode( C.WAKE_MODE_NETWORK )
                  .setAudioAttributes( audioAttributes, handleAudioFocus )
                  .setUsePlatformDiagnostics( false )
                  .build()
          )
      }
      @SuppressLint("UnsafeOptInUsageError")
      single<DownloadHelper> {
          val dataSourceFactory: ResolvingDataSource.Factory = get(DatasourceType.DOWNLOADER)
          val downloadCache: Cache = get(CacheType.DOWNLOAD)

          DownloadHelperImpl(dataSourceFactory, get(), downloadCache)
      }
  }

  enum class DatasourceType : Qualifier {
      PLAYER, DOWNLOADER;

      override val value: QualifierValue = toString().lowercase()
  }

  private data class StreamCache(
      val cpn: String,
      val contentLength: Long,
      val playableUrl: String,
      val expiredTimeMillis: Long
  )
  