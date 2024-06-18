package pl.woyteck.ragnarok

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import android.widget.Button
import android.widget.TextView
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.animation.ScaleAnimation
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var textView: TextView
    private lateinit var button: Button
    private lateinit var forgetButton: Button
    private lateinit var speechRecognizer: SpeechRecognizer
    private var mediaPlayer: MediaPlayer? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private var conversationId: String? = null
    private var baseUrl: String? = "http://home.woyteck.pl:4000"

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

        getConversationId()

        forgetButton.setOnClickListener {
            conversationId = null
            getConversationId()
            textView.text = getString(R.string.ropoczeto_nowa_rozmowe)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
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
                sendPostRequest(recognizedText)
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
        var url = "$baseUrl/api/v1/conversation"
        val request = Request.Builder()
            .url(url)
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

    private fun sendPostRequest(recognizedText: String) {
        val url = "$baseUrl/api/v1/conversation/$conversationId"

        val json = JSONObject().put("text", recognizedText).toString()
        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
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
                        val audioStream = response.body?.byteStream()
                        audioStream?.let { playAudio(it) }
                    }
                }
            })
        }
    }

    private fun playAudio(inputStream: InputStream) {
        val audioFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "response_audio.mp3")
        FileOutputStream(audioFile).use { output ->
            inputStream.copyTo(output)
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            start()
        }
    }

//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
//            textView.text = "Microphone permission is required to use this app"
//        }
//    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        mediaPlayer?.release()
    }
}
