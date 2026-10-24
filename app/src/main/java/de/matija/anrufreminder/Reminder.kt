package de.matija.anrufreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

enum class Repeat(val label: String) {
    ONCE("Einmalig"),
    DAILY("Täglich"),
    MINUTES("Minütlich")
}

data class Reminder(
    val id: Int,
    val text: String,
    val nextTrigger: Long,
    val repeat: Repeat,
    /** Intervall bei [Repeat.MINUTES], in Minuten. */
    val minutes: Int,
    val hour: Int,
    val minute: Int,
    val active: Boolean,
    val title: String = ""
) {
    /** Für die Liste: der Titel, wenn vorhanden, sonst der Text. */
    fun displayTitle(): String = title.ifBlank { text }

    /** Was beim Anruf vorgelesen wird: der Text, ohne Text der Titel. */
    fun spoken(): String = text.ifBlank { title }

    /** "Heute · 15:30", "Morgen · 15:29", sonst "So. 25.10. · 15:29", damit der Tag sofort klar ist. */
    fun whenText(): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(nextTrigger).atZone(zone).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        val time = SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date(nextTrigger))
        return when (day) {
            today -> "Heute · $time"
            today.plusDays(1) -> "Morgen · $time"
            else -> SimpleDateFormat("EE dd.MM. · HH:mm", Locale.GERMAN).format(Date(nextTrigger))
        }
    }

    fun repeatText(): String = when (repeat) {
        Repeat.ONCE -> "einmalig"
        Repeat.DAILY -> "täglich"
        Repeat.MINUTES -> "alle $minutes Min."
    }

    /** Nächster Termin nach dem aktuellen, immer in der Zukunft. Null bei einmaligen Erinnerungen. */
    fun following(now: Long): Long? = when (repeat) {
        Repeat.ONCE -> null
        Repeat.DAILY -> {
            // Über Datum + Uhrzeit rechnen, damit die Uhrzeit bei Sommer-/Winterzeit gleich bleibt.
            val zone = ZoneId.systemDefault()
            var t = Instant.ofEpochMilli(nextTrigger).atZone(zone)
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            do {
                t = t.plusDays(1).withHour(hour).withMinute(minute)
            } while (t.toInstant().toEpochMilli() <= now)
            t.toInstant().toEpochMilli()
        }
        Repeat.MINUTES -> {
            val step = minutes.coerceAtLeast(1) * 60_000L
            var t = nextTrigger + step
            while (t <= now) t += step
            t
        }
    }

    /**
     * Termin beim Wiedereinschalten: der alte, wenn er noch kommt, sonst der nächste passende in der Zukunft.
     * Null bei einer einmaligen Erinnerung, deren Zeit vorbei ist: deren Datum wird nie still verschoben.
     */
    fun nextFrom(now: Long): Long? {
        if (nextTrigger > now) return nextTrigger
        return following(now)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("next", nextTrigger)
        .put("repeat", repeat.name)
        .put("minutes", minutes)
        .put("hour", hour)
        .put("minute", minute)
        .put("active", active)
        .put("title", title)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            id = o.getInt("id"),
            text = o.getString("text"),
            nextTrigger = o.getLong("next"),
            // Ältere Versionen speicherten "Alle X Stunden" (HOURS/hours) – in Minuten umrechnen.
            repeat = o.getString("repeat").let { if (it == "HOURS") Repeat.MINUTES else Repeat.valueOf(it) },
            minutes = if (o.has("minutes")) o.getInt("minutes") else o.optInt("hours", 1) * 60,
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            active = o.optBoolean("active", true),
            title = o.optString("title", "")
        )
    }
}

/** Speichert alle Erinnerungen als JSON in den SharedPreferences. */
object ReminderStore {
    private const val PREFS = "reminders"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT_ID = "next_id"
    const val KEY_EARPIECE = "earpiece"
    const val KEY_SPEAK_TWICE = "speak_twice"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Reminder> {
        val raw = prefs(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
            // Nach nächstem Anruf sortiert, oben steht also der Anruf, den auch der Countdown zeigt. Ausschalten verschiebt keine Karte.
            .sortedWith(compareBy<Reminder>({ it.nextTrigger }, { it.id }))
    }

    fun get(ctx: Context, id: Int): Reminder? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun save(ctx: Context, r: Reminder) {
        val list = all(ctx).filter { it.id != r.id } + r
        write(ctx, list)
    }

    @Synchronized
    fun delete(ctx: Context, id: Int) {
        write(ctx, all(ctx).filter { it.id != id })
    }

    @Synchronized
    fun newId(ctx: Context): Int {
        val p = prefs(ctx)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    fun earpiece(ctx: Context) = prefs(ctx).getBoolean(KEY_EARPIECE, true)
    /** "Text mehrmals vorlesen": Schleife bis zum Auflegen (Schlüssel aus der früheren Zweimal-Option). */
    fun speakTwice(ctx: Context) = prefs(ctx).getBoolean(KEY_SPEAK_TWICE, true)

    private fun write(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
