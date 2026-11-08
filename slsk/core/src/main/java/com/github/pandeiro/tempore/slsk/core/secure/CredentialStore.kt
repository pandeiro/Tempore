package com.github.pandeiro.tempore.slsk.core.secure

import kotlinx.serialization.Serializable

interface CredentialStore {
    fun load(): SlskdConfig?

    fun save(config: SlskdConfig)

    fun clear()
}

@Serializable
data class SlskdConfig(
    val baseUrl: String,
    val apiKey: String,
    /** Optional SHA-256 digest (lowercase hex) of the server cert chain root (trust-on-first-use). */
    val pinnedCertSha256: String?,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is SlskdConfig
            && other.baseUrl == baseUrl
            && other.apiKey == apiKey
            && other.pinnedCertSha256 == pinnedCertSha256)

    override fun hashCode(): Int =
        31 * baseUrl.hashCode() + apiKey.hashCode() + (pinnedCertSha256?.hashCode() ?: 0)
}
