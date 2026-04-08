package com.example.guidetr

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.russhwolf.multiplatformsettings.Settings
import com.russhwolf.multiplatformsettings.get
import com.russhwolf.multiplatformsettings.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import guidetr.composeapp.generated.resources.Res
import guidetr.composeapp.generated.resources.app_logo

enum class AppScreen {
    HOME, LEADER, MEMBER
}

@Composable
fun App() {
    val audioHandler = rememberAudioHandler()
    val settings = remember { Settings() }
    
    val audioUris = remember { mutableStateListOf<String?>(*Array(10) { null }) }
    val audioNames = remember { mutableStateListOf<String?>(*Array(10) { null }) }
    var currentSelectingIndex by remember { mutableStateOf(-1) }
    var showFilePicker by remember { mutableStateOf(false) }

    // Load initial state
    LaunchedEffect(Unit) {
        for (i in 0..9) {
            val uri = settings.getStringOrNull("AUDIO_URI_$i")
            if (uri != null) {
                audioUris[i] = uri
                audioNames[i] = audioHandler.queryFileName(uri)
            }
        }
    }

    WalkieTalkieTheme {
        WalkieTalkieScreen(
            audioHandler = audioHandler,
            audioUris = audioUris,
            audioNames = audioNames,
            onSelectAudio = { index ->
                currentSelectingIndex = index
                showFilePicker = true
            }
        )
    }

    if (showFilePicker) {
        PickAudioFile { uri ->
            showFilePicker = false
            if (currentSelectingIndex in 0..9) {
                audioUris[currentSelectingIndex] = uri
                audioNames[currentSelectingIndex] = audioHandler.queryFileName(uri)
                settings["AUDIO_URI_$currentSelectingIndex"] = uri
            }
        }
    }
}

@Composable
fun WalkieTalkieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF1D5D68),
            surface = Color(0xFF26707B),
            primary = Color(0xFFFFFFFF),
            onPrimary = Color(0xFF1D5D68),
            secondary = Color(0xFF164851),
            onSecondary = Color.White
        ),
        content = content
    )
}

@Composable
fun WalkieTalkieScreen(
    audioHandler: AudioHandler,
    audioUris: List<String?>,
    audioNames: List<String?>,
    onSelectAudio: (Int) -> Unit
) {
    var currentScreen by remember { mutableStateOf(AppScreen.LEADER) }

    when (currentScreen) {
        AppScreen.HOME -> { currentScreen = AppScreen.LEADER }
        AppScreen.LEADER -> ConnectionScreen(
            isLeader = true,
            audioHandler = audioHandler,
            audioUris = audioUris,
            audioNames = audioNames,
            onSelectAudio = onSelectAudio,
            onBack = {}
        )
        AppScreen.MEMBER -> ConnectionScreen(
            isLeader = false,
            audioHandler = audioHandler,
            audioUris = emptyList(),
            audioNames = emptyList(),
            onSelectAudio = {},
            onBack = {}
        )
    }
}

@Composable
fun ConnectionScreen(
    isLeader: Boolean,
    audioHandler: AudioHandler,
    audioUris: List<String?>,
    audioNames: List<String?>,
    onSelectAudio: (Int) -> Unit,
    onBack: () -> Unit
) {
    val discoveryPort = 50006
    val magicWord = "GUIDE_PA_LEADER_HERE"
    
    val fetchedIp = remember { if (isLeader) getLocalIpAddress() else "" }
    var ipAddress by remember { mutableStateOf(fetchedIp) }
    var isMicOn by remember { mutableStateOf(false) }
    var connectedStatus by remember { mutableStateOf(if (isLeader) "Hosting on $fetchedIp" else "Looking for Leader on Network...") }

    // Navigation and Discovery logic (simplified for KMP)
    // Note: Actual UDP discovery would need Multiplatform Sockets or Native implementation
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Upper UI Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Image(
                painter = painterResource(Res.drawable.app_logo),
                contentDescription = "Logo",
                modifier = Modifier.size(60.dp).clip(CircleShape)
            )
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isLeader) "LEADER MODE" else "MEMBER MODE",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isMicOn || audioHandler.isPlayingAudioFile) Color.Red else Color.Green)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = connectedStatus,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        if (isLeader) {
            // Leader-specific controls
            BigTalkButton(
                isTalking = isMicOn,
                onToggle = {
                    isMicOn = !isMicOn
                    if (isMicOn) audioHandler.startBroadcaster() else audioHandler.stopBroadcaster()
                }
            )
            
            Spacer(modifier = Modifier.height(40.dp))
            
            Text(
                "AUDIO LIBRARY",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Audio Grid
            for (i in 0 until 5) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AudioSlot(
                        index = i * 2,
                        name = audioNames.getOrNull(i * 2),
                        isSelected = audioUris.getOrNull(i * 2) != null,
                        isPlaying = audioHandler.isPlayingAudioFile && audioHandler.isAudioPaused == false, // Simplified
                        onSelect = { onSelectAudio(i * 2) },
                        onPlay = { audioUris[i * 2]?.let { audioHandler.startFileBroadcaster(it) } },
                        onStop = { audioHandler.stopFileBroadcaster() }
                    )
                    AudioSlot(
                        index = i * 2 + 1,
                        name = audioNames.getOrNull(i * 2 + 1),
                        isSelected = audioUris.getOrNull(i * 2 + 1) != null,
                        isPlaying = false,
                        onSelect = { onSelectAudio(i * 2 + 1) },
                        onPlay = { audioUris[i * 2 + 1]?.let { audioHandler.startFileBroadcaster(it) } },
                        onStop = { audioHandler.stopFileBroadcaster() }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        } else {
            // Member UI (Real-time EQ visualization)
             EQVisualization(active = true) // Placeholder for member EQ
        }
    }
}

@Composable
fun BigTalkButton(isTalking: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(if (isTalking) 1.15f else 1.0f)
    val color by animateColorAsState(if (isTalking) Color(0xFFFF5252) else Color(0xFF26707B))
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .scale(scale)
            .shadow(if (isTalking) 30.dp else 10.dp, CircleShape, ambientColor = color, spotColor = color)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { 
                    onToggle()
                    tryAwaitRelease()
                    onToggle()
                })
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (isTalking) "TALKING..." else "PUSH TO TALK",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun AudioSlot(
    index: Int,
    name: String?,
    isSelected: Boolean,
    isPlaying: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = if (isPlaying) 0.2f else 0.05f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.weight(1f).height(100.dp).clickable { if (!isSelected) onSelect() else onPlay() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("${index + 1}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            Text(name ?: "Empty", color = Color.White, fontSize = 14.sp, maxLines = 1)
            if (isSelected) {
                IconButton(onClick = if (isPlaying) onStop else onPlay) {
                    // Icon placeholder
                }
            }
        }
    }
}

@Composable
fun EQVisualization(active: Boolean) {
    // EQ implementation...
}
