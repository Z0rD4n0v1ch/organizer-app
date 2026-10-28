package ru.montazh.organizer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Виджет «Сегодня»: задачи на сегодня и идущий таймер рабочего времени. Данные присылает страница (Bridge.widgetUpdate). */
public class TodayWidget extends AppWidgetProvider {
    static final String PREF = "widget";
    private static final int[] ROWS = {R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4, R.id.w_t5};
    private static final String[] WD = {"воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"};
    private static final String[] MON = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c));
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, TodayWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews v = build(c);
        for (int id : ids) m.updateAppWidget(id, v);
    }

    static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_today);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Date now = new Date();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now);
        v.setTextViewText(R.id.w_title, "Сегодня, " + cal.get(java.util.Calendar.DAY_OF_MONTH) + " " + MON[cal.get(java.util.Calendar.MONTH)] + ", " + WD[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]);
        JSONObject d;
        try { d = new JSONObject(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("data", "{}")); } catch (Exception e) { d = new JSONObject(); }
        JSONArray all = d.optJSONArray("tasks");
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        int done = 0, total = 0;
        if (all != null) for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t == null || !today.equals(t.optString("date"))) continue;
            total++;
            if (t.optBoolean("done")) done++; else list.add(t);
        }
        v.setTextViewText(R.id.w_count, total > 0 ? done + " из " + total : "");
        for (int i = 0; i < ROWS.length; i++) {
            if (i < list.size()) {
                JSONObject t = list.get(i);
                String time = t.optString("time"), obj = t.optString("obj");
                String s = (time.isEmpty() ? "• " : time + "  ") + t.optString("title") + (obj.isEmpty() ? "" : " · " + obj);
                v.setTextViewText(ROWS[i], s);
                v.setTextColor(ROWS[i], t.optBoolean("urgent") ? 0xFFC62828 : 0xFF16151C);
                v.setViewVisibility(ROWS[i], View.VISIBLE);
            } else v.setViewVisibility(ROWS[i], View.GONE);
        }
        int more = list.size() - ROWS.length;
        v.setViewVisibility(R.id.w_more, more > 0 ? View.VISIBLE : View.GONE);
        if (more > 0) v.setTextViewText(R.id.w_more, "ещё " + more);
        v.setViewVisibility(R.id.w_empty, list.isEmpty() ? View.VISIBLE : View.GONE);
        if (list.isEmpty() && total > 0) v.setTextViewText(R.id.w_empty, "Всё на сегодня сделано");
        JSONObject tm = d.optJSONObject("timer");
        if (tm != null && tm.optLong("start") > 0) {
            long base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - tm.optLong("start"));
            v.setViewVisibility(R.id.w_run, View.VISIBLE);
            v.setTextViewText(R.id.w_run_obj, "Идёт работа" + (tm.optString("obj").isEmpty() ? "" : ": " + tm.optString("obj")));
            v.setChronometer(R.id.w_chrono, base, null, true);
        } else {
            v.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime(), null, false);
            v.setViewVisibility(R.id.w_run, View.GONE);
        }
        return v;
    }
}
