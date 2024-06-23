package pl.woyteck.ragnarok

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.MotionEvent
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var textView: TextView
    private lateinit var button: Button
    private lateinit var forgetButton: Button
    private lateinit var speechRecognizer: SpeechRecognizer

    private val mediaPlayer = MediaPlayer()
    private val audioQueue: Queue<File> = LinkedList()
    private var isMediaPlayerPrepared = false
    private var isPreparing = false
    private var fileCounter = 1

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private var conversationId: String? = null
    private var apiBaseUrl: String = "http://192.168.20.55:4000"
    private var baseUrl: String = "ws://192.168.20.55:8080/ws"
    private lateinit var webSocket: WebSocket

    init {
        mediaPlayer.setOnPreparedListener {
            isMediaPlayerPrepared = true
            mediaPlayer.start()
        }
        mediaPlayer.setOnCompletionListener {
            onAudioCompletion()
        }
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        button = findViewById(R.id.button)
        forgetButton = findViewById(R.id.forgetButton)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startListening()
                    animateButton(true)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopListening()
                    animateButton(false)
                    true
                }
                else -> false
            }
        }

        forgetButton.setOnClickListener {
            conversationId = null
            getConversationId()
            textView.text = getString(R.string.ropoczeto_nowa_rozmowe)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        getConversationId()
        initiateWebSocket()
    }

    private fun initiateWebSocket() {
        val request = Request.Builder().url(baseUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    textView.text = "Connected to websocket server"
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerResponse(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    textView.text = "Websocket connection error"
                }
            }
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                textView.text = getString(R.string.blad_rozpoznawania_mowy)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.get(0) ?: getString(R.string.nie_rozpoznano_mowy)
                textView.text = recognizedText
                sendWebSocketMessage(recognizedText)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer.stopListening()
    }

    private fun animateButton(isPressed: Boolean) {
        val scale = if (isPressed) 1.2f else 1.0f
        val animation = ScaleAnimation(
            1.0f, scale, 1.0f, scale,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        )
        animation.duration = 100
        animation.fillAfter = true
        button.startAnimation(animation)
    }

    private fun getConversationId() {
        val request = Request.Builder()
            .url("$apiBaseUrl/api/v1/conversation")
            .addHeader("Content-Type", "application/json")
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        textView.text = getString(R.string.post_request_failed, e.message)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string() ?: "No response body"
                        runOnUiThread {
                            textView.text = getString(R.string.error, print(response.code), responseBody)
                        }
                    } else {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            val jsonResponse = JSONObject(responseBody)
                            conversationId = jsonResponse.getString("id")
                        }
                    }
                }
            })
        }
    }

    private fun sendWebSocketMessage(recognizedText: String) {
        val json = JSONObject().apply {
            put("text", recognizedText)
            put("conversationId", conversationId)
        }.toString()
        webSocket.send(json)
    }

    private fun handleServerResponse(message: String) {
        val jsonResponse = JSONObject(message)
        val audioBase64 = jsonResponse.getString("audio")
        val audioBytes = Base64.getDecoder().decode(audioBase64)
        val audioFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "response_audio$fileCounter.mp3")
        fileCounter++

        FileOutputStream(audioFile).use { output ->
            output.write(audioBytes)
        }

        enqueueAudio(audioFile)
    }

    private fun enqueueAudio(audioData: File) {
        audioQueue.offer(audioData)
        if (audioQueue.size == 1 && !isMediaPlayerPrepared && !isPreparing) {
            prepareAndStartMediaPlayer()
        }
    }

    private fun prepareAndStartMediaPlayer() {
        if (audioQueue.isEmpty()) return

        val audioFile = audioQueue.peek()
        if (audioFile == null) {
            return
        }

        try {
            isPreparing = true
            mediaPlayer.reset()
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.prepareAsync()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun onAudioCompletion() {
        isMediaPlayerPrepared = false
        isPreparing = false
        audioQueue.poll()
        if (audioQueue.isNotEmpty()) {
            prepareAndStartMediaPlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        webSocket.close(1000, "Activity destroyed")
    }
}
