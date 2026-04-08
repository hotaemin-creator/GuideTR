package com.example.guidetr

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
import androidx.compose.ui.draw.shadow
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
    private var audioNames = mutableStateListOf<String?>(*Array(10) { null })
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
                audioNames[currentSelectingIndex] = queryFileName(it)
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
                val uri = android.net.Uri.parse(uriString)
                audioUris[i] = uri
                audioNames[i] = queryFileName(uri)
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
                    audioNames = audioNames,
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
    
    private fun queryFileName(uri: android.net.Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) name = name?.substring(cut + 1)
        }
        return name
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
    audioNames: List<String?>,
    onExit: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(AppScreen.LEADER) }

    when (currentScreen) {
        AppScreen.HOME -> { /* No longer used */ 
            currentScreen = AppScreen.LEADER
        }
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
            audioNames = audioNames,
            onExit = onExit,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onBack = { /* No back button needed */ }
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
            onBack = { /* No back button needed */ },
            audioNames = emptyList()
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
    audioNames: List<String?>,
    onExit: () -> Unit
) {
   WalkieTalkieApp(onTalkToggled, onStartListening, onStopListening, onSelectAudio, onPlayAudio, onPauseAudio, onStopAudio, isPlayingAudio, isAudioPaused, playingIndex, audioUris, audioNames, onExit)
}

@Composable
fun HomeScreen(
    onLeaderSelected: () -> Unit,
    onMemberSelected: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                )
            )
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "GuideTR Logo",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .size(260.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(100.dp)) // Offset for the large logo

            Surface(
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp)
            ) {
                Text(
                    text = "사용자들은 모두 같은 핫스팟에 연결합니다. 인터넷이 없어도 됩니다.\n\n" +
                           "인솔자는 Leader를 누르고 다른 이들은 Member를 누릅니다. 리더만 말할 수 있습니다.\n\n" +
                           "서로 가까이 있으면 에코가 생길 수 있으니 이어폰을 사용하시기 추천드립니다.",
                    color = Color.White.copy(alpha = 0.8f),
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .padding(end = 8.dp)
                ) {
                    Text("Leader", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }

                Button(
                    onClick = onMemberSelected,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .padding(start = 8.dp)
                ) {
                    Text("Member", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
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
    audioNames: List<String?>,
    onExit: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onBack: () -> Unit
) {
    val discoveryPort = 50006
    val magicWord = "GUIDE_PA_LEADER_HERE"
    
    val fetchedIp = remember { if (isLeader) getLocalIpAddress() else "" }
    var ipAddress by remember { mutableStateOf(fetchedIp) }
    var isMicOn by remember { mutableStateOf(false) }
    var connectedStatus by remember { mutableStateOf(if (isLeader) "Hosting on $fetchedIp" else "Looking for Leader on Network...") }

    DisposableEffect(Unit) {
        onDispose {
            if (isMicOn) {
                isMicOn = false
                onTalkToggled(false)
            }
        }
    }

    LaunchedEffect(isLeader) {
        if (isLeader) {
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
                } catch (e: Exception) { e.printStackTrace() } finally { socket?.close() }
            }
        } else {
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
                                break
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() } finally { socket?.close() }
            }
        }
    }

    DisposableEffect(isLeader) {
        if (!isLeader) onStartListening() else onStopListening()
        onDispose { onStopListening() }
    }

    val buttonScale by animateFloatAsState(
        targetValue = if (isMicOn) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "buttonScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isMicOn) Color(0xFFEF4444) else Color(0xFF3B82F6),
        animationSpec = tween(durationMillis = 300),
        label = "buttonColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1D5D68))
    ) {
        // MODERN HEADER
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size(132.dp)
                )

                if (isLeader) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = {
                            if (!isPlayingAudio || isAudioPaused) {
                                isMicOn = !isMicOn
                                onTalkToggled(isMicOn)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlayingAudio && !isAudioPaused) Color(0xFF334155) else buttonColor
                        ),
                        shape = RoundedCornerShape(32.dp),
                        modifier = Modifier
                            .size(132.dp)
                            .scale(buttonScale)
                            .shadow(if (isMicOn) 20.dp else 4.dp, RoundedCornerShape(32.dp), spotColor = buttonColor),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = if (isMicOn) android.R.drawable.stat_notify_chat else android.R.drawable.ic_btn_speak_now),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isPlayingAudio && !isAudioPaused) "OFF" else if (isMicOn) "TALK ON" else "PRESS TO\nTALK",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // CONTENT
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (isLeader) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Audio Library",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )

                        val chunkedAudio = (0..9).chunked(2)
                        for (rowIndex in chunkedAudio.indices) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                for (itemIndex in chunkedAudio[rowIndex]) {
                                    val uri = audioUris.getOrNull(itemIndex)
                                    val hasUri = uri != null
                                    val isThisSlotPlaying = isPlayingAudio && playingIndex == itemIndex
                                    
                                    Surface(
                                        color = if (isThisSlotPlaying) Color(0xFF3B82F6).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                IconButton(
                                                    onClick = { onSelectAudio(itemIndex) },
                                                    modifier = Modifier.size(40.dp).background(if (hasUri) Color(0xFF10B981) else Color.White.copy(alpha = 0.1f), CircleShape)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(id = if (hasUri) android.R.drawable.ic_menu_save else android.R.drawable.ic_input_add),
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                                
                                                Spacer(modifier = Modifier.width(8.dp))
                                                
                                                IconButton(
                                                    onClick = {
                                                        if (isThisSlotPlaying) {
                                                            if (isAudioPaused) onPauseAudio() else onPauseAudio() 
                                                        } else if (hasUri) {
                                                            if (isPlayingAudio) onStopAudio()
                                                            onPlayAudio(uri!!, itemIndex)
                                                        }
                                                    },
                                                    enabled = hasUri,
                                                    modifier = Modifier.size(40.dp).background(if (isThisSlotPlaying) Color(0xFFF59E0B) else Color(0xFF3B82F6), CircleShape)
                                                ) {
                                                    Text(if (isThisSlotPlaying && !isAudioPaused) "⏸" else "▶", color = Color.White)
                                                }
                                            }
                                            
                                            if (hasUri) {
                                                Text(
                                                    text = audioNames.getOrNull(itemIndex) ?: "Unknown",
                                                    color = Color.White.copy(alpha = 0.6f),
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(60.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(240.dp)
                        .scale(buttonScale)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                colors = listOf(Color(0xFF3B82F6), Color(0xFF0F172A), Color(0xFF3B82F6))
                            ),
                            shape = CircleShape
                        )
                        .padding(4.dp)
                        .background(Color(0xFF0F172A), CircleShape)
                ) {
                    Text(
                        text = if (ipAddress.isNotEmpty()) "LISTENING" else "CONNECTING",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.width(200.dp).height(56.dp)
            ) {
                Text("DISCONNECT", color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
