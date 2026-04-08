package com.example.guidetr

import androidx.compose.runtime.*

actual fun getLocalIpAddress(): String = "iOS-IP"

class IosAudioHandler : AudioHandler {
    override var isTalking by mutableStateOf(false)
    override var isPlayingAudioFile by mutableStateOf(false)
    override var isAudioPaused by mutableStateOf(false)

    override fun startBroadcaster() { /* iOS implementation needed */ }
    override fun stopBroadcaster() { /* iOS implementation needed */ }
    override fun startReceiver() { /* iOS implementation needed */ }
    override fun stopReceiver() { /* iOS implementation needed */ }
    override fun startFileBroadcaster(uri: String) { /* iOS implementation needed */ }
    override fun stopFileBroadcaster() { isPlayingAudioFile = false }
    override fun toggleAudioPause() { isAudioPaused = !isAudioPaused }
    override fun queryFileName(uri: String): String? = uri.substringAfterLast("/")
}

@Composable
actual fun rememberAudioHandler(): AudioHandler = remember { IosAudioHandler() }

@Composable
actual fun PickAudioFile(onFileSelected: (String) -> Unit) {
    // iOS File Picker implementation would go here using UIDocumentPickerViewController
}
