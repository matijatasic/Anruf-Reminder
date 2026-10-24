package de.matija.anrufreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * Klingelt, bis angenommen, abgelehnt oder nach 60 s nicht reagiert wird.
 * Nach "Annehmen" wird der Text per Text-to-Speech vorgelesen (standardmäßig über die Hörmuschel).
 */
class RingService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_ACCEPT = "accept"
        private const val ACTION_DECLINE = "decline"
        private const val ACTION_SPEAKER = "speaker"
        private const val EXTRA_ON = "on"
        private const val EXTRA_TEXT = "text"
        private const val CHANNEL_RING = "ring"
        private const val CHANNEL_INFO = "info"
        private const val NOTIF_RING = 1
        /** Nach 15 s ohne Annehmen endet der Anruf als "verpasst". */
        private const val RING_TIMEOUT_MS = 15_000L
        private const val SPEAK_TIMEOUT_MS = 120_000L
        /** Schleife "mehrmals vorlesen": Sicherheitsgrenze, falls nie aufgelegt wird. */
        private const val LOOP_TIMEOUT_MS = 10 * 60_000L
        private const val LOOP_PAUSE_MS = 1500L
        private const val LOOP_UTTERANCE = "loop"
        private const val SPEAK_DELAY_MS = 1_000L
        private const val LAST_UTTERANCE = "last"
        private const val FILE_UTTERANCE = "file"

        @Volatile var currentText: String? = null
            private set

        /** Zeitpunkt des Annehmens (Wanduhr), 0 solange kein Gespräch läuft. */
        @Volatile var callStartedAt = 0L
            private set

        fun start(ctx: Context, id: Int, text: String) {
            val i = Intent(ctx, RingService::class.java)
                .setAction(ACTION_START)
                .putExtra(Scheduler.EXTRA_ID, id)
                .putExtra(EXTRA_TEXT, text)
            ctx.startForegroundService(i)
        }

        fun accept(ctx: Context) = ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_ACCEPT))
        fun decline(ctx: Context) = ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_DECLINE))

        /** Lautsprecher im laufenden Gespräch; startet laut Einstellung "Über die Hörmuschel vorlesen". */
        @Volatile var speakerOn = false
            private set

        fun setSpeaker(ctx: Context, on: Boolean) {
            speakerOn = on
            ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_SPEAKER).putExtra(EXTRA_ON, on))
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ring = NotificationChannel(CHANNEL_RING, "Klingeln", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Eingehende Erinnerungen"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val info = NotificationChannel(CHANNEL_INFO, "Verpasste Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ring)
            nm.createNotificationChannel(info)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var acceptedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioModeChanged = false
    private var ringing = false
    private var reminderId = 0

    private val ringTimeout = Runnable { missed() }
    private val speakTimeout = Runnable { finishAll() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: "Erinnerung"
                // Ein neuer Anruf ersetzt einen noch laufenden.
                stopRinging()
                stopSpeaking()
                reminderId = intent.getIntExtra(Scheduler.EXTRA_ID, 0)
                currentText = text
                callStartedAt = 0L
                goForeground(ringNotification(text))
                acquireWakeLock()
                wakeScreen()
                startRinging()
                prepareTts()
                openRingScreen()
            }
            ACTION_ACCEPT -> if (ringing) {
                stopRinging()
                callStartedAt = System.currentTimeMillis()
                // Gesprächs-Bildschirm mit Auflegen zeigen, wie bei einem echten Anruf.
                if (!RingActivity.showInCallIfOpen()) openRingScreen()
                speak(currentText ?: "")
            }
            ACTION_DECLINE -> finishAll()
            ACTION_SPEAKER -> {
                speakerOn = intent.getBooleanExtra(EXTRA_ON, false)
                if (audioModeChanged) routeCall()
            }
            else -> if (!ringing && !audioModeChanged && pendingText == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_RING, n)
        }
    }

    private fun servicePending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ringNotification(text: String): Notification {
        val full = PendingIntent.getActivity(
            this, 1, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Annehmen öffnet direkt den Gesprächs-Bildschirm; ein Activity-Start aus der Benachrichtigung
        // ist immer erlaubt, auch ohne "Über anderen Apps einblenden".
        val answerPi = PendingIntent.getActivity(
            this, 2, Intent(this, RingActivity::class.java)
                .putExtra(RingActivity.EXTRA_ACCEPT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePi = servicePending(ACTION_DECLINE, 3)
        val b = Notification.Builder(this, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(Ui.TEAL)
            .setContentTitle("Unbekannt")
            .setContentText("Anruf")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // System-Anrufdarstellung mit Annehmen/Ablehnen, auch auf dem Sperrbildschirm.
            val caller = Person.Builder()
                .setName("Unbekannt")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_call))
                .setImportant(true)
                .build()
            b.setStyle(Notification.CallStyle.forIncomingCall(caller, declinePi, answerPi))
        } else {
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_call_end), "Ablehnen", declinePi
                ).build()
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_call), "Annehmen", answerPi
                ).build()
            )
        }
        return b.build()
    }

    private fun speakingNotification(): Notification {
        val full = PendingIntent.getActivity(
            this, 6, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangUp = servicePending(ACTION_DECLINE, 4)
        val b = Notification.Builder(this, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(Ui.TEAL)
            .setContentTitle("Unbekannt")
            .setContentText("Laufender Anruf")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(full)
            .setWhen(callStartedAt)
            .setUsesChronometer(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = Person.Builder().setName("Unbekannt").setImportant(true).build()
            b.setStyle(Notification.CallStyle.forOngoingCall(caller, hangUp))
        } else {
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_call_end), "Auflegen", hangUp
                ).build()
            )
        }
        return b.build()
    }

    private fun openRingScreen() {
        // Klappt nur, wenn Android den Start erlaubt; sonst übernimmt die Vollbild-Benachrichtigung.
        try {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun startRinging() {
        ringing = true
        // Klingelmodus des Handys respektieren: lautlos = nur Anrufbildschirm, Vibration = nur vibrieren.
        val mode = getSystemService(AudioManager::class.java).ringerMode
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
        if (mode == AudioManager.RINGER_MODE_NORMAL) playRingtone()
        handler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
    }

    private fun playRingtone() {
        boostVolume()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // Wecker-Stream: darf ohne Sonderrechte auf Maximum (nur im normalen Klingelmodus genutzt).
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player = null
        }
    }

    private fun stopRinging() {
        ringing = false
        handler.removeCallbacks(ringTimeout)
        player?.run { try { stop() } catch (_: Exception) {}; release() }
        player = null
        vibrator().cancel()
        restoreVolume()
    }

    /** Wecker-Lautstärke vor dem Klingeln, um sie danach wiederherzustellen; -1 = nichts geändert. */
    private var savedAlarmVolume = -1

    /** Jeder Anruf startet in voller Lautstärke; die Seitentasten können ihn währenddessen leiser machen. */
    private fun boostVolume() {
        val am = getSystemService(AudioManager::class.java)
        if (savedAlarmVolume < 0) savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: SecurityException) {
        }
    }

    private fun restoreVolume() {
        if (savedAlarmVolume < 0) return
        try {
            getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        } catch (_: SecurityException) {
        }
        savedAlarmVolume = -1
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator = getSystemService(Vibrator::class.java)

    /** Sprachausgabe schon beim Klingeln starten, damit sie beim Annehmen sofort bereit ist. */
    private fun prepareTts() {
        ttsReady = false
        tts = TextToSpeech(this) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                if (pendingText != null) handler.post { finishAll() }
                return@TextToSpeech
            }
            engine.language = Locale.GERMAN
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == FILE_UTTERANCE) handler.post { onFileReady(true) }
                    if (utteranceId == LAST_UTTERANCE) handler.post { finishAll() }
                    // Schleife: nach kurzer Pause erneut vorlesen, bis aufgelegt wird.
                    if (utteranceId == LOOP_UTTERANCE) handler.postDelayed({ loopText?.let { speakLoop(it) } }, LOOP_PAUSE_MS)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == FILE_UTTERANCE) handler.post { onFileReady(false) }
                    else handler.post { finishAll() }
                }
            })
            handler.post {
                ttsReady = true
                currentText?.let { synthesize(it) }
                pendingText?.let { speakNow(it) }
            }
        }
    }

    private fun speak(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_RING, speakingNotification())
        handler.postDelayed(speakTimeout, if (ReminderStore.speakTwice(this)) LOOP_TIMEOUT_MS else SPEAK_TIMEOUT_MS)

        acceptedAt = SystemClock.elapsedRealtime()
        // Immer als Telefonat ausgeben, damit der Lautsprecher-Knopf ohne Unterbrechung umschalten kann.
        speakerOn = !ReminderStore.earpiece(this)
        routeCall()
        if (tts == null) prepareTts()
        if (ttsReady) speakNow(text) else pendingText = text
    }

    /** Spricht den Text genau 1 Sekunde nach dem Annehmen. */
    /** Text der laufenden Vorlese-Schleife; null = keine Schleife (beendet beim Auflegen). */
    private var loopText: String? = null

    private fun speakLoop(text: String) {
        // Vor jeder Runde die gewählte Ausgabe erneuern; manche Geräte setzen sie beim neuen Abspielen zurück.
        if (audioModeChanged) routeCall()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), LOOP_UTTERANCE)
    }

    private fun speakNow(text: String) {
        pendingText = null
        val wait = (SPEAK_DELAY_MS - (SystemClock.elapsedRealtime() - acceptedAt)).coerceAtLeast(0)
        handler.postDelayed({
            if (tts == null) return@postDelayed
            if (audioModeChanged) routeCall()
            loopText = if (ReminderStore.speakTwice(this)) text else null
            when (fileState) {
                FileState.READY -> playFile()
                FileState.FAILED -> speakLive(text)
                FileState.PENDING -> playPending = true
            }
        }, wait)
    }

    /** Ersatzweg, falls die Sprachdatei nicht erzeugt werden konnte: direkt über die Sprachausgabe sprechen. */
    private fun speakLive(text: String) {
        val e = tts ?: return
        e.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        if (loopText != null) speakLoop(text) else e.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), LAST_UTTERANCE)
    }

    // Der Text wird schon beim Klingeln in eine Audiodatei gesprochen und dann als ein durchgehendes
    // Stück abgespielt. Die Sprachausgabe spielt lange Texte sonst in Teilen ab, und manche Geräte
    // schalten dabei mitten im Satz kurz zwischen Lautsprecher und Hörmuschel um.
    private enum class FileState { PENDING, READY, FAILED }
    private var fileState = FileState.PENDING
    private var playPending = false
    private var speechFile: File? = null
    private var speechPlayer: MediaPlayer? = null
    private val loopRestart = Runnable {
        val p = speechPlayer ?: return@Runnable
        if (loopText == null) return@Runnable
        if (audioModeChanged) routeCall()
        p.seekTo(0)
        p.start()
    }

    private fun synthesize(text: String) {
        val f = File(cacheDir, "ansage.wav").also { speechFile = it }
        fileState = FileState.PENDING
        val ok = tts?.synthesizeToFile(text, Bundle(), f, FILE_UTTERANCE) == TextToSpeech.SUCCESS
        if (!ok) onFileReady(false)
    }

    private fun onFileReady(ok: Boolean) {
        if (fileState != FileState.PENDING) return
        fileState = if (ok) FileState.READY else FileState.FAILED
        if (!playPending) return
        playPending = false
        if (ok) playFile() else speakLive(currentText ?: "")
    }

    private fun playFile() {
        val f = speechFile ?: return speakLive(currentText ?: "")
        try {
            speechPlayer?.release()
            speechPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.path)
                prepare()
                preferOutput(this)
                setOnCompletionListener {
                    // Schleife: nach kurzer Pause von vorn, bis aufgelegt wird; sonst ist das Gespräch zu Ende.
                    if (loopText != null) handler.postDelayed(loopRestart, LOOP_PAUSE_MS) else finishAll()
                }
                start()
            }
        } catch (_: Exception) {
            speechPlayer?.release()
            speechPlayer = null
            speakLive(currentText ?: "")
        }
    }

    /** Gibt dem Abspieler das gewünschte Ausgabegerät direkt vor, zusätzlich zum Gesprächs-Routing. */
    private fun preferOutput(p: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == wantedDevice() }
        p.setPreferredDevice(device)
    }

    /** Hält die gewählte Ausgabe fest, falls das System sie während des Gesprächs eigenmächtig umstellt. */
    private var deviceWatcher: AudioManager.OnCommunicationDeviceChangedListener? = null

    /** Gesprächs-Modus mit Ausgabe über Hörmuschel oder Lautsprecher, je nach [speakerOn]. */
    @Suppress("DEPRECATION")
    private fun routeCall() {
        val am = getSystemService(AudioManager::class.java)
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
        audioModeChanged = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = wantedDevice()
            if (am.communicationDevice?.type != type) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == type }
                    ?.let { am.setCommunicationDevice(it) }
            }
            if (deviceWatcher == null) {
                deviceWatcher = AudioManager.OnCommunicationDeviceChangedListener { device ->
                    if (audioModeChanged && device?.type != wantedDevice()) routeCall()
                }.also { am.addOnCommunicationDeviceChangedListener(mainExecutor, it) }
            }
        } else {
            am.isSpeakerphoneOn = speakerOn
        }
        speechPlayer?.let { preferOutput(it) }
    }

    private fun wantedDevice() =
        if (speakerOn) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        if (!audioModeChanged) return
        audioModeChanged = false
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            deviceWatcher?.let { am.removeOnCommunicationDeviceChangedListener(it) }
            deviceWatcher = null
            am.clearCommunicationDevice()
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun stopSpeaking() {
        loopText = null
        handler.removeCallbacks(speakTimeout)
        handler.removeCallbacks(loopRestart)
        speechPlayer?.run { try { stop() } catch (_: Exception) {}; release() }
        speechPlayer = null
        playPending = false
        fileState = FileState.PENDING
        speechFile?.delete()
        speechFile = null
        tts?.run { stop(); shutdown() }
        tts = null
        ttsReady = false
        pendingText = null
        restoreAudio()
    }

    private fun missed() {
        val text = currentText ?: ""
        finishAll()
        val n = Notification.Builder(this, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Verpasster Anruf")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(1000 + reminderId, n)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        // Schaltet den Bildschirm ein, damit Anruf-Bildschirm bzw. Anruf-Benachrichtigung sichtbar werden.
        getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "anrufreminder:screen"
            )
            .acquire(10_000L)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anrufreminder:ring")
            .apply { acquire(LOOP_TIMEOUT_MS + RING_TIMEOUT_MS) }
    }

    private fun finishAll() {
        loopText = null
        stopRinging()
        stopSpeaking()
        RingActivity.closeIfOpen()
        currentText = null
        callStartedAt = 0L
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRinging()
        stopSpeaking()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
