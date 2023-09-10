package com.timower.variosync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

const val SCAN_TIMEOUT = 50

const val REMOTE_ROOT = ".xcsoar"
const val STAGING_PATH = ".xcsync.staging"

val SYNCED_EXTENSIONS = arrayOf("xcm", "tsk", "cup")

data class Document(
    val name: String,
    val isDir: Boolean,
    val children: List<Document> = emptyList(),
    val uri: Uri = Uri.EMPTY
)

enum class SyncAction {
    /// Ignore this file, it's already present on the remote
    Ignore,

    /// Missing on remote, push
    Push,

    /// User deselected, skip
    Skip
}

data class SyncPlan(val localFiles: List<Document>, val plan: Map<Document, SyncAction>)

fun makePlan(localFiles: List<Document>, remoteFiles: List<Document>): SyncPlan {
    val plan: MutableMap<Document, SyncAction> = mutableMapOf()

    fun compare(local: List<Document>, remote: List<Document>) {
        val remoteMap = remote.associateBy { it.name }
        local.forEach {
            if (!it.isDir || it.children.isEmpty()) return@forEach

            val remoteDoc = remoteMap[it.name]
            compare(it.children, remoteDoc?.children ?: emptyList())
        }
        local.filter { doc ->
            (doc.isDir && doc.children.any { plan.containsKey(it) }) || SYNCED_EXTENSIONS.any {
                doc.name.endsWith(it, ignoreCase = true)
            }
        }.associateWithTo(plan) {
            if (remoteMap.containsKey(it.name)) SyncAction.Ignore else SyncAction.Push
        }
    }

    compare(localFiles, remoteFiles)

    return SyncPlan(localFiles, plan)
}

suspend fun listRemoteDirs(ip: String) = withSFTPConnection(ip) { scope, chan ->
    fun listDir(directory: String): List<Document> {
        val ls = chan.ls(directory)
        val result = mutableListOf<Document>()
        for (it in ls) {
            if (!scope.isActive) {
                return emptyList()
            }

            val entry = it as? ChannelSftp.LsEntry ?: continue
            if (entry.filename.startsWith(".")) {
                continue
            }

            val children: List<Document> = if (entry.attrs.isDir) {
                chan.cd(directory)
                val res = listDir(entry.filename)
                chan.cd("..")

                res
            } else {
                emptyList()
            }

            result.add(Document(entry.filename, entry.attrs.isDir, children))
        }

        return result
    }

    listDir(REMOTE_ROOT)
}

suspend fun doPlan(
    contentResolver: ContentResolver,
    ip: String,
    plan: SyncPlan,
    onProgress: (Float, String) -> Unit
) =
    withSFTPConnection(ip) { scope, chan ->
        var progress = 0
        val totalProgress = plan.plan.count { !it.key.isDir }
        fun next(name: String) {
            progress += 1
            onProgress(progress.toFloat() / totalProgress, name)
        }
        chan.cd(REMOTE_ROOT)

        fun doDocs(docs: List<Document>) {
            docs.map { it to plan.plan[it] }.filter { it.second != null }
                .filter { it.first.isDir || it.second == SyncAction.Push }
                .forEach { (it, action) ->
                    if (it.isDir) {
                        if (action == SyncAction.Push) {
                            chan.mkdir(it.name)
                        }
                        chan.cd(it.name)
                        doDocs(it.children)
                        chan.cd("..")

                        return@forEach
                    }

                    contentResolver.openInputStream(it.uri)?.use { stream ->
                        val aval = stream.available()
                        chan.put(stream, STAGING_PATH, object : SftpProgressMonitor {
                            var counter: Long = 0
                            override fun init(
                                op: Int, src: String?, dest: String?, max: Long
                            ) {
                            }

                            override fun count(count: Long): Boolean {
                                counter += count
                                onProgress(
                                    (progress + (counter.toFloat() / aval)) / totalProgress,
                                    it.name
                                )
                                return scope.isActive
                            }

                            override fun end() {}
                        })

                        if (scope.isActive) {
                            chan.rename(STAGING_PATH, it.name)
                        }
                    }
                    next(it.name)
                }
        }

        doDocs(plan.localFiles)
    }

fun getDocuments(
    contentResolver: ContentResolver,
    treeUri: Uri,
    directoryId: String? = null
): List<Document> {
    val dirID = directoryId ?: DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirID)

    val result = mutableListOf<Document>()

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
                getDocuments(contentResolver, treeUri, id)
            } else {
                emptyList()
            }
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

            result.add(Document(name, isDir, children, docUri))
        }
    }

    return result
}

data class ProgressState(val value: Float? = null, val message: String? = null)

class XCSyncViewModel : ViewModel() {
    var errorMsg: String? by mutableStateOf("Not Connected")
        private set
    var syncProgress: ProgressState? by mutableStateOf(null)
        private set

    private var localFiles: List<Document> by mutableStateOf(emptyList())

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
                currentAddress =
                    findIP(interfaceAddress!!) { value, msg ->
                        syncProgress = ProgressState(value, msg)
                    }
            } finally {
                syncProgress = null
            }
        }
    }

    fun updateLocalFiles(contentResolver: ContentResolver, localContentUri: Uri) {
        localFiles = getDocuments(contentResolver, localContentUri)
    }

    fun updateRemoteFiles() {
        syncProgress = ProgressState()
        job = viewModelScope.launch {
            try {
                val files = listRemoteDirs(currentAddress!!)
                errorMsg = files.exceptionOrNull()?.let { it.message ?: "Unknown Error" }
                syncPlan = files.getOrNull()?.let { makePlan(localFiles, it) }
            } finally {
                syncProgress = null
            }
        }
    }

    fun refresh(contentResolver: ContentResolver, localContentUri: Uri) {
        updateLocalFiles(contentResolver, localContentUri)
        updateRemoteFiles()
    }

    fun canExecutePlan() = syncPlan != null && !jobInProgress() && currentAddress != null
    fun executePlan(contentResolver: ContentResolver) {
        job = viewModelScope.launch {
            try {
                val res =
                    doPlan(contentResolver, currentAddress!!, syncPlan!!) { value, msg ->
                        syncProgress = ProgressState(value, msg)
                    }.mapCatching { listRemoteDirs(currentAddress!!).getOrThrow() }
                errorMsg = res.exceptionOrNull()?.let { it.message ?: "Unknown error" }
                syncPlan = res.getOrNull()?.let { makePlan(localFiles, it) }
            } finally {
                syncProgress = null
            }
        }
    }

    fun updatePlan(document: Document, action: SyncAction) {
        syncPlan = syncPlan?.let { it.copy(plan = it.plan.plus(document to action)) }
    }
}