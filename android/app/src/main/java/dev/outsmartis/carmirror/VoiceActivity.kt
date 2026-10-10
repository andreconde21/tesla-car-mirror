package dev.outsmartis.carmirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice typing from the car's mic button. The phone listens (its own mic, or the car's when
 * Bluetooth routes it) and the text goes to the focused field on the car screen.
 *
 * An invisible activity, not the service: Android only gives the microphone to an app in the
 * foreground. It shows above the lock screen so it also works with the phone in a pocket.
 */
class VoiceActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishWith(Voice.Error("Allow the microphone for CarMirror on the phone (Setup → Voice typing)"))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishWith(Voice.Error("No speech recognition on the phone (install or enable Google)"))
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Voice.emit(Voice.Listening)
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { Voice.emit(Voice.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                val text = first(results)
                finishWith(if (text.isNullOrBlank()) Voice.Error("Didn't catch that") else Voice.Done(text))
            }
            override fun onError(error: Int) = finishWith(Voice.Error(message(error)))
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
        current = this
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun message(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice typing needs mobile data"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone for CarMirror on the phone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The phone's voice input is busy"
        else -> "Voice typing failed ($error)"
    }

    private var finished = false

    private fun finishWith(result: Voice.Event) {
        if (finished) return
        finished = true
        // close first: the app underneath gets its focus (and focused text field) back before the text arrives
        finish()
        overridePendingTransition(0, 0)
        android.os.Handler(mainLooper).postDelayed({ Voice.emit(result) }, 400)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: VoiceActivity? = null

        fun start(context: Context) {
            current?.let { it.runOnUiThread { it.finishWith(Voice.Error("Cancelled")) } }
            // from the background: allowed because CarMirror may display over other apps
            context.startActivity(
                Intent(context, VoiceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }

        fun stop() {
            current?.let { a -> a.runOnUiThread { a.recognizer?.stopListening() } }
        }
    }
}

/** Voice typing progress, for the car. */
object Voice {
    sealed interface Event
    object Listening : Event
    data class Partial(val text: String) : Event
    data class Done(val text: String) : Event
    data class Error(val message: String) : Event

    @Volatile var listener: ((Event) -> Unit)? = null

    fun emit(e: Event) {
        listener?.invoke(e)
    }
}
