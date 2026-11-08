package com.github.pandeiro.tempore.slsk.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hand-written DTOs for slskd 0.26.1.x, matching the live-probed shapes in slsk/SPIKE.md
 * and the golden fixtures in slsk/contract/fixtures. All fields are optional so unknown
 * server additions (and slightly older versions) never break parsing; the shared Json
 * settings live in [SlskdJson].
 */

/** `GET /api/v0/application` (subset). SPIKE.md: version.full + server.isConnected/isLoggedIn. */
@Serializable
data class ApplicationDto(
    val version: VersionDto? = null,
    val server: ServerDto? = null,
)

@Serializable
data class VersionDto(
    val appliance: String? = null,
    val full: String? = null,
    val major: Int? = null,
    val minor: Int? = null,
    val buildNumber: Int? = null,
    val revision: Int? = null,
)

@Serializable
data class ServerDto(
    val isConnected: Boolean? = null,
    val isLoggedIn: Boolean? = null,
)

/** `POST /api/v0/searches` request body. `id` MUST be a real GUID (slskd 400s otherwise). */
@Serializable
data class StartSearchRequestDto(
    val id: String,
    val searchText: String,
    /** Milliseconds. */
    val searchTimeout: Long,
    val responseLimit: Int,
    val fileLimit: Int,
)

/**
 * `GET /api/v0/searches/{id}`. `state` is a comma-separated token set
 * (e.g. `InProgress`, `Completed, ResponseLimitReached`) parsed by StateTokens.
 */
@Serializable
data class SearchStateDto(
    val id: String? = null,
    val searchText: String? = null,
    val state: String? = null,
    val isComplete: Boolean? = null,
    val responseCount: Int? = null,
    val fileCount: Int? = null,
    val lockedFileCount: Int? = null,
    val responses: List<SearchResponseDto>? = null,
    @SerialName("token") val token: Long? = null,
    @SerialName("startedAt") val startedAt: String? = null,
    @SerialName("endedAt") val endedAt: String? = null,
)

/** `GET /api/v0/searches/{id}/responses` array element (SPIKE.md exact field names). */
@Serializable
data class SearchResponseDto(
    val username: String? = null,
    val token: Long? = null,
    val hasFreeUploadSlot: Boolean? = null,
    val queueLength: Int? = null,
    val uploadSpeed: Long? = null,
    val fileCount: Int? = null,
    val files: List<SearchFileDto>? = null,
    val lockedFileCount: Int? = null,
    val lockedFiles: List<SearchFileDto>? = null,
)

/**
 * One peer-shared file. SPIKE.md: has `code`, NO reliable `bitRate`/`format`; some peers
 * send an empty `extension`, so the mapper derives it from the filename.
 */
@Serializable
data class SearchFileDto(
    val filename: String? = null,
    val size: Long? = null,
    val extension: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val length: Int? = null,
    val isLocked: Boolean? = null,
    val code: Int? = null,
)

/** `POST /api/v0/transfers/downloads/batches` request. Top-level `Username` is required by slskd. */
@Serializable
data class EnqueueBatchRequestDto(
    @SerialName("Username") val Username: String,
    @SerialName("Files") val Files: List<EnqueueFileDto>,
    /** Optional provenance so slskd-side search-driven downloads can be attributed. */
    @SerialName("searchId") val searchId: String? = null,
)

@Serializable
data class EnqueueFileDto(
    val filename: String,
    val size: Long,
)

/** `POST /api/v0/users/{username}/directory` request body. */
@Serializable
data class UserDirectoryRequestDto(
    @SerialName("Directory") val Directory: String,
)

/** `GET /api/v0/transfers/downloads` array element. */
@Serializable
data class TransferUserDto(
    val username: String? = null,
    val directories: List<TransferDirectoryDto>? = null,
)

@Serializable
data class TransferDirectoryDto(
    val directory: String? = null,
    val fileCount: Int? = null,
    val files: List<TransferFileDto>? = null,
)

/**
 * One queued/downloaded file (`GET /api/v0/transfers/downloads/...`).
 * `state` is a composite token string such as `Completed, Succeeded`; `remainingTime` is a
 * .NET TimeSpan string.
 */
@Serializable
data class TransferFileDto(
    val id: String? = null,
    val username: String? = null,
    val direction: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val state: String? = null,
    @SerialName("requestedAt") val requestedAt: String? = null,
    @SerialName("enqueuedAt") val enqueuedAt: String? = null,
    @SerialName("startedAt") val startedAt: String? = null,
    @SerialName("endedAt") val endedAt: String? = null,
    @SerialName("bytesTransferred") val bytesTransferred: Long? = null,
    @SerialName("bytesRemaining") val bytesRemaining: Long? = null,
    val averageSpeed: Double? = null,
    val attempts: Int? = null,
    val removed: Boolean? = null,
    @SerialName("elapsedTime") val elapsedTime: String? = null,
    @SerialName("percentComplete") val percentComplete: Double? = null,
    @SerialName("remainingTime") val remainingTime: String? = null,
    val exception: String? = null,
)

/**
 * Element of the POST batches response (201 all queued / 200+207 partial). Field names vary
 * across slskd versions; every candidate spelling is optional and a null `success` with no
 * error counts as success (tolerant).
 */
@Serializable
data class EnqueueResultItemDto(
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("file") val file: String? = null,
    val filename: String? = null,
    val success: Boolean? = null,
    val error: String? = null,
    val message: String? = null,
    val exception: String? = null,
)
