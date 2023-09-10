package com.timower.variosync

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetAddress

const val DEFAULT_TIMEOUT = 2000

suspend fun <Res> withSSHSession(
    ip: String, timeout: Int = DEFAULT_TIMEOUT, block: (CoroutineScope, Session) -> Res
) = withContext(Dispatchers.IO) {
    try {
        val jsch = JSch()

        val session = jsch.getSession("root", ip)
        session.timeout = timeout
        session.setConfig("StrictHostKeyChecking", "no")

        session.connect()
        try {
            return@withContext Result.success(block(this, session))
        } finally {
            session.disconnect()
        }
    } catch (e: Exception) {
        Log.d(TAG, "Exception: $e")
        return@withContext Result.failure(e)
    }
}

suspend fun <Res> withSFTPConnection(
    ip: String, timeout: Int = DEFAULT_TIMEOUT, block: (CoroutineScope, ChannelSftp) -> Res
) = withSSHSession(ip, timeout) { scope, session ->
    val channel = session.openChannel("sftp")
    channel.connect()
    try {
        val sftpChannel = channel as ChannelSftp
        return@withSSHSession block(scope, sftpChannel)
    } finally {
        channel.disconnect()
    }
}

suspend fun findIP(
    baseIP: InetAddress, onProgress: (Float, String) -> Unit
): String? = withContext(Dispatchers.IO) {
    val bytes = baseIP.address
    val len = bytes.size

    for (cur in (1..254).map { it.toByte() }) {
        if (cur == baseIP.address[len - 1]) {
            continue
        }

        if (!isActive) {
            break
        }

        bytes[len - 1] = cur
        val address = InetAddress.getByAddress(bytes)
        val hostAddress = address.hostAddress
        if (address.isReachable(SCAN_TIMEOUT)) {
            if (hostAddress != null && withSSHSession(hostAddress, 500) { _, _ -> }.isSuccess) {
                return@withContext hostAddress
            }
        }

        onProgress(cur.toFloat() / 254, hostAddress ?: address.toString())
    }
    return@withContext null
}