package com.timower.variosync

import android.content.ContentResolver
import android.net.Uri
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.isActive


val SYNCED_EXTENSIONS = arrayOf("xcm", "tsk", "cup")

data class Document(
    val name: String,
    val isDir: Boolean,
    val children: List<Document> = emptyList(),
    val uri: Uri = Uri.EMPTY
)

enum class SyncAction {
    Ignore, /// Ignore this file, it's already present on the remote
    Push,   /// Missing on remote, push
    Skip    /// User deselected, skip
}

data class SyncPlan(val localFiles: List<Document>, val plan: Map<Document, SyncAction>)

const val REMOTE_ROOT = ".xcsoar"
const val STAGING_PATH = ".xcsync.staging"

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
) = withSFTPConnection(ip) { scope, chan ->
    var progress = 0
    val totalProgress = plan.plan.count { !it.key.isDir }
    fun next(name: String) {
        progress += 1
        onProgress(progress.toFloat() / totalProgress, name)
    }
    chan.cd(REMOTE_ROOT)

    fun doDocs(docs: List<Document>) {
        docs.map { it to plan.plan[it] }.filter { it.second != null }
            .filter { it.first.isDir || it.second == SyncAction.Push }.forEach { (it, action) ->
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
                                (progress + (counter.toFloat() / aval)) / totalProgress, it.name
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
