package de.matija.anrufreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wird vom Wecker ausgelöst: startet das Klingeln und plant den nächsten Termin. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        intent.getStringExtra(Scheduler.EXTRA_TEST_TEXT)?.let {
            RingService.start(ctx, -1, it)
            return
        }
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val r = ReminderStore.get(ctx, id) ?: return
        if (!r.active) return

        RingService.start(ctx, r.id, r.spoken())

        val next = r.following(System.currentTimeMillis())
        if (next == null) {
            ReminderStore.save(ctx, r.copy(active = false))
        } else {
            val updated = r.copy(nextTrigger = next)
            ReminderStore.save(ctx, updated)
            Scheduler.schedule(ctx, updated)
        }
    }
}

/** Wecker gehen bei Neustart verloren, deshalb hier neu planen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
    }
}
