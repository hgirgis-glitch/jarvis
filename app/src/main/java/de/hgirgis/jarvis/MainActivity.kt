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
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var vault: Vault
    private lateinit var brain: Brain
    private lateinit var calendar: Calendar
    private lateinit var google: Google
    private lateinit var tools: Tools
    private lateinit var gemini: GeminiBrain
    private lateinit var router: NanoRouter
    private lateinit var voice: GeminiVoice
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
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
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                .onFailure { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit().putString("vault", uri.toString()).apply()
            loadVault(uri)
        }
    }

    private val askCalendar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        ui("calendar", if (r[Manifest.permission.READ_CALENDAR] == true) "verbunden" else "nicht erlaubt")
    }

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ok = google.onConsent(r.data)
        ui("google", if (ok) "verbunden" else "nicht verbunden")
        ui("status", if (ok) "Google verbunden. Gmail und Drive sind bereit." else "Google Anmeldung abgebrochen.")
    }

    private val brainMode get() = prefs.getString("brain", null) ?: if (prefs.getString("gemini_key", "").isNullOrBlank()) "nano" else "gemini"

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
        calendar = Calendar(this)
        google = Google(this)
        tools = Tools(vault, calendar, google, ::confirmAction, { src -> if (src.isNotBlank()) ui("sources", src) }, { q -> gemini.webSearch(q) })
        gemini = GeminiBrain({ prefs.getString("gemini_key", "") ?: "" }, { prefs.getString("gemini_model", null) ?: DEFAULT_MODEL }, tools) { msg -> ui("status", msg) }
        router = NanoRouter(tools)
        voice = GeminiVoice({ prefs.getString("gemini_key", "") ?: "" }, { prefs.getString("gemini_voice", null) ?: GeminiVoice.DEFAULT_VOICE })

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
            if (brainMode == "nano") {
                runCatching { brain.prepare() }.onFailure { ui("status", "Gemini Nano Fehler: ${it.message}") }
            }
            ui("status", "Bereit. Tippe auf den Kern oder auf Sprechen.")
            if (prefs.getBoolean("google_connected", false)) {
                val needsConsent = runCatching { google.authorize() }.getOrNull()
                ui("google", if (needsConsent == null) "verbunden" else "erneut anmelden")
            }
        }
        prefs.getString("vault", null)?.let { loadVault(Uri.parse(it)) }
        checkUpdate()
        if (!hasCalendar() && !prefs.getBoolean("asked_calendar", false)) {
            prefs.edit().putBoolean("asked_calendar", true).apply()
            askCalendar.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
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
        tts?.stop(); voice.stop()
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
        val langs = languages()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, langs.first())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langs.first())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Ich höre zu, Sir")
        if (langs.size > 1 && android.os.Build.VERSION.SDK_INT >= 34) {
            // Android 14+: let the recognizer detect which of the allowed languages is spoken.
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(langs))
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(langs))
        }
    }

    private fun languages(): List<String> =
        (prefs.getString("languages", null) ?: "de-DE,en-US").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("de-DE") }

    private var pendingUpdate: Updater.Update? = null

    private fun checkUpdate() {
        lifecycleScope.launch {
            val u = Updater(this@MainActivity).check() ?: return@launch
            pendingUpdate = u
            ui("update", "Neue Version ${u.versionName} verfügbar")
        }
    }

    private fun installUpdate() {
        val u = pendingUpdate ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            ui("status", "Bitte JARVIS einmalig erlauben, Apps zu installieren, dann noch einmal auf Update tippen.")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            val err = Updater(this@MainActivity).install(u) { p -> ui("update", "Lade Update: $p %") }
            ui("update", err ?: "Installation gestartet")
        }
    }

    private fun report(): String {
        val pi = packageManager.getPackageInfo(packageName, 0)
        return buildString {
            append("JARVIS Fehlerbericht\n")
            append("Version ${pi.versionName} (${pi.longVersionCode}), Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}\n")
            append("Gehirn: $brainMode, Modell: ${prefs.getString("gemini_model", null) ?: DEFAULT_MODEL}, ")
            append("Schlüssel: ${if (prefs.getString("gemini_key", "").isNullOrBlank()) "fehlt" else "vorhanden"}, ")
            append("Sprachen: ${languages().joinToString()}, Vault: ${vault.size} Notizen, Kalender: ${hasCalendar()}\n")
            append("--- Verlauf ---\n").append(EventLog.dump())
        }
    }

    private fun copyReport() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS Fehlerbericht", report()))
        ui("status", "Fehlerbericht kopiert. Füge ihn einfach im Chat mit Claude ein.")
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
        ui("sources", "")
        lifecycleScope.launch {
            val reply = runCatching {
                if (brainMode == "gemini") withTimeout(70_000) { gemini.answer(text) }
                else {
                    if (!brain.ready) "Gemini Nano wird noch auf das Handy geladen. Bis dahin kann ich nicht antworten. " +
                        "Schneller geht es mit einem Gemini Schlüssel in den Einstellungen."
                    else withTimeout(30_000) { brain.answer(text, router.gather(text)) }
                }
            }.getOrElse {
                if (it is TimeoutCancellationException) (if (brainMode == "gemini") "Gemini hat nicht rechtzeitig geantwortet. Meist ist das Gratis Limit erreicht, bitte in einer Minute noch einmal fragen." else "Gemini Nano hat nicht rechtzeitig geantwortet. Bitte in den Einstellungen auf Gemini Schlüssel umstellen.")
                else if (it is java.net.UnknownHostException || it is java.net.SocketTimeoutException || it is java.net.ConnectException)
                    "Keine Internetverbindung, Sir. Bitte WLAN oder mobile Daten prüfen und noch einmal fragen."
                else "Da ist etwas schiefgegangen: ${it.message}"
            }
            busy = false
            ui("reply", reply)
            speak(reply)
        }
    }

    private fun hasCalendar() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Shows a confirmation card in the app and waits for the answer. */
    private suspend fun confirmAction(text: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingConfirm = d
        ui("confirm", text)
        ui("state", "idle")
        if (ttsReady) tts?.speak("Bitte bestätigen, Sir.", TextToSpeech.QUEUE_FLUSH, null, "confirm")
        val ok = d.await()
        ui("state", "thinking")
        return ok
    }

    private fun connectGoogle() {
        lifecycleScope.launch {
            val intent = runCatching { google.authorize() }.getOrElse {
                ui("status", "Google Anmeldung fehlgeschlagen: ${it.message}. Ist der OAuth Client in der Google Cloud angelegt?")
                return@launch
            }
            prefs.edit().putBoolean("google_connected", true).apply()
            if (intent == null) { ui("google", "verbunden"); ui("status", "Google verbunden.") }
            else googleConsent.launch(IntentSenderRequest.Builder(intent.intentSender).build())
        }
    }

    private fun diagnose() {
        ui("diag", "Teste Verbindung")
        lifecycleScope.launch {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "kein aktives Netz"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobilfunk"
                else -> "anderes Netz"
            } + (if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) " mit VPN" else "") +
                (if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) ", Internet bestätigt" else ", Internet nicht bestätigt")
            val out = StringBuilder("Version ${packageManager.getPackageInfo(packageName, 0).versionName}\nNetz: $net\nDatensparmodus: ${cm.restrictBackgroundStatus}\n")
            for (u in listOf("https://www.google.com/generate_204", "https://generativelanguage.googleapis.com/")) {
                val host = java.net.URL(u).host
                val dns = runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { java.net.InetAddress.getAllByName(host).size } }
                val http = runCatching { Http.get(u).code }
                out.append(host).append(": DNS ").append(dns.fold({ "ok ($it Adressen)" }, { "FEHLER ${it.javaClass.simpleName}" }))
                    .append(", HTTP ").append(http.fold({ "$it" }, { "FEHLER ${it.javaClass.simpleName}" })).append("\n")
            }
            ui("diag", out.toString())
        }
    }

    private fun settingsJson() = JSONObject().apply {
        put("brain", brainMode)
        put("gemini_key_set", !prefs.getString("gemini_key", "").isNullOrBlank())
        put("gemini_model", prefs.getString("gemini_model", null) ?: DEFAULT_MODEL)
        put("gemini_voice", prefs.getString("gemini_voice", null) ?: GeminiVoice.DEFAULT_VOICE)
        put("languages", languages().joinToString(","))
        put("version", packageManager.getPackageInfo(packageName, 0).versionName)
        put("calendar", hasCalendar())
        put("google", prefs.getBoolean("google_connected", false))
        put("vault", vault.size)
    }.toString()

    // ---------- speech out ----------

    private fun speak(text: String) {
        if (brainMode == "gemini" && prefs.getBoolean("gemini_voice_on", true)) {
            lifecycleScope.launch {
                ui("state", "speaking")
                val problem = voice.speak(text)
                if (problem == null) ui("state", "idle") else speakAndroid(text)
            }
            return
        }
        speakAndroid(text)
    }

    private fun speakAndroid(text: String) {
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
            tts?.stop(); voice.stop(); recognizer?.cancel(); ui("state", "idle")
        }
        @JavascriptInterface fun settings(): String = settingsJson()
        @JavascriptInterface fun set(key: String, value: String) = runOnUiThread {
            if (key in setOf("brain", "gemini_key", "gemini_model", "gemini_voice", "languages")) {
                prefs.edit().putString(key, value.trim()).apply()
                if (key == "brain" && value == "nano") lifecycleScope.launch { runCatching { brain.prepare() } }
                if (key == "brain") gemini.reset()
            }
        }
        @JavascriptInterface fun copyReport() = runOnUiThread { this@MainActivity.copyReport() }
        @JavascriptInterface fun installUpdate() = runOnUiThread { this@MainActivity.installUpdate() }
        @JavascriptInterface fun diagnose() = runOnUiThread { this@MainActivity.diagnose() }
        @JavascriptInterface fun connectGoogle() = runOnUiThread { this@MainActivity.connectGoogle() }
        @JavascriptInterface fun grantCalendar() = runOnUiThread {
            askCalendar.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
        @JavascriptInterface fun confirm(ok: Boolean) = runOnUiThread {
            pendingConfirm?.complete(ok); pendingConfirm = null
        }
        @JavascriptInterface fun pickVault() = runOnUiThread {
            val hint = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments")
            pickVault.launch(hint)
        }
    }

    private fun ui(kind: String, value: String): Unit {
        if (kind != "diag") EventLog.add(kind, value)
        js("window.jarvis && jarvis.on(${JSONObject.quote(kind)}, ${JSONObject.quote(value)})")
    }

    private fun js(code: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
