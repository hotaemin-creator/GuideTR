package com.example.guidetr

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress ?: ""
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return "Unknown"
}

class AndroidAudioHandler(private val context: Context) : AudioHandler {
    private val sampleRate = 8000
    private val port = 50005
    override var isTalking by mutableStateOf(false)
    override var isPlayingAudioFile by mutableStateOf(false)
    override var isAudioPaused by mutableStateOf(false)
    
    private var broadcasterThread: Thread? = null
    private var receiverThread: Thread? = null
    private var audioFileThread: Thread? = null

    override fun startBroadcaster() {
        isTalking = true
        broadcasterThread = Thread {
            // ... logic from old MainActivity ...
        }.apply { start() }
    }

    override fun stopBroadcaster() {
        isTalking = false
        broadcasterThread?.interrupt()
    }

    override fun startReceiver() {
       // ... logic from old MainActivity ...
    }

    override fun stopReceiver() {
        receiverThread?.interrupt()
    }

    override fun startFileBroadcaster(uri: String) {
        // ... logic from old MainActivity ...
        // Note: uri is a String here, need to parse to android.net.Uri
    }

    override fun stopFileBroadcaster() {
        isPlayingAudioFile = false
        audioFileThread?.interrupt()
    }

    override fun toggleAudioPause() {
        isAudioPaused = !isAudioPaused
    }

    override fun queryFileName(uri: String): String? {
        val androidUri = android.net.Uri.parse(uri)
        var name: String? = null
        if (androidUri.scheme == "content") {
            try {
                context.contentResolver.query(androidUri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return name ?: androidUri.path?.substringAfterLast('/')
    }
}

@Composable
actual fun rememberAudioHandler(): AudioHandler {
    val context = LocalContext.current
    return remember { AndroidAudioHandler(context) }
}

@Composable
actual fun PickAudioFile(onFileSelected: (String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onFileSelected(it.toString()) }
    }
    LaunchedEffect(Unit) {
        launcher.launch(arrayOf("audio/*"))
    }
}
