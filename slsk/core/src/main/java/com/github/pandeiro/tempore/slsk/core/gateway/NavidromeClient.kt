package com.github.pandeiro.tempore.slsk.core.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** Tweets wrapped Subsonic responses live under a `subsonic-response` root regardless of format. */
@Serializable
data class SubsonicWrapperDto(
    @SerialName("subsonic-response") val subsonicResponse: SubsonicResponseDto? = null,
)

@Serializable
data class SubsonicResponseDto(
    val status: String? = null,
    val version: String? = null,
    val error: SubsonicErrorDto? = null,
    val searchResult3: SubsonicSearchResultDto? = null,
    val scanStatus: SubsonicScanStatusDto? = null,
) {
    val isOk: Boolean get() = status.equals("ok", ignoreCase = true) && error == null
    val errorMessage: String? get() = error?.message ?: status
}

@Serializable
data class SubsonicErrorDto(
    val code: Int? = null,
    val message: String? = null,
)

@Serializable
data class SubsonicSearchResultDto(
    val artist: List<SubsonicArtistDto>? = null,
    val album: List<SubsonicAlbumDto>? = null,
    val song: List<SubsonicSongDto>? = null,
)

@Serializable
data class SubsonicArtistDto(
    val id: String? = null,
    val name: String? = null,
    val albumCount: Int? = null,
    val songCount: Int? = null,
)

@Serializable
data class SubsonicAlbumDto(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val songCount: Int? = null,
)

@Serializable
data class SubsonicSongDto(
    val id: String? = null,
    val title: String? = null,
)

@Serializable
data class SubsonicScanStatusDto(
    val scanning: Boolean? = null,
    val count: Long? = null,
)

/**
 * Hand-written Subsonic surface (search3 / startScan / getScanStatus only). Version is
 * pinned to 1.15.0 (upstream cap per SPIKE.md); auth params arrive per call.
 */
interface NavidromeSubsonicApi {

    @GET("rest/search3")
    suspend fun search3(
        @Query("u") username: String,
        @Query("t") token: String?,
        @Query("s") salt: String?,
        @Query("p") password: String?,
        @Query("v") apiVersion: String,
        @Query("c") client: String,
        @Query("f") format: String,
        @Query("musicFolderId") musicFolderId: Int?,
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int,
        @Query("albumCount") albumCount: Int,
        @Query("songCount") songCount: Int,
    ): Response<SubsonicWrapperDto>

    @GET("rest/getScanStatus")
    suspend fun getScanStatus(
        @Query("u") username: String,
        @Query("t") token: String?,
        @Query("s") salt: String?,
        @Query("p") password: String?,
        @Query("v") apiVersion: String,
        @Query("c") client: String,
        @Query("f") format: String,
        @Query("musicFolderId") musicFolderId: Int?,
    ): Response<SubsonicWrapperDto>

    @GET("rest/startScan")
    suspend fun startScan(
        @Query("u") username: String,
        @Query("t") token: String?,
        @Query("s") salt: String?,
        @Query("p") password: String?,
        @Query("v") apiVersion: String,
        @Query("c") client: String,
        @Query("f") format: String,
        @Query("musicFolderId") musicFolderId: Int?,
    ): Response<SubsonicWrapperDto>
}

/**
 * [LibraryGateway] for Navidrome, talking Subsonic 1.15.0 with the HostBridge-fed credentials.
 * Tolerant by design: missing fields, `status: failed` payloads, and unreachable servers all
 * degrade to null/false/Unknown instead of crashing the handoff.
 */
class NavidromeClient(
    baseUrl: String,
    private val config: NavidromeConfig,
    okHttpClient: OkHttpClient? = null,
) : LibraryGateway {

    private val api: NavidromeSubsonicApi

    init {
        val client = okHttpClient
            ?: OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType()),
            )
            .build()
        api = retrofit.create(NavidromeSubsonicApi::class.java)
    }

    private suspend fun auth(): SubsonicAuth =
        SubsonicAuth(
            username = config.username,
            token = config.token.takeIf { config.useTokenAuth },
            salt = config.salt.takeIf { config.useTokenAuth },
            password = if (config.useTokenAuth) null else config.password,
        )

    override suspend fun findAlbum(artist: String, album: String): LibraryAlbumMatch? {
        val a = auth()
        val wrapper = try {
            api.search3(
                username = a.username, token = a.token, salt = a.salt, password = a.password,
                apiVersion = API_VERSION, client = CLIENT_NAME, format = FORMAT,
                musicFolderId = config.musicFolderId,
                query = "$artist $album",
                artistCount = ARTIST_COUNT, albumCount = ALBUM_COUNT, songCount = 0,
            )
        } catch (_: Exception) {
            return null // unreachable server: "not visible yet", never a crash
        }
        if (wrapper.isSuccessful) {
            val body = wrapper.body()?.subsonicResponse ?: return null
            if (!body.isOk) return null
            return matchAlbum(body.searchResult3?.album.orEmpty(), artist, album)
        }
        return null
    }

    internal fun matchAlbum(
        albums: List<SubsonicAlbumDto>,
        artist: String,
        album: String,
    ): LibraryAlbumMatch? {
        val artistKey = artist.trim().lowercase()
        val albumKey = album.trim().lowercase()
        val exact = albums.firstOrNull {
            it.name?.lowercase() == albumKey && (it.artist?.lowercase() == artistKey || it.artist.isNullOrBlank())
        } ?: albums.firstOrNull {
            it.name?.lowercase() == albumKey
        }
        exact ?: return null
        return LibraryAlbumMatch(
            id = exact.id ?: "",
            name = exact.name ?: album,
            artist = exact.artist ?: artist,
            songCount = exact.songCount,
        )
    }

    override suspend fun startScan(): Boolean {
        return try {
            val a = auth()
            val response = api.startScan(
                username = a.username, token = a.token, salt = a.salt, password = a.password,
                apiVersion = API_VERSION, client = CLIENT_NAME, format = FORMAT,
                musicFolderId = config.musicFolderId,
            )
            val body = response.takeIf { it.isSuccessful }?.body()?.subsonicResponse
            body?.isOk == true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun scanStatus(): ScanStatus? {
        val a = auth()
        val wrapper = try {
            api.getScanStatus(
                username = a.username, token = a.token, salt = a.salt, password = a.password,
                apiVersion = API_VERSION, client = CLIENT_NAME, format = FORMAT,
                musicFolderId = config.musicFolderId,
            )
        } catch (_: Exception) {
            return null
        }
        if (!wrapper.isSuccessful) return null
        val body = wrapper.body()?.subsonicResponse ?: return null
        if (!body.isOk) return ScanStatus.Unknown(raw = body.errorMessage)
        val scanning = body.scanStatus?.scanning ?: return ScanStatus.Unknown(raw = "missing scanStatus")
        return if (scanning) ScanStatus.InProgress(startedAt = null) else ScanStatus.Completed(timeStamp = null)
    }

    private data class SubsonicAuth(val username: String, val token: String?, val salt: String?, val password: String?)

    companion object {
        const val API_VERSION = "1.15.0"
        const val CLIENT_NAME = "tempore"
        const val FORMAT = "json"
        const val ARTIST_COUNT = 20
        const val ALBUM_COUNT = 20

        /** Same tolerant settings as the slskd client. */
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }
}
