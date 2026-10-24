package de.matija.anrufreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Scheduler {
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TEST_TEXT = "test_text"
    private const val TEST_REQUEST = -1

    private fun firePending(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPending(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Plant die Erinnerung als Wecker (setAlarmClock), das ist auch im Doze-Modus pünktlich. */
    fun schedule(ctx: Context, r: Reminder) {
        if (!r.active) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fire = firePending(ctx, r.id)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(r.nextTrigger, showPending(ctx)), fire)
        } catch (e: SecurityException) {
            // Ohne Erlaubnis für exakte Wecker: ungenauer, aber besser als gar nicht.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.nextTrigger, fire)
        }
    }

    /** Test-Anruf in [delayMs], ohne dafür einen Eintrag in der Liste anzulegen. */
    fun scheduleTest(ctx: Context, text: String, delayMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_TEST_TEXT, text)
        val fire = PendingIntent.getBroadcast(
            ctx, TEST_REQUEST, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + delayMs
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPending(ctx)), fire)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePending(ctx, id))
    }

    /** Nach Neustart, Update oder Zeitänderung alle aktiven Erinnerungen neu planen. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (r in ReminderStore.all(ctx)) {
            if (!r.active) continue
            var updated = r
            if (r.nextTrigger <= now) {
                // Verpasst (Handy war aus): einmalige gleich nachholen, wiederholende auf den nächsten Termin.
                val next = r.following(now) ?: (now + 15_000L)
                updated = r.copy(nextTrigger = next)
                ReminderStore.save(ctx, updated)
            }
            schedule(ctx, updated)
        }
    }
}
