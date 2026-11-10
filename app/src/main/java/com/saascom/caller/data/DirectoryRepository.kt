package com.saascom.caller.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the home screen shows about the list. */
data class SyncInfo(
    val count: Int = 0,
    val lastSyncAt: Long = 0,
    val syncing: Boolean = false,
    val lastError: String? = null,
)

/** Keeps the local client list in step with GET /directory (ETag: most syncs are a cheap 304). */
class DirectoryRepository(
    private val api: ApiClient,
    private val db: DirectoryDb,
    private val settings: SettingsStore,
) {
    private val mutex = Mutex()
    private val _info = MutableStateFlow(SyncInfo(lastSyncAt = settings.lastSyncAt))
    val info: StateFlow<SyncInfo> = _info.asStateFlow()

    /** Reads the stored count (call once at startup, off the main thread). */
    suspend fun refreshCount() = withContext(Dispatchers.IO) {
        val count = db.count()
        _info.update { it.copy(count = count) }
    }

    fun find(phone: String): DirectoryEntry? = db.find(phone)

    suspend fun search(query: String): List<DirectoryEntry> = withContext(Dispatchers.IO) { db.search(query) }

    suspend fun statuses(): List<StatusDto> = withContext(Dispatchers.IO) { db.statuses() }

    /** Throws [ApiException] on failure; the error is also kept in [info] for the UI. */
    suspend fun sync() = mutex.withLock {
        _info.update { it.copy(syncing = true, lastError = null) }
        try {
            withContext(Dispatchers.IO) {
                when (val result = api.directory(settings.directoryEtag)) {
                    DirectoryResult.NotModified -> Unit
                    is DirectoryResult.Updated -> {
                        db.replaceAll(result.body.entries, result.body.statuses)
                        settings.directoryEtag = result.etag
                    }
                }
                settings.lastSyncAt = System.currentTimeMillis()
                _info.update { it.copy(count = db.count(), lastSyncAt = settings.lastSyncAt, syncing = false) }
            }
        } catch (e: Exception) {
            _info.update { it.copy(syncing = false, lastError = e.message) }
            throw e
        }
    }

    /** Logout: the list holds client data, so it must not stay on the phone. */
    fun clear() {
        db.clear()
        settings.clearSync()
        _info.value = SyncInfo()
    }
}
