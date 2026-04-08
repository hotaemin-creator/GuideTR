package com.example.guidetr

import androidx.compose.runtime.Composable

expect fun getLocalIpAddress(): String

interface AudioHandler {
    fun startBroadcaster()
    fun stopBroadcaster()
    fun startReceiver()
    fun stopReceiver()
    fun startFileBroadcaster(uri: String) // Using string representation for URI
    fun stopFileBroadcaster()
    fun queryFileName(uri: String): String?
    val isTalking: Boolean
    val isPlayingAudioFile: Boolean
    val isAudioPaused: Boolean
    fun toggleAudioPause()
}

@Composable
expect fun rememberAudioHandler(): AudioHandler

@Composable
expect fun PickAudioFile(onFileSelected: (String) -> Unit)
