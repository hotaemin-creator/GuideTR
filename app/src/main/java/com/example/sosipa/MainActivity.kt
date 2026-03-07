package com.example.sosipa

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    // Audio Settings
    private val sampleRate = 8000
    private val port = 50005
    private var isTalking = false
    private var broadcasterThread: Thread? = null
    private var receiverThread: Thread? = null
    
    // Audio File State
    private var isPlayingAudioFile = mutableStateOf(false)
    private var isAudioPaused = mutableStateOf(false)
    private var currentlyPlayingIndex = mutableStateOf<Int>(-1)
    private var audioFileThread: Thread? = null
    
    // 10 Audio Slots
    private var audioUris = mutableStateListOf<android.net.Uri?>(*Array(10) { null })
    private var currentSelectingIndex = -1

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Microphone permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    private val selectAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            if (currentSelectingIndex in 0..9) {
                try {
                    contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                audioUris[currentSelectingIndex] = it
                val sharedPref = getSharedPreferences("AudioPrefs", android.content.Context.MODE_PRIVATE)
                with (sharedPref.edit()) {
                    putString("AUDIO_URI_$currentSelectingIndex", it.toString())
                    apply()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Permissions on startup
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        
        // Load initial state from SharedPreferences
        val sharedPref = getSharedPreferences("AudioPrefs", android.content.Context.MODE_PRIVATE)
        for (i in 0..9) {
            val uriString = sharedPref.getString("AUDIO_URI_$i", null)
            if (uriString != null) {
                audioUris[i] = android.net.Uri.parse(uriString)
            }
        }

        setContent {
            WalkieTalkieTheme {
                WalkieTalkieScreen(
                    onTalkToggled = { isOn -> 
                        if (isOn) startBroadcaster() else stopBroadcaster() 
                    },
                    onStartListening = { startReceiver() },
                    onStopListening = { stopReceiver() },
                    onSelectAudio = { index -> 
                        currentSelectingIndex = index
                        selectAudioLauncher.launch(arrayOf("audio/*")) 
                    },
                    onPlayAudio = { uri, index -> 
                        currentlyPlayingIndex.value = index
                        startFileBroadcaster(uri) 
                    },
                    onPauseAudio = {
                        isAudioPaused.value = !isAudioPaused.value
                    },
                    onStopAudio = { 
                        stopFileBroadcaster() 
                        currentlyPlayingIndex.value = -1
                    },
                    isPlayingAudio = isPlayingAudioFile.value,
                    isAudioPaused = isAudioPaused.value,
                    playingIndex = currentlyPlayingIndex.value,
                    audioUris = audioUris,
                    onExit = {
                        finishAffinity()
                        exitProcess(0)
                    }
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBroadcaster() {
        if (isPlayingAudioFile.value && !isAudioPaused.value) {
            Toast.makeText(this, "Microphone disabled while playing audio", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        isTalking = true

        broadcasterThread = Thread {
            var socket: DatagramSocket? = null
            var recorder: AudioRecord? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                
                // Broadcast to the entire local network
                val address = InetAddress.getByName("255.255.255.255")

                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val buffer = ByteArray(bufferSize)
                recorder.startRecording()

                while (isTalking) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    val packet = DatagramPacket(buffer, read, address, port)
                    socket.send(packet)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                recorder?.apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        stop()
                        release()
                    }
                }
                socket?.close()
            }
        }.apply { start() }
    }

    private fun stopBroadcaster() {
        isTalking = false
        broadcasterThread?.interrupt()
        broadcasterThread = null
    }

    private fun startReceiver() {
        receiverThread = Thread {
            var socket: DatagramSocket? = null
            var speaker: AudioTrack? = null
            try {
                socket = DatagramSocket(port)
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                speaker = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )

                val buffer = ByteArray((bufferSize).coerceAtLeast(1024))
                speaker.play()

                while (!Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    speaker.write(packet.data, 0, packet.length)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                speaker?.apply {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        try { stop() } catch(e: Exception){}
                        try { release() } catch(e: Exception){}
                    }
                }
                socket?.close()
            }
        }.apply { start() }
    }

    private fun stopReceiver() {
        receiverThread?.interrupt()
        receiverThread = null
    }

    private fun startFileBroadcaster(uri: android.net.Uri) {
        if (isPlayingAudioFile.value) return
        isPlayingAudioFile.value = true
        isAudioPaused.value = false
        
        // Disable mic broadcaster if active
        if (isTalking) {
            stopBroadcaster()
        }
        
        audioFileThread = Thread {
            // Very simplified mock downsampling/broadcasting loop for demonstration
            // Real implementation requires MediaExtractor + MediaCodec + Resampler
            // Using existing logic structure to stay within code lengths constraints.
            var socket: DatagramSocket? = null
            var speaker: AudioTrack? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val address = InetAddress.getByName("255.255.255.255")

                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                speaker = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
                speaker.play()

                // Simulating audio stream data from file (this part should read from file via Audio Decoder)
                // For simplicity of UDP transmission, we'll open content resolver as InputStream.
                val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                val fd = parcelFileDescriptor?.fileDescriptor
                
                if (fd != null) {
                   val extractor = android.media.MediaExtractor()
                   extractor.setDataSource(fd)
                   var audioTrackIndex = -1
                   for (i in 0 until extractor.trackCount) {
                       val format = extractor.getTrackFormat(i)
                       val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                       if (mime?.startsWith("audio/") == true) {
                           audioTrackIndex = i
                           break
                       }
                   }
                   
                   if (audioTrackIndex >= 0) {
                      extractor.selectTrack(audioTrackIndex)
                      val format = extractor.getTrackFormat(audioTrackIndex)
                      val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                      val fileSampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                      val channelCount = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                      val encoding = if (format.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                          format.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
                      } else {
                          AudioFormat.ENCODING_PCM_16BIT
                      }
                      
                      val codec = android.media.MediaCodec.createDecoderByType(mime!!)
                      codec.configure(format, null, null, 0)
                      codec.start()
                      
                      val info = android.media.MediaCodec.BufferInfo()
                      var isInputEOS = false
                      var isOutputEOS = false
                      val sampleRateRatio = fileSampleRate.toDouble() / sampleRate
                      
                      // Used for timing playback to real time
                      var startTimeUs = -1L
                      var pauseTimeOffsetUs = 0L
                      
                      while (isPlayingAudioFile.value && !isOutputEOS) {
                          if (isAudioPaused.value) {
                              // If paused, just sleep thread, update pause offset, and prevent reading
                              try {
                                  Thread.sleep(100)
                              } catch (e: InterruptedException) {
                                  break
                              }
                              // We need to keep pushing the effective start time forward while paused
                              if (startTimeUs != -1L) {
                                  startTimeUs += 1000 * 100 // add 100ms
                              }
                              continue
                          }
                          
                          if (!isInputEOS) {
                              val inIndex = codec.dequeueInputBuffer(10000)
                              if (inIndex >= 0) {
                                  val buffer = codec.getInputBuffer(inIndex)
                                  val sampleSize = extractor.readSampleData(buffer!!, 0)
                                  if (sampleSize < 0) {
                                      codec.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                      isInputEOS = true
                                  } else {
                                      codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                      extractor.advance()
                                  }
                              }
                          }
                          
                          var outIndex = codec.dequeueOutputBuffer(info, 10000)
                          while (outIndex >= 0) {
                              
                              if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                  codec.releaseOutputBuffer(outIndex, false)
                                  outIndex = codec.dequeueOutputBuffer(info, 10000)
                                  continue
                              }
                              
                              if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                  isOutputEOS = true
                              }

                              if (info.size != 0) {
                                  if (startTimeUs == -1L) {
                                      startTimeUs = System.nanoTime() / 1000
                                  }
                                  
                                  val outBuffer = codec.getOutputBuffer(outIndex)
                                  val chunk = ByteArray(info.size)
                                  outBuffer?.get(chunk)
                                  outBuffer?.clear()
                                  
                                  // 1. Stereo to Mono Conversion (if needed)
                                  val monoChunk = if (channelCount == 2) {
                                      val result = ByteArray(chunk.size / 2)
                                      for (i in 0 until chunk.size / 4) {
                                          result[i * 2] = chunk[i * 4]
                                          result[i * 2 + 1] = chunk[i * 4 + 1]
                                          // Note: this just perfectly drops the Right channel.
                                          // Averaging both channels is computationally heavier but 'better'.
                                      }
                                      result
                                  } else {
                                      chunk
                                  }
                                  
                                  // 2. Downsample (Naive drop sampling from whatever rate to 8000Hz)
                                  val resampledLength = (monoChunk.size / 2 / sampleRateRatio).toInt() * 2
                                  val resampledChunk = ByteArray(resampledLength)
                                  
                                  var dstIndex = 0
                                  var srcIndexTime = 0.0
                                  while (dstIndex < resampledLength && (srcIndexTime.toInt() * 2 + 1) < monoChunk.size) {
                                      val idx = srcIndexTime.toInt() * 2
                                      resampledChunk[dstIndex] = monoChunk[idx]
                                      resampledChunk[dstIndex + 1] = monoChunk[idx + 1]
                                      dstIndex += 2
                                      srcIndexTime += sampleRateRatio
                                  }
                                  
                                  // Calculate Time Delay for Real-Time Streaming
                                  // Time to wait to keep playback in real-time
                                  val presentationTimeUs = info.presentationTimeUs
                                  val systemTimeUs = (System.nanoTime() / 1000) - startTimeUs
                                  val delayUs = presentationTimeUs - systemTimeUs
                                  if (delayUs > 0) {
                                      try {
                                          Thread.sleep(delayUs / 1000, ((delayUs % 1000) * 1000).toInt())
                                      } catch (e: InterruptedException) {
                                          break
                                      }
                                  }

                                  // Play Locally
                                  speaker.write(resampledChunk, 0, dstIndex)
                                  
                                  // Broadcast
                                  // UDP has constraints on packet size so we chunk
                                  var offset = 0
                                  val maxUdpPayload = 1024
                                  while(offset < dstIndex && isPlayingAudioFile.value) {
                                      val size = minOf(maxUdpPayload, dstIndex - offset)
                                      val packet = DatagramPacket(resampledChunk, offset, size, address, port)
                                      socket.send(packet)
                                      offset += size
                                  }
                              }
                              codec.releaseOutputBuffer(outIndex, false)
                              outIndex = codec.dequeueOutputBuffer(info, 10000)
                          }
                      }
                      codec.stop()
                      codec.release()
                      extractor.release()
                   }
                   parcelFileDescriptor.close()
                }
            } catch (e: Exception) {
               e.printStackTrace()
            } finally {
                socket?.close()
                speaker?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        try { it.stop() } catch(e: Exception){}
                        try { it.release() } catch(e: Exception){}
                    }
                }
                isPlayingAudioFile.value = false
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Audio Playback Finished", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { start() }
    }
    
    private fun stopFileBroadcaster() {
        isPlayingAudioFile.value = false
        audioFileThread?.interrupt()
        audioFileThread = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBroadcaster()
        stopReceiver()
        stopFileBroadcaster()
    }
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            // Ignore loopback and inactive interfaces
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress ?: ""
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "Could not find IP"
}

// State Enum for Navigation
enum class AppScreen {
    HOME, LEADER, MEMBER
}

@Composable
fun WalkieTalkieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            primary = Color(0xFF007AFF), // Flat Blue to match the new logo
            onPrimary = Color.White,
            secondary = Color(0xFF555555),
            onSecondary = Color.White
        ),
        content = content
    )
}

@Composable
fun WalkieTalkieApp(
    onTalkToggled: (Boolean) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onSelectAudio: (Int) -> Unit,
    onPlayAudio: (android.net.Uri, Int) -> Unit,
    onPauseAudio: () -> Unit,
    onStopAudio: () -> Unit,
    isPlayingAudio: Boolean,
    isAudioPaused: Boolean,
    playingIndex: Int,
    audioUris: List<android.net.Uri?>,
    onExit: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

    when (currentScreen) {
        AppScreen.HOME -> HomeScreen(
            onLeaderSelected = { currentScreen = AppScreen.LEADER },
            onMemberSelected = { currentScreen = AppScreen.MEMBER }
        )
        AppScreen.LEADER -> ConnectionScreen(
            isLeader = true,
            onTalkToggled = onTalkToggled,
            onSelectAudio = onSelectAudio,
            onPlayAudio = onPlayAudio,
            onPauseAudio = onPauseAudio,
            onStopAudio = onStopAudio,
            isPlayingAudio = isPlayingAudio,
            isAudioPaused = isAudioPaused,
            playingIndex = playingIndex,
            audioUris = audioUris,
            onExit = onExit,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onBack = { currentScreen = AppScreen.HOME }
        )
        AppScreen.MEMBER -> ConnectionScreen(
            isLeader = false,
            onTalkToggled = onTalkToggled,
            onSelectAudio = {},
            onPlayAudio = { _, _ -> },
            onPauseAudio = {},
            onStopAudio = {},
            isPlayingAudio = false,
            isAudioPaused = false,
            playingIndex = -1,
            audioUris = emptyList(),
            onExit = onExit,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onBack = { currentScreen = AppScreen.HOME }
        )
    }
}

// Wrapper Composable
@Composable
fun WalkieTalkieScreen(
    onTalkToggled: (Boolean) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onSelectAudio: (Int) -> Unit,
    onPlayAudio: (android.net.Uri, Int) -> Unit,
    onPauseAudio: () -> Unit,
    onStopAudio: () -> Unit,
    isPlayingAudio: Boolean,
    isAudioPaused: Boolean,
    playingIndex: Int,
    audioUris: List<android.net.Uri?>,
    onExit: () -> Unit
) {
   WalkieTalkieApp(onTalkToggled, onStartListening, onStopListening, onSelectAudio, onPlayAudio, onPauseAudio, onStopAudio, isPlayingAudio, isAudioPaused, playingIndex, audioUris, onExit)
}

@Composable
fun HomeScreen(
    onLeaderSelected: () -> Unit,
    onMemberSelected: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ancommnew),
                contentDescription = "ANComm Logo",
                modifier = Modifier
                    .size(100.dp)
                    .padding(bottom = 32.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp)
            ) {
                Text(
                    text = "사용자들은 모두 같은 핫스팟에 연결합니다. 인터넷이 없어도 됩니다.\n\n" +
                           "인솔자는 Leader를 누르고 다른 이들은 Member를 누릅니다. 리더만 말할 수 있습니다.\n\n" +
                           "서로 가까이 있으면 에코가 생길 수 있으니 이어폰을 사용하시기 추천드립니다.",
                    color = Color.LightGray,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = onLeaderSelected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(60.dp).padding(end = 8.dp)
                ) {
                    Text("Leader", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onMemberSelected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(60.dp).padding(start = 8.dp)
                ) {
                    Text("Member", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ConnectionScreen(
    isLeader: Boolean,
    onTalkToggled: (Boolean) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onPlayAudio: (android.net.Uri, Int) -> Unit,
    onPauseAudio: () -> Unit,
    onStopAudio: () -> Unit,
    isPlayingAudio: Boolean,
    isAudioPaused: Boolean,
    playingIndex: Int,
    audioUris: List<android.net.Uri?>,
    onExit: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onBack: () -> Unit
) {
    val discoveryPort = 50006
    val magicWord = "GUIDE_PA_LEADER_HERE"
    
    // Auto-fetch if Leader. If Member, start empty and auto-fill when broadcast found.
    val fetchedIp = remember { if (isLeader) getLocalIpAddress() else "" }
    var ipAddress by remember { mutableStateOf(fetchedIp) }
    var isMicOn by remember { mutableStateOf(false) } // Toggle state
    var connectedStatus by remember { mutableStateOf(if (isLeader) "Hosting on $fetchedIp" else "Looking for Leader on Network...") }

    // Stop mic when leaving current screen context
    DisposableEffect(Unit) {
        onDispose {
            if (isMicOn) {
                isMicOn = false
                onTalkToggled(false)
            }
        }
    }

    // ----------------------------------------------------
    // UDP Broadcaster / Listener Logic for Auto-Discovery
    // ----------------------------------------------------
    LaunchedEffect(isLeader) {
        if (isLeader) {
            // LEADER: Broadcast presence to network every 2 seconds
            launch(Dispatchers.IO) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.broadcast = true
                    val message = magicWord.toByteArray()
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    
                    while (true) {
                        val packet = DatagramPacket(message, message.size, broadcastAddr, discoveryPort)
                        socket.send(packet)
                        delay(2000)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    socket?.close()
                }
            }
        } else {
            // MEMBER: Listen for Leader's broadcast
            launch(Dispatchers.IO) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket(discoveryPort)
                    socket.broadcast = true
                    val buffer = ByteArray(256)
                    
                    while (ipAddress.isEmpty()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        
                        val receivedMsg = String(packet.data, 0, packet.length).trim()
                        if (receivedMsg == magicWord) {
                            val leaderIp = packet.address.hostAddress
                            if (leaderIp != null) {
                                ipAddress = leaderIp
                                connectedStatus = "Connected to Leader"
                                break // Stop checking once found
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    socket?.close()
                }
            }
        }
    }

    DisposableEffect(isLeader) {
        if (!isLeader) {
            onStartListening()
        } else {
            onStopListening()
        }
        onDispose {
            onStopListening()
        }
    }

    val buttonScale by animateFloatAsState(
        targetValue = if (isMicOn) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "buttonScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isMicOn) Color(0xFFFF3366) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 200),
        label = "buttonColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Back Button
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Text("< Back", color = Color.Gray, fontSize = 16.sp)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 64.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Image(
                painter = painterResource(id = R.drawable.ancommnew),
                contentDescription = "ANComm Logo",
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = if (isLeader) 8.dp else 24.dp)
            )

            if (isLeader) {
                Text(
                    text = "You are the leader",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }

            Text(
                text = connectedStatus,
                color = if (connectedStatus.contains("Connected")) Color(0xFF00FF88) else Color.LightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            if (isLeader) {
                // Toggle Mic Button
                Button(
                    onClick = {
                        if (!isPlayingAudio || isAudioPaused) {
                            isMicOn = !isMicOn
                            onTalkToggled(isMicOn)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlayingAudio && !isAudioPaused) Color.DarkGray else buttonColor
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(180.dp)
                        .scale(buttonScale)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = if (isPlayingAudio && !isAudioPaused) "MIC OFF\n(AUDIO PLAYING)" else if (isMicOn) "SPEAKER ON" else "SPEAKER OFF",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Audio Clips",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 10 Audio Selectors in 2 Columns
                val chunkedAudio = (0..9).chunked(2)
                for (rowIndex in chunkedAudio.indices) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (itemIndex in chunkedAudio[rowIndex]) {
                            val uri = audioUris.getOrNull(itemIndex)
                            val hasUri = uri != null
                            val isThisSlotPlaying = isPlayingAudio && playingIndex == itemIndex
                            
                            Row(
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { onSelectAudio(itemIndex) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isThisSlotPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    ),
                                    modifier = Modifier.weight(1f).height(45.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(if (hasUri) "Loaded ${itemIndex + 1}" else "Audio ${itemIndex + 1}", fontSize = 12.sp)
                                }
                                
                                Spacer(modifier = Modifier.width(4.dp))
                                
                                if (isThisSlotPlaying) {
                                    // SHOW PAUSE AND STOP BUTTONS
                                    Button(
                                        onClick = { 
                                            onPauseAudio() 
                                            if (!isAudioPaused) {
                                                // Transitioning to Paused: Turn Mic ON
                                                if (!isMicOn) {
                                                    isMicOn = true
                                                    onTalkToggled(true)
                                                }
                                            } else {
                                                // Transitioning to Play: Turn Mic OFF
                                                if (isMicOn) {
                                                    isMicOn = false
                                                    onTalkToggled(false)
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isAudioPaused) Color.LightGray else Color(0xFFFF9800)
                                        ),
                                        modifier = Modifier.size(45.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(if (isAudioPaused) "▶" else "⏸", fontSize = 16.sp)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Button(
                                        onClick = { 
                                            onStopAudio() 
                                            if (isMicOn) {
                                                isMicOn = false
                                                onTalkToggled(false)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                        modifier = Modifier.size(45.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("■", fontSize = 16.sp)
                                    }
                                } else {
                                    // SHOW PLAY BUTTON
                                    Button(
                                        onClick = {
                                            if (isPlayingAudio) {
                                                onStopAudio() // stop whatever else is playing
                                            }
                                            if (hasUri) {
                                                // Ensure Mic is off before playing audio
                                                if (isMicOn) {
                                                    isMicOn = false
                                                    onTalkToggled(false)
                                                }
                                                onPlayAudio(uri!!, itemIndex)
                                            }
                                        },
                                        enabled = hasUri && !isPlayingAudio,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            disabledContainerColor = Color.DarkGray
                                        ),
                                        modifier = Modifier.size(45.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("▶", fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Members just get a nice "Listening" visual instead of a button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(220.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF222222))
                ) {
                    Text(
                        text = if (ipAddress.isNotEmpty()) "LISTENING..." else "WAITING...",
                        color = if (ipAddress.isNotEmpty()) Color(0xFF00FF88) else Color.Gray,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.width(160.dp)
            ) {
                Text(
                    text = "DISCONNECT",
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
