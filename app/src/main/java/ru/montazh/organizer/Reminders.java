package ru.montazh.organizer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Планирование напоминаний через AlarmManager. Список хранится в SharedPreferences,
 *  чтобы после перезагрузки телефона его можно было восстановить. */
final class Reminders {
    static final String ACTION_REMIND = "ru.montazh.organizer.REMIND";
    private static final String PREFS = "reminders";
    private static final String KEY = "list";

    private Reminders() { }

    /** Заменяет все запланированные напоминания новым списком. */
    static void apply(Context c, String json) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cancelAll(c, p.getString(KEY, "[]"));
        p.edit().putString(KEY, json).apply();
        scheduleAll(c, json);
    }

    /** Повторно ставит сохранённые напоминания (после перезагрузки или обновления). */
    static void restore(Context c) {
        scheduleAll(c, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
    }

    private static Intent intentFor(Context c, JSONObject r) {
        return new Intent(c, ReminderReceiver.class)
                .setAction(ACTION_REMIND)
                .putExtra("id", r.optString("id"))
                .putExtra("title", r.optString("title"))
                .putExtra("text", r.optString("text"));
    }

    private static void scheduleAll(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                long at = r.optLong("at");
                if (at <= now) continue;
                PendingIntent pi = PendingIntent.getBroadcast(c, r.optString("id").hashCode(), intentFor(c, r),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
            }
        } catch (Exception ignored) { }
    }

    private static void cancelAll(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                PendingIntent pi = PendingIntent.getBroadcast(c, r.optString("id").hashCode(), intentFor(c, r),
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (pi != null) {
                    am.cancel(pi);
                    pi.cancel();
                }
            }
        } catch (Exception ignored) { }
    }
}
