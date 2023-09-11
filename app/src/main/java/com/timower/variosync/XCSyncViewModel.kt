package com.timower.variosync

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.NetworkInterface

const val SCAN_TIMEOUT = 50
val URI_PREFERENCE = stringPreferencesKey("DOC_URI")

fun getDocuments(
    contentResolver: ContentResolver, treeUri: Uri, directoryId: String? = null
): List<Document>? {
    val dirID = directoryId ?: DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirID)

    val result = mutableListOf<Document>()

    try {
        val cursor = contentResolver.query(
            childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        ) ?: throw Exception("Unable to access!")

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val name = it.getString(1)
                val mime = it.getString(2)

                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val children = if (isDir) {
                    getDocuments(contentResolver, treeUri, id) ?: emptyList()
                } else {
                    emptyList()
                }
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

                result.add(Document(name, isDir, children, docUri))
            }
        }

        return result
    } catch (e: java.lang.Exception) {
        Log.d(TAG, "Error getting local files!")
        return null
    }
}

data class ProgressState(val value: Float? = null, val message: String? = null)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class XCSyncViewModel(
    private val contentResolver: ContentResolver,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {
    var errorMsg: String? by mutableStateOf("Not Connected")
        private set
    var syncProgress: ProgressState? by mutableStateOf(null)
        private set

    /// TODO: group
    private val contentUri = dataStore.data.map { preferences ->
        val pref = preferences[URI_PREFERENCE]
        Log.d(TAG, "Map content URI $pref!")
        pref?.let { Uri.parse(it) }
    }.onEach { updateLocalFiles(it) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(),
        initialValue = null
    )

    private var localFiles: List<Document>? by mutableStateOf(null)
    val hasLocalFiles =
        snapshotFlow {
            Log.d(TAG, "Snapshot Update local files ${localFiles != null}")
            localFiles
        }.combine(contentUri) { files, uri ->
            Log.d(TAG, "contentUri or local Files update!")
            files != null && uri != null
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    var syncPlan: SyncPlan? by mutableStateOf(null)
        private set

    val interfaces
        get() = NetworkInterface.getNetworkInterfaces().toList()
            .filter { ifc -> ifc.interfaceAddresses.any { it.address.address.size == 4 } }

    var selectedInterface: NetworkInterface by mutableStateOf(interfaces.first())

    private val interfaceAddress
        get() = selectedInterface.interfaceAddresses.firstOrNull { it.address.address.size == 4 }?.address


    var currentAddress: String? by mutableStateOf("192.168.178.60")
    fun hasAddress() = currentAddress != null

    private var job: Job? by mutableStateOf(null)

    fun jobInProgress() = syncProgress != null
    fun cancelJob() {
        job?.cancel()
        job = null
    }

    fun canFindIP() = interfaceAddress != null
    fun findIP() {
        job = viewModelScope.launch {
            try {
                findIP(interfaceAddress!!) { value, msg ->
                    syncProgress = ProgressState(value, msg)
                }?.let { currentAddress = it }
            } finally {
                syncProgress = null
            }
        }
    }

    fun setLocalContentUri(uri: Uri) {
        viewModelScope.launch {
            dataStore.edit {
                it[URI_PREFERENCE] = uri.toString()
                Log.d(TAG, "Save preferences!")
            }
            updateLocalFiles()
        }
    }

    private fun updateLocalFiles(uri: Uri? = null) {
        val contentUriVal = uri ?: contentUri.value ?: return
        Log.d(TAG, "Getting local files: $contentUriVal")
        localFiles = getDocuments(contentResolver, contentUriVal)
    }

    private suspend fun updateRemoteFiles() {
        Log.d(TAG, "Getting remote files")
        if (currentAddress == null || localFiles == null) {
            Log.d(TAG, "Error, current address not set!")
            return
        }

        val files = listRemoteDirs(currentAddress!!)
        errorMsg = files.exceptionOrNull()?.let { it.message ?: "Unknown Error" }
        syncPlan = files.getOrNull()?.let { makePlan(localFiles!!, it) }
    }

    fun refresh() {
        syncProgress = ProgressState()
        job = viewModelScope.launch {
            try {
                updateLocalFiles()
                updateRemoteFiles()
            } finally {
                syncProgress = null
            }
        }
    }

    fun canExecutePlan() = syncPlan != null && !jobInProgress() && currentAddress != null
    fun executePlan() {
        job = viewModelScope.launch {
            try {
                val res = doPlan(contentResolver, currentAddress!!, syncPlan!!) { value, msg ->
                    syncProgress = ProgressState(value, msg)
                }.mapCatching { listRemoteDirs(currentAddress!!).getOrThrow() }
                errorMsg = res.exceptionOrNull()?.let { it.message ?: "Unknown error" }
                syncPlan = res.getOrNull()?.let { makePlan(localFiles!!, it) }
            } finally {
                syncProgress = null
            }
        }
    }

    fun updatePlan(document: Document, action: SyncAction) {
        syncPlan = syncPlan?.let { it.copy(plan = it.plan.plus(document to action)) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[APPLICATION_KEY] as Application)
                XCSyncViewModel(app.contentResolver, app.dataStore)
            }
        }
    }
}