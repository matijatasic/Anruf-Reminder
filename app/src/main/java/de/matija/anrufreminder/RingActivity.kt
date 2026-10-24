package de.matija.anrufreminder

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Eingehender-Anruf-Bildschirm, erscheint auch über dem Sperrbildschirm. */
class RingActivity : Activity() {

    companion object {
        /** Gesetzt, wenn die Activity über "Annehmen" in der Benachrichtigung gestartet wird. */
        const val EXTRA_ACCEPT = "accept"
        private var current: WeakReference<RingActivity>? = null

        fun closeIfOpen() {
            current?.get()?.let { a -> a.runOnUiThread { a.finish() } }
        }

        /** Nach dem Annehmen: offenen Bildschirm auf "Gespräch" umschalten. Gibt false zurück, wenn keiner offen ist. */
        fun showInCallIfOpen(): Boolean {
            val a = current?.get() ?: return false
            a.runOnUiThread { a.render() }
            return true
        }
    }

    private var pulse: ObjectAnimator? = null
    private var proximity: PowerManager.WakeLock? = null

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        showOverLockScreen()
        // Seitliche Lautstärketasten regeln auf diesem Bildschirm das Klingeln (Wecker-Stream).
        volumeControlStream = AudioManager.STREAM_ALARM

        if (!acceptFrom(intent)) render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!acceptFrom(intent)) render()
    }

    /** Annehmen aus der Benachrichtigung: Dienst informieren, der schaltet dann auf "Gespräch" um. */
    private fun acceptFrom(i: Intent?): Boolean {
        if (i?.getBooleanExtra(EXTRA_ACCEPT, false) != true) return false
        i.removeExtra(EXTRA_ACCEPT)
        RingService.accept(this)
        render()
        return true
    }

    /** Zeigt je nach Zustand den klingelnden Anruf oder das laufende Gespräch. */
    private fun render() {
        pulse?.cancel()
        when {
            RingService.callStartedAt > 0 -> {
                // Im Gespräch regeln die Seitentasten die Gesprächslautstärke.
                volumeControlStream = AudioManager.STREAM_VOICE_CALL
                setContentView(buildInCallScreen())
                updateEar()
            }
            RingService.currentText != null -> setContentView(buildCallScreen())
            else -> finish()
        }
    }

    /** Bildschirm aus, solange das Handy am Ohr ist – wie bei einem echten Telefonat; nicht bei Lautsprecher. */
    private fun updateEar() {
        if (RingService.speakerOn) {
            proximity?.let { if (it.isHeld) it.release() }
            proximity = null
            return
        }
        if (proximity != null) return
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "anrufreminder:ear")
            .apply { acquire(3 * 60_000L) }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Farben der System-Anrufkarte (Annehmen/Ablehnen), damit beides gleich aussieht.
    private val callBg = 0xFF171717.toInt()
    private val callRed = 0xFFE66A5E.toInt()
    private val callGreen = 0xFF5DBA81.toInt()
    private val onPill = 0xFF1A1A1A.toInt()

    private fun buildCallScreen(): View {
        // Der Erinnerungstext bleibt bis zum Annehmen verborgen, angezeigt wird ein unbekannter Anrufer.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(callBg)
            // Oben Platz lassen für die Anruf-Benachrichtigung des Systems.
            setPadding(dp(24), dp(190), dp(24), dp(56))
        }

        root.addView(callerName())
        root.addView(TextView(this).apply {
            this.text = "Anruf"
            typeface = Typeface.DEFAULT
            textSize = 20f
            setTextColor(0xFFB3B3B3.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Telefonsymbol mit pulsierendem Ring, mittig zwischen Text und Knöpfen.
        // Der Container ist größer als der maximal skalierte Ring, damit nichts eckig abgeschnitten wird.
        val avatarSize = dp(200)
        val avatar = FrameLayout(this).apply { clipChildren = false }
        val halo = View(this).apply { background = Ui.oval(0x335DBA81, callGreen and 0x66FFFFFF, dp(2)) }
        avatar.addView(halo, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        val core = FrameLayout(this).apply { background = Ui.oval(0xFF242424.toInt(), callGreen, dp(3)) }
        core.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(callGreen)
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        avatar.addView(core, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        root.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize))

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            halo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.15f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.15f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.2f)
        ).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val pillLp = { LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
        buttons.addView(pill("Ablehnen", callRed, R.drawable.ic_call_end) {
            RingService.decline(this); finish()
        }, pillLp())
        buttons.addView(pill("Annehmen", callGreen, R.drawable.ic_call) {
            RingService.accept(this)
        }, pillLp())
        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    /** Laufendes Gespräch: Gesprächsdauer und Auflegen-Knopf. */
    private fun buildInCallScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(callBg)
            setPadding(dp(24), dp(190), dp(24), dp(56))
        }
        root.addView(callerName())
        root.addView(Chronometer(this).apply {
            base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - RingService.callStartedAt)
            typeface = Typeface.DEFAULT
            textSize = 20f
            setTextColor(callGreen)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            start()
        })

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val core = FrameLayout(this).apply { background = Ui.oval(0xFF242424.toInt(), 0xFF3A3A3A.toInt(), dp(2)) }
        core.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(0xFFB3B3B3.toInt())
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(core, LinearLayout.LayoutParams(dp(112), dp(112)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Lautsprecher (weiß = an, dunkel = aus) links neben Auflegen, wie beim normalen Telefon.
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val speakerIcon = ImageView(this).apply { setImageResource(R.drawable.ic_speaker) }
        val speaker = FrameLayout(this).apply {
            isClickable = true
            contentDescription = "Lautsprecher"
            addView(speakerIcon, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        fun showSpeaker() {
            val on = RingService.speakerOn
            speaker.background = Ui.pressable(Ui.oval(if (on) 0xFFFFFFFF.toInt() else 0xFF2A2A2A.toInt()), 0x33000000)
            speakerIcon.setColorFilter(if (on) onPill else 0xFFE0E0E0.toInt())
        }
        showSpeaker()
        speaker.setOnClickListener {
            RingService.setSpeaker(this, !RingService.speakerOn)
            showSpeaker()
            updateEar()
        }
        val hangUp = FrameLayout(this).apply {
            background = Ui.pressable(Ui.oval(callRed), 0x33000000)
            isClickable = true
            contentDescription = "Auflegen"
            setOnClickListener { RingService.decline(this@RingActivity); finish() }
            addView(ImageView(this@RingActivity).apply {
                setImageResource(R.drawable.ic_call_end)
                setColorFilter(onPill)
            }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }
        buttons.addView(labeled(speaker, "Lautsprecher"))
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(48), 1))
        buttons.addView(labeled(hangUp, "Auflegen"))
        root.addView(buttons)
        return root
    }

    /** Dezenter Anrufername: grau und normal, die Zeile darunter ist die Hauptinfo. */
    private fun callerName() = TextView(this).apply {
        text = "Unbekannt"
        typeface = Typeface.DEFAULT
        textSize = 22f
        setTextColor(0xFF9E9E9E.toInt())
        gravity = Gravity.CENTER
    }

    /** Runder Knopf mit Beschriftung darunter. */
    private fun labeled(button: View, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(button, LinearLayout.LayoutParams(dp(76), dp(76)))
        addView(TextView(this@RingActivity).apply {
            text = label
            typeface = Typeface.DEFAULT
            textSize = 15f
            setTextColor(0xFFB3B3B3.toInt())
            setPadding(0, dp(10), 0, 0)
        })
    }

    /** Pillen-Knopf mit Symbol und Text, wie in der System-Anrufkarte. */
    private fun pill(label: String, color: Int, icon: Int, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Ui.pressable(Ui.rounded(color, dp(30).toFloat()), 0x33000000)
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(this@RingActivity).apply {
                setImageResource(icon)
                setColorFilter(onPill)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(this@RingActivity).apply {
                text = label
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 18f
                setTextColor(onPill)
                setPadding(dp(10), 0, 0, 0)
            })
        }

    override fun onDestroy() {
        pulse?.cancel()
        proximity?.let { if (it.isHeld) it.release() }
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
