package de.hgirgis.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var vault: Vault
    private lateinit var brain: Brain
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var busy = false

    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else ui("status", "Ohne Mikrofon kann ich dich nicht hören.")
    }

    /** Fallback: the system speech dialog (Google), works even when the in-app recognizer fails. */
    private val speechDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text.isNullOrBlank()) { ui("state", "idle"); ui("status", "Nichts verstanden. Tippe und versuch es noch einmal.") }
        else handle(text)
    }
    private var useDialog = false

    private val pickVault = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("vault", uri.toString()).apply()
            loadVault(uri)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        vault = Vault(this)
        brain = Brain { msg -> ui("status", msg) }

        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)

        tts = TextToSpeech(this) { code ->
            if (code != TextToSpeech.SUCCESS) {
                ui("status", "Sprachausgabe nicht verfügbar. Bitte in Android eine Text zu Sprache Engine aktivieren.")
            } else {
                val lang = tts?.setLanguage(Locale.GERMANY) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED)
                    ui("status", "Deutsche Stimme fehlt. Einstellungen, Allgemeine Verwaltung, Text zu Sprache: Deutsch installieren.")
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(0.9f)
                pickGermanVoice()
                tts?.setOnUtteranceProgressListener(speechListener)
                ttsReady = true
            }
        }

        lifecycleScope.launch {
            runCatching { brain.prepare() }.onFailure { ui("status", "Gemini Nano Fehler: ${it.message}") }
            if (brain.ready) ui("status", "Bereit. Tippe auf den Kern und sprich.")
        }
        prefs.getString("vault", null)?.let { loadVault(Uri.parse(it)) }
    }

    private fun pickGermanVoice() {
        val voices = runCatching { tts?.voices }.getOrNull() ?: return
        voices.filter { it.locale.language == "de" && !it.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }
            ?.let { tts?.voice = it }
    }

    private fun loadVault(uri: Uri) {
        ui("vault", "Lese Notizen")
        lifecycleScope.launch {
            val n = runCatching { vault.load(uri) }.getOrElse { -1 }
            ui("vault", if (n >= 0) "$n Notizen" else "Vault nicht lesbar")
        }
    }

    // ---------- speech in ----------

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (busy) return
        tts?.stop()
        if (useDialog || !SpeechRecognizer.isRecognitionAvailable(this)) { listenWithDialog(); return }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        }
        val intent = speechIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        runCatching { recognizer?.startListening(intent) }.onFailure { listenWithDialog(); return }
        ui("state", "listening")
        ui("status", "Starte Mikrofon")
    }

    private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "de-DE")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Ich höre zu, Sir")
    }

    private fun listenWithDialog() {
        ui("state", "listening")
        runCatching { speechDialog.launch(speechIntent()) }.onFailure {
            ui("state", "idle")
            ui("status", "Auf dem Handy ist keine Spracherkennung aktiv. Bitte die Google App installieren oder aktualisieren.")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = ui("status", "Ich höre zu")
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = js("window.jarvis && jarvis.level(${(rmsdB + 2f) / 12f})")
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = ui("state", "thinking")
        override fun onError(error: Int) {
            ui("state", "idle")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    ui("status", "Ich habe nichts verstanden. Tippe und versuch es noch einmal.")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    ui("status", "Mikrofon nicht erlaubt. Bitte in den App Einstellungen das Mikrofon freigeben.")
                else -> {
                    // In-app recognizer does not work on this phone: switch to the system dialog for good.
                    useDialog = true
                    ui("status", "Spracherkennung Fehler $error, wechsle zum System Dialog")
                    listenWithDialog()
                }
            }
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) { ui("state", "idle"); return }
            handle(text)
        }
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { ui("user", it) }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- thinking ----------

    private fun handle(text: String) {
        if (busy) return
        busy = true
        ui("user", text)
        ui("state", "thinking")
        lifecycleScope.launch {
            val hits = vault.search(text)
            ui("sources", hits.joinToString(" · ") { it.note.title })
            val reply = runCatching { withTimeout(25_000) { brain.answer(text, hits) } }
                .getOrElse {
                    if (it is TimeoutCancellationException) "Gemini Nano hat nicht rechtzeitig geantwortet. Auf diesem Handy ist das lokale Modell offenbar nicht nutzbar."
                    else "Da ist etwas schiefgegangen: ${it.message}"
                }
            busy = false
            ui("reply", reply)
            speak(reply)
        }
    }

    // ---------- speech out ----------

    private fun speak(text: String) {
        if (!ttsReady) { ui("state", "idle"); ui("status", "Sprachausgabe ist nicht bereit."); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private val speechListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = ui("state", "speaking")
        override fun onDone(utteranceId: String?) = ui("state", "idle")
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = ui("state", "idle")
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) =
            js("window.jarvis && jarvis.pulse()")
    }

    // ---------- bridge ----------

    inner class Bridge {
        @JavascriptInterface fun listen() = runOnUiThread { this@MainActivity.listen() }
        @JavascriptInterface fun ask(text: String) = runOnUiThread { if (text.isNotBlank()) handle(text.trim()) }
        @JavascriptInterface fun stop() = runOnUiThread {
            tts?.stop(); recognizer?.cancel(); ui("state", "idle")
        }
        @JavascriptInterface fun pickVault() = runOnUiThread {
            val hint = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments")
            pickVault.launch(hint)
        }
    }

    private fun ui(kind: String, value: String) =
        js("window.jarvis && jarvis.on(${JSONObject.quote(kind)}, ${JSONObject.quote(value)})")

    private fun js(code: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
