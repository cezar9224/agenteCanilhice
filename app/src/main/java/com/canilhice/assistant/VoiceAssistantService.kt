package com.canilhice.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.Executors

class VoiceAssistantService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        const val ASK = "ask"
        const val TEST_VOICE = "test_voice"
        const val EXTRA_QUESTION = "question"
        const val ACTION_UPDATE = "com.canilhice.UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATE = "state"
        const val EXTRA_ANSWER = "answer"
        const val STATE_IDLE = "idle"
        const val STATE_LISTENING = "listening"
        const val STATE_THINKING = "thinking"
        const val STATE_SPEAKING = "speaking"
        const val STATE_ERROR = "error"
        private const val TAG = "Canilhice"
        private const val PREFS = "assistant_state"
        private const val PREF_ACTIVE = "active"

        fun isActive(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(PREF_ACTIVE, false)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var client: OpenAiClient? = null
    private var waitingForQuestion = false
    private var speaking = false
    private var ttsReady = false
    private var handlingResult = false
    private var pendingSpeech: String? = null

    override fun onCreate() {
        super.onCreate()
        val googleTtsInstalled = runCatching {
            packageManager.getApplicationInfo("com.google.android.tts", 0)
        }.isSuccess
        tts = if (googleTtsInstalled) {
            TextToSpeech(this, this, "com.google.android.tts")
        } else {
            TextToSpeech(this, this)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                speaking = false
                handler.post { startListening(350) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking = false
                handler.post { startListening(350) }
            }
        })
        createNotification()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            setActive(false)
            broadcast("Escuta pausada", STATE_IDLE)
            stopSelf()
            return START_NOT_STICKY
        }
        setActive(true)
        startForeground(7, notification("Ouvindo por “Canilhice”…"))
        when (intent?.action) {
            ASK -> intent.getStringExtra(EXTRA_QUESTION)?.takeIf { it.isNotBlank() }?.let(::ask)
            TEST_VOICE -> say("Olá! A voz do Canilhice está funcionando.", false)
            else -> startListening()
        }
        return START_STICKY
    }
    override fun onDestroy() {
        setActive(false)
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startListening(delay: Long = 250) {
        if (speaking) return
        handler.postDelayed({
            if (!SpeechRecognizer.isRecognitionAvailable(this)) { update("Reconhecimento de voz indisponível", state = STATE_ERROR); return@postDelayed }
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Tolera uma pausa natural antes de considerar a pergunta concluida.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1800L)
            }
            handlingResult = false
            recognizer?.startListening(intent)
            update("Ouvindo por “Canilhice”…", state = STATE_LISTENING)
        }, delay)
    }
    private fun process(text: String) {
        if (handlingResult) return
        if (!waitingForQuestion) {
            val match = wakeWordRegex.find(text)
            if (match == null) {
                Log.i(TAG, "Fala recebida sem a palavra de ativação")
                update("Diga “Canilhice” para começar", state = STATE_LISTENING)
                startListening(250)
                return
            }
            val rest = text.substring(match.range.last + 1).trim(' ', ',', '.', '?', ':', ';')
            handlingResult = true
            if (rest.isBlank()) { waitingForQuestion = true; say("Sim?", listenAfter = true) } else ask(rest)
        } else {
            handlingResult = true
            waitingForQuestion = false
            ask(text)
        }
    }
    private fun ask(question: String) {
        Log.i(TAG, "Pergunta reconhecida; enviando para a OpenAI")
        update("Pensando…", state = STATE_THINKING)
        worker.execute {
            try {
                val key = SecureKeyStore(this).load() ?: throw IllegalStateException("Chave não configurada")
                val response = (client ?: OpenAiClient(key).also { client = it }).ask(question)
                Log.i(TAG, "Resposta da OpenAI recebida (${response.length} caracteres)")
                handler.post { say(response, false) }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao consultar a OpenAI", e)
                val detail = e.message ?: "Verifique a conexão com a internet."
                handler.post {
                    update("Erro ao consultar a OpenAI", detail, STATE_ERROR)
                    say("Não consegui responder. $detail", false)
                }
            }
        }
    }
    private fun say(text: String, listenAfter: Boolean) {
        if (!ttsReady) {
            Log.i(TAG, "TTS ainda está inicializando; fala colocada na fila")
            speaking = false
            pendingSpeech = text
            update("Preparando a voz…", text, STATE_THINKING)
            return
        }
        speaking = true
        recognizer?.cancel()
        update("Respondendo…", state = STATE_SPEAKING)
        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle().apply { putBoolean("listenAfter", listenAfter) },
            "canilhice"
        )
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TextToSpeech.speak retornou ERROR")
            speaking = false
            update("Não foi possível reproduzir a voz", text, STATE_ERROR)
            startListening(500)
        }
    }
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            update("Sintetizador de voz indisponível", state = STATE_ERROR)
            return
        }
        // Alguns mecanismos podem concluir a inicialização antes de a atribuição
        // do objeto TextToSpeech terminar. Executar no próximo ciclo evita essa corrida.
        handler.post {
            val languageResult = tts?.setLanguage(Locale("pt", "BR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            Log.i(TAG, "TTS inicializado; idioma=$languageResult; pronto=$ttsReady; mecanismo=${tts?.defaultEngine}")
            if (!ttsReady) {
                update("Voz em português indisponível", pendingSpeech, STATE_ERROR)
                pendingSpeech = null
            } else {
                pendingSpeech?.let {
                    pendingSpeech = null
                    say(it, false)
                }
            }
        }
    }
    override fun onResults(results: Bundle?) {
        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        Log.i(TAG, "Reconhecimento final recebido; alternativas=${alternatives.size}; aguardandoPergunta=$waitingForQuestion")
        if (BuildConfig.DEBUG) Log.d(TAG, "Alternativas reconhecidas: $alternatives")
        val selected = if (waitingForQuestion) alternatives.firstOrNull()
        else alternatives.firstOrNull { wakeWordRegex.containsMatchIn(it) } ?: alternatives.firstOrNull()
        selected?.let(::process) ?: startListening()
    }
    override fun onError(error: Int) { if (!speaking) startListening(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 400) }
    override fun onPartialResults(partialResults: Bundle?) {
        val alternatives = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val text = (if (waitingForQuestion) alternatives.firstOrNull()
        else alternatives.firstOrNull { wakeWordRegex.containsMatchIn(it) } ?: alternatives.firstOrNull())
            ?.trim().orEmpty()
        if (text.isBlank() || speaking || handlingResult) return
        update("Ouvindo…", state = STATE_LISTENING)

        // Se a frase parcial já contém o nome e a pergunta, não precisamos
        // esperar o reconhecedor encerrar sozinho a sessão.
        // Resultados parciais apenas atualizam a interface. A pergunta so e
        // processada em onResults, quando o usuario realmente termina de falar.
    }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun createNotification() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("voice", "Escuta por voz", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String) = NotificationCompat.Builder(this, "voice").setSmallIcon(R.drawable.ic_canilhice).setContentTitle("Canilhice ativo").setContentText(text).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun update(status: String, answer: String? = null, state: String = STATE_IDLE) {
        getSystemService(NotificationManager::class.java).notify(7, notification(status.lineSequence().first()))
        broadcast(status, state, answer)
    }

    private fun broadcast(status: String, state: String, answer: String? = null) {
        sendBroadcast(
            Intent(ACTION_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_ANSWER, answer)
        )
    }

    private fun setActive(value: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ACTIVE, value).apply()
    }

    private val wakeWordRegex = Regex(
        pattern = "\\b(?:can|kan|ken)[\\p{L}]{1,14}\\b(?:\\s+(?:hice|ice|isse|isso|disse|se|si|lise|risse))?",
        option = RegexOption.IGNORE_CASE
    )
}
