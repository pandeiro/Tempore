package com.github.pandeiro.tempore.slsk.core.api

import kotlinx.serialization.json.Json
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Hand-written Retrofit surface for slskd 0.26.1.x. Field shapes follow slsk/SPIKE.md
 * (live-probed) over the generated OpenAPI doc, which is unreliable.
 *
 * All calls return [Response] so non-2xx answers (400 validation, 404 unknown peer, 409
 * user offline, 503) can be inspected instead of thrown by a converter.
 */
interface SlskdApi {

    @GET("api/v0/application")
    suspend fun application(): Response<ApplicationDto>

    /**
     * Starts a search. When `requestTimeout` (ms) is supplied slskd BLOCKS until the search
     * completes or the timeout elapses, then answers 200 with the final snapshot; the caller
     * fires this on a worker and polls [searchState] immediately.
     */
    @POST("api/v0/searches")
    suspend fun startSearch(
        @Query("requestTimeout") requestTimeoutMs: Long?,
        @Body body: StartSearchRequestDto,
    ): Response<SearchStateDto>

    @GET("api/v0/searches/{id}")
    suspend fun searchState(@Path("id") id: String): Response<SearchStateDto>

    @GET("api/v0/searches/{id}/responses")
    suspend fun searchResponses(@Path("id") id: String): Response<List<SearchResponseDto>>

    /** Stops an in-flight search from seeking more responses (404 on slskd versions without it). */
    @PUT("api/v0/searches/{id}")
    suspend fun stopSearch(@Path("id") id: String): Response<Unit>

    /** Removes the search record entirely (also the cleanup used when abandonment needs deletion). */
    @DELETE("api/v0/searches/{id}")
    suspend fun deleteSearch(@Path("id") id: String): Response<Unit>

    /**
     * Preferred enqueue. slskd requires the top-level `Username` and a non-empty `Files`
     * array (400 otherwise). Semantics: 201 = all queued; 200/207 = inspect the failures in
     * the body; any 2xx without failures is accepted.
     */
    @POST("api/v0/transfers/downloads/batches")
    suspend fun enqueueBatch(@Body body: EnqueueBatchRequestDto): Response<List<EnqueueResultItemDto>>

    /**
     * Legacy fallback (`POST /transfers/downloads/{username}`): inline `[{filename,size}]`
     * list, used when the batch endpoint 404s/405s or rejects the request shape.
     */
    @POST("api/v0/transfers/downloads/{username}")
    suspend fun enqueueLegacy(
        @Path("username") username: String,
        @Body files: List<EnqueueFileDto>,
    ): Response<List<EnqueueResultItemDto>>

    /** Transfers grouped by user, then directory (`{username, directories:[{directory, files}]}`). */
    @GET("api/v0/transfers/downloads")
    suspend fun downloads(): Response<List<TransferUserDto>>

    /** Queue position of a single transfer; null when slskd does not track it. */
    @GET("api/v0/transfers/downloads/{username}/{id}/position")
    suspend fun transferPosition(@Path("username") username: String, @Path("id") id: String): Response<Int>

    /** `remove=true` also removes the row from the list, `false` just aborts where possible. */
    @DELETE("api/v0/transfers/downloads/{username}/{id}")
    suspend fun removeTransfer(
        @Path("username") username: String,
        @Path("id") id: String,
        @Query("remove") remove: Boolean,
    ): Response<Unit>

    /** Clears every completed download row (206/200/204 all plausible; caller tolerates). */
    @DELETE("api/v0/transfers/downloads/all/completed")
    suspend fun removeCompletedTransfers(): Response<Unit>

    /** Full folder listing from a peer; slskd answers 404 for unknown users (`Directory` body). */
    @POST("api/v0/users/{username}/directory")
    suspend fun userDirectory(
        @Path("username") username: String,
        @Body body: UserDirectoryRequestDto,
    ): Response<List<SearchFileDto>>

    companion object {
        /** ignoreUnknownKeys: slskd adds fields freely between versions; coerceInputValues: null-soaked booleans. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }
    }
}
