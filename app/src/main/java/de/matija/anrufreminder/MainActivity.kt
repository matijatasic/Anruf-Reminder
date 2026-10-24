package de.matija.anrufreminder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val KEY_VENDOR_DONE = "vendor_done"
        private const val KEY_INTRO = "intro_shown"
        private const val REQ_SPEECH = 42
    }

    private lateinit var setupTop: LinearLayout
    private lateinit var setupBottom: LinearLayout
    private lateinit var cards: LinearLayout

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RingService.createChannels(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(36))
        }

        // Kopf: zentriertes Emblem (Telefon oder Countdown) mit Mono-Label, darunter die Statusleiste.
        emblem = HeaderEmblem(this)
        content.addView(emblem, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(Ui.label(this, "Anruf Reminder", Ui.TEAL_TEXT).apply {
            gravity = Gravity.CENTER
        }, lp(top = 2))

        setupTop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(setupTop)

        content.addView(Ui.primaryButton(this, "+  Neue Erinnerung") { askText() }, lp(top = 16))

        content.addView(sectionHead("Geplant"), lp(top = 32))
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(cards, lp(top = 12))

        content.addView(sectionHead("Einstellungen"), lp(top = 32))
        content.addView(settingsCard(), lp(top = 12))

        setupBottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(setupBottom)

        // Inhalt hält Abstand zu den Systemleisten.
        val inner = FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = true
                addView(content)
            })
            // Android 15 zeichnet bis unter die Systemleisten; den Inhalt davon freihalten.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(inner)
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        introOnce()
    }

    override fun onResume() {
        super.onResume()
        // Selbstheilung: falls das System Wecker gelöscht hat (z. B. Xiaomi), beim Öffnen neu setzen.
        Scheduler.rescheduleAll(this)
        refresh()
        showSetup()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    // Statusleiste jede halbe Minute aktualisieren, damit der Countdown stimmt.
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            ticker.postDelayed(this, 30_000L)
        }
    }

    private lateinit var emblem: HeaderEmblem

    /** Kopf-Grafik auf den nächsten geplanten Anruf setzen (Countdown) oder Telefon zeigen. */
    private fun updateStatus() {
        if (!::emblem.isInitialized) return
        val now = System.currentTimeMillis()
        val next = ReminderStore.all(this).filter { it.active && it.nextTrigger > now }.minByOrNull { it.nextTrigger }
        emblem.show(next?.let { it.nextTrigger - now }, next?.let { countdownFraction(it, now) } ?: 0f)
    }

    /** Verstrichener Anteil bis zum Anruf: bezogen auf das Intervall, sonst auf 24 Stunden. */
    private fun countdownFraction(r: Reminder, now: Long): Float {
        val remaining = (r.nextTrigger - now).toFloat()
        val window = when (r.repeat) {
            Repeat.MINUTES -> r.minutes * 60_000f
            else -> 24 * 3_600_000f
        }.coerceAtLeast(remaining)
        return 1f - remaining / window
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun sectionHead(text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.label(this@MainActivity, text))
        addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(12) })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = Ui.pressable(Ui.rounded(Ui.SURFACE, dp(10).toFloat(), Ui.LINE, dp(1)))
    }

    private fun refresh() {
        updateStatus()
        cards.removeAllViews()
        val reminders = ReminderStore.all(this)
        if (reminders.isEmpty()) {
            cards.addView(card().apply {
                addView(Ui.body(this@MainActivity, "Noch keine Erinnerungen. Lege oben die erste an.", Ui.MUTED, 15f))
            })
            return
        }
        reminders.forEachIndexed { i, r ->
            val c = card().apply {
                isClickable = true
                setOnClickListener { itemMenu(r) }
            }
            val meta = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    // Ausgeschaltete einmalige Erinnerungen behalten sichtbar ihr ursprüngliches Datum.
                    text = if (r.active || r.repeat == Repeat.ONCE) r.whenText() else "Ausgeschaltet"
                    typeface = Ui.mono(this@MainActivity)
                    letterSpacing = 0.04f
                    setTextColor(if (r.active) Ui.TEAL_TEXT else Ui.MUTED)
                    // Datum und Uhrzeit immer in einer Zeile: bei Platzmangel kleiner statt Umbruch.
                    maxLines = 1
                    gravity = Gravity.CENTER_VERTICAL
                    setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
                }, LinearLayout.LayoutParams(0, dp(18), 1f).apply { marginEnd = dp(8) })
                val chipColor = if (r.active) Ui.CHIP_TEXT else Ui.MUTED
                // Minütlich kompakt als Stoppuhr + Zahl, damit Datum und Uhrzeit links nicht schrumpfen.
                addView(if (r.repeat == Repeat.MINUTES) Ui.chip(this@MainActivity, r.minutes.toString(), chipColor).apply {
                    val icon = getDrawable(R.drawable.ic_timer)!!.mutate().apply {
                        setTint(chipColor)
                        setBounds(0, 0, dp(12), dp(12))
                    }
                    setCompoundDrawables(icon, null, null, null)
                    compoundDrawablePadding = dp(4)
                    gravity = Gravity.CENTER_VERTICAL
                    contentDescription = r.repeatText()
                } else Ui.chip(this@MainActivity, r.repeatText(), chipColor))
                addView(Switch(this@MainActivity).apply {
                    isChecked = r.active
                    setPadding(dp(10), 0, 0, 0)
                    setOnCheckedChangeListener { _, on -> setActive(r, on) }
                })
            }
            c.addView(meta)
            c.addView(TextView(this).apply {
                if (!r.active) alpha = 0.55f
                text = r.displayTitle()
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                typeface = Ui.sans(this@MainActivity)
                textSize = 16f
                setLineSpacing(0f, 1.25f)
                setTextColor(Ui.INK)
                setPadding(0, dp(8), 0, 0)
            })
            cards.addView(c, lp(top = if (i == 0) 0 else 10))
        }
    }

    private fun settingsCard() = card().apply {
        addView(settingSwitch(
            "Über die Hörmuschel vorlesen",
            ReminderStore.earpiece(this@MainActivity)
        ) { ReminderStore.prefs(this@MainActivity).edit().putBoolean(ReminderStore.KEY_EARPIECE, it).apply() })
        addView(Ui.divider(this@MainActivity), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(10), 0, dp(10)) })
        addView(settingSwitch(
            "Text mehrmals vorlesen",
            ReminderStore.speakTwice(this@MainActivity)
        ) { ReminderStore.prefs(this@MainActivity).edit().putBoolean(ReminderStore.KEY_SPEAK_TWICE, it).apply() })
    }

    private fun settingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val texts = TextView(this@MainActivity).apply {
                text = title
                typeface = Ui.sans(this@MainActivity)
                textSize = 15f
                setTextColor(Ui.INK)
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = checked
                // Einstellungs-Schalter neutral weiß, damit sie sich von den Erinnerungs-Schaltern abheben.
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB9BEBD.toInt()))
                trackTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), Ui.LINE_STRONG))
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
        }

    private class Check(val title: String, val hint: String, val ok: Boolean, val action: () -> Unit)

    /** Alles, was Android bzw. der Hersteller freigeben muss, damit der Anruf zuverlässig kommt. */
    private fun checks(): List<Check> {
        val pkg = Uri.parse("package:$packageName")
        val list = mutableListOf<Check>()
        val nm = getSystemService(NotificationManager::class.java)
        list += Check("Benachrichtigungen", "Ohne sie kann kein Anruf erscheinen.", nm.areNotificationsEnabled()) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        list += Check("Anruf auf dem Sperrbildschirm", "Vollbild-Benachrichtigungen erlauben.", fullScreen) {
            open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        }
        // Ab Android 13 gibt es exakte Wecker automatisch; der Punkt erscheint nur, wenn er wirklich fehlt.
        if (!Scheduler.canScheduleExact(this)) {
            list += Check("Pünktliche Wecker", "Exakte Wecker und Erinnerungen erlauben.", false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
        }
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        list += Check("Akku-Optimierung aus", "Sonst kann Android die App im Hintergrund schlafen legen.", battery) {
            @Suppress("BatteryLife")
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        }
        Vendor.current()?.let { v ->
            val done = ReminderStore.prefs(this).getBoolean(KEY_VENDOR_DONE, false)
            list += Check("Autostart / Hintergrund", v.hint, done) { vendorDialog(v) }
        }
        return list
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun vendorDialog(v: Vendor) {
        AlertDialog.Builder(this)
            .setTitle("Im Hintergrund erlauben")
            .setMessage(v.hint + "\n\nDie Einstellung heißt je nach Modell etwas anders. Die ausführliche Anleitung zeigt Bilder für dein Gerät.")
            .setPositiveButton("Einstellung öffnen") { _, _ ->
                ReminderStore.prefs(this).edit().putBoolean(KEY_VENDOR_DONE, true).apply()
                v.openSettings(this)
            }
            .setNeutralButton("Anleitung") { _, _ -> open(Intent(Intent.ACTION_VIEW, Uri.parse(v.guideUrl))) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    /** Einrichtungs-Karte: oben, solange etwas fehlt; sonst unten als kurze Bestätigung. */
    private fun showSetup() {
        setupTop.removeAllViews()
        setupBottom.removeAllViews()
        val list = checks()
        val done = list.count { it.ok }
        val complete = done == list.size
        val target = if (complete) setupBottom else setupTop

        target.addView(sectionHead("Einrichtung · $done/${list.size}"), lp(top = if (complete) 32 else 20))
        val c = card()
        if (!complete) {
            c.addView(Ui.body(this, "Damit der Anruf auch bei gesperrtem Handy und beendeter App kommt, tippe die offenen Punkte an.", Ui.MUTED, 14f),
                lp().apply { bottomMargin = dp(6) })
        }
        list.forEachIndexed { i, ch ->
            if (i > 0) c.addView(Ui.divider(this), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(10), 0, dp(10)) })
            c.addView(checkRow(ch))
        }
        target.addView(c, lp(top = 12))
    }

    private fun checkRow(ch: Check) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        isClickable = true
        setOnClickListener { ch.action() }
        addView(TextView(this@MainActivity).apply {
            text = if (ch.ok) "✓" else "!"
            typeface = Ui.monoBold(this@MainActivity)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (ch.ok) Ui.TEAL_TEXT else Ui.AMBER)
            background = Ui.oval(if (ch.ok) Ui.TINT else Ui.AMBER_T, if (ch.ok) Ui.TINT_B else Ui.AMBER, dp(1))
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = ch.title
                typeface = Ui.semibold(this@MainActivity)
                textSize = 15f
                setTextColor(if (ch.ok) Ui.TEXT else Ui.INK)
            })
            if (!ch.ok) addView(Ui.body(this@MainActivity, ch.hint, Ui.MUTED, 13f))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Beim allerersten Start kurz erklären, warum die Einrichtung nötig ist. */
    private fun introOnce() {
        val p = ReminderStore.prefs(this)
        if (p.getBoolean(KEY_INTRO, false)) return
        p.edit().putBoolean(KEY_INTRO, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Willkommen")
            .setMessage(
                "Anruf Reminder ruft dich zur eingestellten Zeit an und liest dir deine Erinnerung vor.\n\n" +
                    "Android und viele Hersteller schränken Apps im Hintergrund stark ein. Geh deshalb einmal " +
                    "alle Punkte unter „Einrichtung“ durch, bis überall ein Haken ist. Lege danach zum Testen eine " +
                    "Erinnerung in zwei Minuten an und sperre das Handy."
            )
            .setPositiveButton("Los geht's", null)
            .show()
    }

    // Neue Erinnerung: Titel + Text -> Datum -> Uhrzeit -> Wiederholung -> ggf. Minuten

    /** Eingabefeld des offenen Text-Dialogs, damit die Spracheingabe dort hineinschreiben kann. */
    private var textInput: EditText? = null
    private var titleField: EditText? = null
    /** Feld, in das das laufende Diktat geschrieben wird: Titel oder Details, je nach Cursor. */
    private var speechTarget: EditText? = null

    /** Eingabefenster für eine neue Erinnerung; mit [edit] zum Bearbeiten von Titel und Details. */
    private fun askText(edit: Reminder? = null) {
        val dialog = Dialog(this)
        val input = EditText(this).apply {
            hint = "Details tippen oder diktieren …"
            typeface = Ui.sans(this@MainActivity)
            textSize = 17f
            setTextColor(Ui.INK)
            setHintTextColor(0xFF5D6564.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(0, dp(12), 0, dp(16))
        }
        textInput = input
        edit?.let { input.setText(it.text) }

        // Optionaler Titel für die Liste, mit feiner Linie vom Text getrennt.
        val titleInput = EditText(this).apply {
            hint = "Titel (optional)"
            typeface = Ui.semibold(this@MainActivity)
            textSize = 17f
            setTextColor(Ui.INK)
            setHintTextColor(0xFF5D6564.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_NEXT
            background = null
            setPadding(0, dp(14), 0, dp(10))
            edit?.let { setText(it.title) }
        }

        // Kopf: kleiner Punkt + Mono-Label, darunter die Frage.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply {
                background = Ui.oval(Ui.TEAL, 0x2E1FB5A8, dp(3))
            }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(Ui.label(this@MainActivity, "Erinnerung", Ui.TEAL_TEXT).apply {
                setPadding(dp(8), 0, 0, 0)
            })
        }
        val question = TextView(this).apply {
            text = "Woran soll ich dich erinnern?"
            // Leicht und grau wie ein Untertitel, damit sie sich von Titel und Text abhebt.
            typeface = Ui.sans(this@MainActivity)
            textSize = 15f
            setTextColor(Ui.MUTED)
            setPadding(0, dp(8), 0, 0)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), 0)
            addView(head)
            addView(question)
            addView(titleInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE_STRONG) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // Fußleiste: Mikrofon-Quadrat links, Abbrechen und Weiter rechts.
        val mic = FrameLayout(this).apply {
            background = Ui.pressable(Ui.rounded(0x00000000, dp(9).toFloat(), Ui.LINE_STRONG, dp(1)))
            isClickable = true
            contentDescription = "Sprechen"
            setOnClickListener { listen() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_mic)
                setColorFilter(Ui.TEAL_TEXT)
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        fun textButton(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label
            typeface = Ui.semibold(this@MainActivity)
            textSize = 15f
            setTextColor(color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.pressable(Ui.rounded(0x00000000, dp(9).toFloat()))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1D2223.toInt())
            setPadding(dp(16), dp(12), dp(12), dp(12))
            addView(mic, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(textButton("Abbrechen", Ui.MUTED) { dialog.dismiss() })
            addView(textButton(if (edit == null) "Weiter →" else "Speichern", Ui.TEAL_TEXT) {
                val text = input.text.toString().trim()
                if (text.isEmpty()) toast("Bitte die Details eingeben oder diktieren")
                else {
                    dialog.dismiss()
                    val title = titleInput.text.toString().trim()
                    if (edit == null) askDate(title, text) else saveContent(edit, title, text)
                }
            })
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xFF181C1D.toInt(), dp(18).toFloat(), Ui.LINE, dp(1))
            clipToOutline = true
            addView(body)
            addView(Ui.divider(this@MainActivity), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            addView(footer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        titleField = titleInput
        dialog.setOnDismissListener {
            if (textInput === input) textInput = null
            if (titleField === titleInput) titleField = null
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        titleInput.requestFocus()
    }

    /** Startet die Spracheingabe des Systems; das Ergebnis landet im Textfeld. */
    private fun listen() {
        // Steht der Cursor im Titel, wird der Titel diktiert, sonst die Details.
        speechTarget = titleField?.takeIf { it.hasFocus() } ?: textInput
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (speechTarget === titleField) "Titel sprechen" else "Details sprechen")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            toast("Keine Spracheingabe gefunden. Bitte die Google-App installieren oder aktivieren.")
        }
    }

    @Deprecated("Activity ohne AndroidX: klassischer Weg")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (spoken.isNullOrEmpty()) return
        val input = speechTarget ?: return
        // Gesagtes anhängen, damit man in mehreren Etappen diktieren kann.
        val before = input.text.toString().trimEnd()
        val text = if (before.isEmpty()) spoken.replaceFirstChar { it.uppercase() } else "$before $spoken"
        input.setText(text)
        input.setSelection(text.length)
        // Nach dem Titel gleich mit den Details weitermachen.
        if (input === titleField) textInput?.requestFocus()
    }

    /** Nur Titel und Details ändern; Termin und Wiederholung bleiben. */
    private fun saveContent(r: Reminder, title: String, text: String) {
        val current = ReminderStore.get(this, r.id) ?: return
        ReminderStore.save(this, current.copy(title = title, text = text))
        toast("Gespeichert")
        refresh()
    }

    private fun askDate(title: String, text: String, edit: Reminder? = null) {
        val today = edit?.let { Instant.ofEpochMilli(it.nextTrigger).atZone(ZoneId.systemDefault()).toLocalDate() }
            ?.takeIf { !it.isBefore(LocalDate.now()) } ?: LocalDate.now()
        DatePickerDialog(this, { _, y, m, d ->
            askTime(title, text, LocalDate.of(y, m + 1, d), edit)
        }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    private fun askTime(title: String, text: String, date: LocalDate, edit: Reminder? = null) {
        val now = edit?.let { LocalTime.of(it.hour, it.minute) } ?: LocalTime.now().plusMinutes(2)
        TimePickerDialog(this, { _, h, min ->
            askRepeat(title, text, date, h, min, edit)
        }, now.hour, now.minute, true).show()
    }

    private fun askRepeat(title: String, text: String, date: LocalDate, h: Int, min: Int, edit: Reminder? = null) {
        val options = Repeat.values()
        AlertDialog.Builder(this)
            .setTitle("Wiederholen?")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                when (options[which]) {
                    Repeat.MINUTES -> askMinutes(title, text, date, h, min, edit)
                    else -> create(title, text, date, h, min, options[which], 1, edit)
                }
            }
            .show()
    }

    /** Intervall per Scroll-Rad wählen, im gleichen Stil wie das Eingabefenster. */
    private fun askMinutes(title: String, text: String, date: LocalDate, h: Int, min: Int, edit: Reminder? = null) {
        val dialog = Dialog(this)
        val start = edit?.takeIf { it.repeat == Repeat.MINUTES }?.minutes ?: 30
        val wheel = WheelPicker(this, 1, 1440, start, "MIN")

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply {
                background = Ui.oval(Ui.TEAL, 0x2E1FB5A8, dp(3))
            }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(Ui.label(this@MainActivity, "Wiederholung", Ui.TEAL_TEXT).apply {
                setPadding(dp(8), 0, 0, 0)
            })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(10))
            addView(head)
            addView(TextView(this@MainActivity).apply {
                this.text = "Alle wie viele Minuten?"
                typeface = Ui.sans(this@MainActivity)
                textSize = 15f
                setTextColor(Ui.MUTED)
                setPadding(0, dp(8), 0, dp(10))
            })
            addView(wheel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        fun textButton(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            this.text = label
            typeface = Ui.semibold(this@MainActivity)
            textSize = 15f
            setTextColor(color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.pressable(Ui.rounded(0x00000000, dp(9).toFloat()))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setBackgroundColor(0xFF1D2223.toInt())
            setPadding(dp(16), dp(12), dp(12), dp(12))
            addView(textButton("Abbrechen", Ui.MUTED) { dialog.dismiss() })
            addView(textButton("Übernehmen", Ui.TEAL_TEXT) {
                dialog.dismiss()
                create(title, text, date, h, min, Repeat.MINUTES, wheel.value, edit)
            })
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xFF181C1D.toInt(), dp(18).toFloat(), Ui.LINE, dp(1))
            clipToOutline = true
            addView(body)
            addView(Ui.divider(this@MainActivity), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            addView(footer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** Legt eine neue Erinnerung an oder ersetzt mit [edit] den Zeitplan einer bestehenden (gleiche ID, wieder eingeschaltet). */
    private fun create(title: String, text: String, date: LocalDate, h: Int, min: Int, repeat: Repeat, minutes: Int, edit: Reminder? = null) {
        val first = LocalDateTime.of(date, LocalTime.of(h, min))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var r = Reminder(edit?.id ?: ReminderStore.newId(this), text, first, repeat, minutes, h, min, true, title)
        val now = System.currentTimeMillis()
        if (first <= now) {
            val next = r.following(now)
            if (next == null) {
                toast("Dieser Zeitpunkt liegt in der Vergangenheit")
                return
            }
            r = r.copy(nextTrigger = next)
        }
        ReminderStore.save(this, r)
        Scheduler.schedule(this, r)
        toast("Nächste Erinnerung: " + SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMAN).format(Date(r.nextTrigger)))
        refresh()
    }

    /** Menü einer Erinnerung: Kopf mit Titel und Termin, darunter Zeilen mit Symbol-Quadraten. */
    private fun itemMenu(r: Reminder) {
        val dialog = Dialog(this)
        val red = 0xFFE66A5E.toInt()

        fun row(icon: Int, label: String, danger: Boolean, last: Boolean, onClick: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(16), dp(12))
                background = Ui.pressable(Ui.rounded(0x00000000, 0f))
                isClickable = true
                setOnClickListener { dialog.dismiss(); onClick() }
                val sq = FrameLayout(this@MainActivity).apply {
                    background = if (danger) Ui.rounded(0xFF2E1D1B.toInt(), dp(9).toFloat(), 0xFF5A2F2A.toInt(), dp(1))
                    else Ui.rounded(Ui.TINT, dp(9).toFloat(), Ui.TINT_B, dp(1))
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(icon)
                        setColorFilter(if (danger) red else Ui.TEAL_TEXT)
                    }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                }
                addView(sq, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    typeface = Ui.sans(this@MainActivity)
                    textSize = 15.5f
                    setTextColor(if (danger) red else Ui.INK)
                    setPadding(dp(14), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (!danger) addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_chevron)
                    setColorFilter(0xFF5D6564.toInt())
                }, LinearLayout.LayoutParams(dp(16), dp(16)))
            }.let { v ->
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(v)
                    if (!last) addView(Ui.divider(this@MainActivity), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(20), 0, dp(20), 0) })
                }
            }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(8))
            addView(Ui.label(this@MainActivity, "Erinnerung", Ui.TEAL_TEXT))
            addView(TextView(this@MainActivity).apply {
                text = r.displayTitle()
                typeface = Ui.semibold(this@MainActivity)
                textSize = 19f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Ui.INK)
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = (if (r.active) r.whenText() + " · " else "Ausgeschaltet · ") + r.repeatText()
                typeface = Ui.mono(this@MainActivity)
                textSize = 10.5f
                letterSpacing = 0.1f
                isAllCaps = true
                setTextColor(Ui.MUTED)
                setPadding(0, dp(6), 0, 0)
            })
        }

        val close = TextView(this).apply {
            text = "Schließen"
            typeface = Ui.semibold(this@MainActivity)
            textSize = 14.5f
            setTextColor(Ui.MUTED)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.pressable(Ui.rounded(0x00000000, dp(9).toFloat()))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xFF181C1D.toInt(), dp(18).toFloat(), Ui.LINE, dp(1))
            clipToOutline = true
            addView(head)
            addView(row(R.drawable.ic_edit, "Inhalt bearbeiten", false, false) { askText(r) })
            addView(row(R.drawable.ic_timer, "Zeitplan bearbeiten", false, false) { askDate(r.title, r.text, r) })
            addView(row(R.drawable.ic_delete, "Löschen", true, true) {
                Scheduler.cancel(this@MainActivity, r.id)
                ReminderStore.delete(this@MainActivity, r.id)
                refresh()
            })
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.END
                setPadding(dp(10), dp(4), dp(10), dp(12))
                addView(close)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** Schalter an der Karte: Erinnerung pausieren oder wieder einplanen. */
    private fun setActive(r: Reminder, on: Boolean) {
        val current = ReminderStore.get(this, r.id) ?: return
        if (on) {
            val next = current.nextFrom(System.currentTimeMillis())
            if (next == null) {
                // Einmalig und schon vorbei: nicht auf morgen schieben, sondern neuen Zeitpunkt wählen lassen.
                toast("Dieser Termin ist vorbei. Bitte einen neuen Zeitpunkt wählen.")
                refresh()
                askDate(current.title, current.text, current)
                return
            }
            val updated = current.copy(active = true, nextTrigger = next)
            ReminderStore.save(this, updated)
            Scheduler.schedule(this, updated)
            toast("Nächste Erinnerung: " + SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMAN).format(Date(updated.nextTrigger)))
        } else {
            Scheduler.cancel(this, current.id)
            ReminderStore.save(this, current.copy(active = false))
        }
        refresh()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
