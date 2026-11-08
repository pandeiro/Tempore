package com.github.pandeiro.tempore.slsk.core.gateway

import com.github.pandeiro.tempore.slsk.core.domain.SearchOptions
import com.github.pandeiro.tempore.slsk.core.domain.SearchProgress
import com.github.pandeiro.tempore.slsk.core.domain.RemoteFile
import com.github.pandeiro.tempore.slsk.core.domain.EnqueueOutcome
import com.github.pandeiro.tempore.slsk.core.domain.SoulseekGateway
import com.github.pandeiro.tempore.slsk.core.domain.TransferGroup
import com.github.pandeiro.tempore.slsk.core.domain.Health
import kotlinx.coroutines.flow.flow

/**
 * Demo/test stand-in for the real gateway. Faithful to the contract but instant.
 */
class FakeSlskdGateway : SoulseekGateway {
    override suspend fun health(): Health = Health(version = "0.26.1.0-fake", isConnected = true, isLoggedIn = true)

    override fun search(query: String, options: SearchOptions) = flow {
        emit(
            SearchProgress(
                searchId = options.searchId ?: "fake",
                stateTokens = listOf("InProgress"),
                isComplete = false,
                responseCount = 1,
                fileCount = 0,
                groups = emptyList(),
            )
        )
    }

    override suspend fun folderContents(username: String, directory: String): List<RemoteFile> = emptyList()

    override suspend fun enqueue(username: String, files: List<RemoteFile>, searchId: String?) =
        EnqueueOutcome(accepted = true, failureCount = 0, failures = emptyList())

    override fun transfers() = flow<List<TransferGroup>> { }

    override suspend fun cancel(username: String, transferId: String, remove: Boolean) {}

    override suspend fun clearCompleted() {}
}
