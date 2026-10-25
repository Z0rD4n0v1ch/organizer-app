package ru.montazh.organizer;

import android.app.Activity;
import android.app.PendingIntent;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    /** Репозиторий, откуда приложение берёт обновления. */
    private static final String REPO = "Z0rD4n0v1ch/organizer-app";
    private static final String ACTION_INSTALL = "ru.montazh.organizer.INSTALL_STATUS";

    private static final int REQ_SAVE = 1;
    private static final int REQ_OPEN = 2;
    private static final int REQ_CAMERA = 3;
    private static final int REQ_NOTIF = 4;

    private WebView web;
    private String pendingBackup;
    private ValueCallback<Uri[]> fileCallback;
    private volatile boolean downloading = false;
    private Uri cameraUri;
    private boolean notifAsked = false;

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(confirm); } catch (Exception e) { jsState("error", "не открылось окно установки"); }
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                jsState("done", "");
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                jsState(status == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancel" : "error", msg == null ? "" : msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternal(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean image = false, pdf = false;
                String[] types = params.getAcceptTypes();
                if (types != null) for (String t : types) { if (t != null && t.startsWith("image")) image = true; if (t != null && t.contains("pdf")) pdf = true; }
                boolean multiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                try {
                    if (image && params.isCaptureEnabled() && Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Images.Media.DISPLAY_NAME, "object_" + System.currentTimeMillis() + ".jpg");
                        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Органайзер");
                        cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(i, REQ_CAMERA);
                    } else {
                        Intent i = new Intent(image ? Intent.ACTION_GET_CONTENT : Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType(image ? "image/*" : pdf ? "application/pdf" : "*/*");
                        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(i, REQ_OPEN);
                    }
                } catch (Exception e) {
                    if (cameraUri != null) { try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { } cameraUri = null; }
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Не удалось открыть камеру или галерею", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        IntentFilter f = new IntentFilter(ACTION_INSTALL);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installReceiver, f);

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(installReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    private boolean openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) return false;
        try {
            Intent i = "tel".equals(scheme) ? new Intent(Intent.ACTION_DIAL, uri) : new Intent(Intent.ACTION_VIEW, uri);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            String pkg = "yandexnavi".equals(scheme) ? "ru.yandex.yandexnavi"
                    : "yandexmaps".equals(scheme) ? "ru.yandex.yandexmaps"
                    : "dgis".equals(scheme) ? "ru.dublgis.dgismobile" : null;
            if (pkg != null) {
                Toast.makeText(this, "Приложение не установлено — открываю Google Play", Toast.LENGTH_SHORT).show();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
                } catch (ActivityNotFoundException e2) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg))); } catch (Exception ignored) { }
                }
            } else Toast.makeText(this, "Нет приложения, чтобы открыть ссылку", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /* ---------- вызовы в страницу ---------- */
    private void callJs(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }
    private void jsState(String state, String msg) {
        callJs("window.onUpdateState && window.onUpdateState(" + JSONObject.quote(state) + "," + JSONObject.quote(msg) + ")");
    }

    @SuppressWarnings("deprecation")
    private int currentVersion() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private class Bridge {
        @JavascriptInterface
        public int versionCode() { return currentVersion(); }

        /* ---- загрузка данных из интернета (каталоги) ---- */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            new Thread(() -> {
                int code = 0;
                String body = "";
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("User-Agent", "organizer-app");
                    c.setRequestProperty("Cache-Control", "no-cache");
                    c.setUseCaches(false);
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    code = c.getResponseCode();
                    if (code == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                            char[] buf = new char[8192];
                            int n;
                            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                        }
                        body = sb.toString();
                    }
                } catch (Exception e) {
                    code = -1;
                    body = String.valueOf(e.getMessage());
                }
                callJs("window.onHttp && window.onHttp(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")");
            }).start();
        }

        /* ---- файлы данных приложения (каталоги) ---- */
        private File dataFile(String name) {
            File dir = new File(getFilesDir(), "data");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, name.replaceAll("[^A-Za-z0-9_.-]", ""));
        }

        @JavascriptInterface
        public boolean saveFile(String name, String text) {
            File f = dataFile(name), tmp = dataFile(name + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return false;
            }
            return tmp.renameTo(f);
        }

        @JavascriptInterface
        public String loadFile(String name) {
            File f = dataFile(name);
            if (!f.exists()) return null;
            try (InputStream in = new java.io.FileInputStream(f)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        /* ---- отправка текста в мессенджер ---- */
        @JavascriptInterface
        public void shareText(final String title, final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(i, "Отправить"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Нет приложения для отправки", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /* ---- отправка фото с подписью (выбор приложения, например Telegram) ---- */
        @JavascriptInterface
        public void sharePhoto(final String photoId, final String text) {
            runOnUiThread(() -> {
                Uri u = PhotoProvider.uriFor(photoId);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("image/jpeg");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.putExtra(Intent.EXTRA_TEXT, text);
                i.setClipData(android.content.ClipData.newRawUri("", u));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    Intent ch = Intent.createChooser(i, "Отправить");
                    ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(ch);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Нет приложения для отправки", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /* ---- PDF-документы объектов: файлы во внутренней памяти, c_* — временные (скачанные из справочника) ---- */
        private File docFile(String id) {
            File dir = new File(id.startsWith("c_") ? getCacheDir() : getFilesDir(), "docs");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "") + ".pdf");
        }

        @JavascriptInterface
        public boolean docWrite(String id, String base64, boolean append) {
            try (FileOutputStream out = new FileOutputStream(docFile(id), append)) {
                out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public double docSize(String id) {
            File f = docFile(id);
            return f.exists() ? f.length() : -1;
        }

        @JavascriptInterface
        public String docRead(String id, double off, int len) {
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(docFile(id), "r")) {
                long o = (long) off;
                int n = (int) Math.max(0, Math.min(len, r.length() - o));
                byte[] b = new byte[n];
                r.seek(o);
                r.readFully(b);
                return Base64.encodeToString(b, Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public void docDelete(String id) {
            //noinspection ResultOfMethodCallIgnored
            docFile(id).delete();
        }

        @JavascriptInterface
        public void docShare(final String id, final String name) {
            runOnUiThread(() -> {
                Uri u = PhotoProvider.docUri(id, name);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("application/pdf");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.setClipData(android.content.ClipData.newRawUri("", u));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    Intent ch = Intent.createChooser(i, "Отправить");
                    ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(ch);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Нет приложения для отправки", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** Скачивает PDF по ссылке (документация из справочника), результат — window.onDocFetch(req, код, текст ошибки). */
        @JavascriptInterface
        public void docFetch(final String req, final String url, final String id) {
            new Thread(() -> {
                int code;
                String err = "";
                File f = docFile(id), tmp = new File(f.getPath() + ".part");
                try {
                    String u = url;
                    HttpURLConnection c = null;
                    for (int hop = 0; hop < 6; hop++) {
                        c = (HttpURLConnection) new URL(u).openConnection();
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) organizer-app");
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(60000);
                        int rc = c.getResponseCode();
                        if (rc >= 300 && rc < 400 && c.getHeaderField("Location") != null) {
                            u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                            c.disconnect();
                            continue;
                        }
                        break;
                    }
                    code = c.getResponseCode();
                    if (code == 200) {
                        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        if (!tmp.renameTo(f)) { code = -1; err = "не удалось сохранить файл"; }
                    }
                } catch (Exception e) {
                    code = -1;
                    err = String.valueOf(e.getMessage());
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                callJs("window.onDocFetch && window.onDocFetch(" + JSONObject.quote(req) + "," + code + "," + JSONObject.quote(err) + ")");
            }).start();
        }

        /* ---- отправка в Telegram от имени бота (Bot API) ---- */
        @JavascriptInterface
        public void tgSend(final String id, final String token, final String chat, final String caption, final String photoId) {
            tgSendVia(id, "https://api.telegram.org", token, chat, caption, photoId);
        }

        @JavascriptInterface
        public void tgSendVia(final String id, final String base, final String token, final String chat, final String caption, final String photoId) {
            new Thread(() -> {
                int code = -1;
                String body = "";
                try {
                    File photo = (photoId == null || photoId.isEmpty()) ? null : photoFile(photoId);
                    boolean withPhoto = photo != null && photo.exists();
                    String b0 = (base == null || base.isEmpty()) ? "https://api.telegram.org" : base.replaceAll("/+$", "");
                    URL url = new URL(b0 + "/bot" + token + (withPhoto ? "/sendPhoto" : "/sendMessage"));
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    c.setDoOutput(true);
                    String b = "----org" + System.currentTimeMillis();
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
                    try (OutputStream out = c.getOutputStream()) {
                        writePart(out, b, "chat_id", chat);
                        writePart(out, b, withPhoto ? "caption" : "text", caption);
                        if (withPhoto) {
                            out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"probeg.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            try (InputStream in = new java.io.FileInputStream(photo)) {
                                byte[] buf = new byte[65536];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        }
                        out.write(("--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                    code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (is != null) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                        }
                        body = sb.toString();
                    }
                } catch (Exception e) {
                    code = -1;
                    body = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                callJs("window.onTg && window.onTg(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")");
            }).start();
        }

        private void writePart(OutputStream out, String b, String name, String value) throws java.io.IOException {
            out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        /* ---- напоминания ---- */
        @JavascriptInterface
        public void setReminders(final String json) {
            Reminders.apply(MainActivity.this, json);
            if (Build.VERSION.SDK_INT >= 33 && !notifAsked && json.length() > 2
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifAsked = true;
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
            }
        }

        /* ---- фото объектов (хранятся во внутренней памяти приложения) ---- */
        private File photoFile(String id) {
            File dir = new File(getFilesDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "") + ".jpg");
        }

        @JavascriptInterface
        public boolean savePhoto(String id, String base64) {
            try (FileOutputStream out = new FileOutputStream(photoFile(id))) {
                out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String photoUrl(String id) {
            return Uri.fromFile(photoFile(id)).toString();
        }

        @JavascriptInterface
        public void deletePhoto(String id) {
            //noinspection ResultOfMethodCallIgnored
            photoFile(id).delete();
        }

        @JavascriptInterface
        public void saveBackup(final String fileName, final String json) {
            runOnUiThread(() -> {
                pendingBackup = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "Не удалось открыть выбор папки", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /* ---- автосохранение копии данных в «Загрузки/Органайзер» без вопросов ---- */
        @JavascriptInterface
        public String autoBackup(String fileName, String json, int keep) {
            String safe = fileName.replaceAll("[^A-Za-z0-9_.-]", "");
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentResolver cr = getContentResolver();
                    String rel = android.os.Environment.DIRECTORY_DOWNLOADS + "/Органайзер";
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                    Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return "";
                    try (OutputStream out = cr.openOutputStream(u)) {
                        if (out == null) return "";
                        out.write(data);
                    }
                    // оставляем только последние keep автокопий
                    try (android.database.Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            new String[]{MediaStore.MediaColumns._ID},
                            MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                            new String[]{rel + "%", "organizer-auto-%"},
                            MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                        int i = 0;
                        while (c != null && c.moveToNext()) {
                            if (++i > Math.max(1, keep)) {
                                Uri del = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                                try { cr.delete(del, null, null); } catch (Exception ignored) { }
                            }
                        }
                    } catch (Exception ignored) { }
                    return "Загрузки/Органайзер";
                } else {
                    File dir = new File(getExternalFilesDir(null), "backups");
                    if (!dir.exists()) dir.mkdirs();
                    try (OutputStream out = new FileOutputStream(new File(dir, safe))) { out.write(data); }
                    File[] fs = dir.listFiles((d, n) -> n.startsWith("organizer-auto-"));
                    if (fs != null && fs.length > keep) {
                        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                        for (int i = keep; i < fs.length; i++) //noinspection ResultOfMethodCallIgnored
                            fs[i].delete();
                    }
                    return "Android/data/" + getPackageName() + "/files/backups";
                }
            } catch (Exception e) {
                return "";
            }
        }

        /** Спрашивает GitHub о последней сборке и отдаёт ответ в window.onUpdateInfo. */
        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                try {
                    out.put("current", currentVersion());
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setRequestProperty("User-Agent", "organizer-app");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject rel = new JSONObject(sb.toString());
                    int latest = 0;
                    Matcher m = Pattern.compile("(\\d+)\\s*$").matcher(rel.optString("name", ""));
                    if (m.find()) latest = Integer.parseInt(m.group(1));
                    out.put("latest", latest);
                    out.put("date", rel.optString("published_at", ""));
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").endsWith(".apk")) {
                                out.put("url", a.optString("browser_download_url"));
                                out.put("size", a.optLong("size"));
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
                }
                callJs("window.onUpdateInfo && window.onUpdateInfo(" + JSONObject.quote(out.toString()) + ")");
            }).start();
        }

        /** Скачивает APK и передаёт его системному установщику. */
        @JavascriptInterface
        public void installUpdate(final String url) {
            if (downloading) return;
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                });
                jsState("permission", "");
                return;
            }
            downloading = true;
            new Thread(() -> {
                PackageInstaller.Session session = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("User-Agent", "organizer-app");
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    long total = c.getContentLengthLong();

                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(getPackageName());
                    int id = pi.createSession(params);
                    session = pi.openSession(id);

                    try (InputStream in = c.getInputStream();
                         OutputStream out = session.openWrite("organizer.apk", 0, total > 0 ? total : -1)) {
                        byte[] buf = new byte[64 * 1024];
                        long done = 0;
                        int n, lastPct = -1;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    callJs("window.onUpdateProgress && window.onUpdateProgress(" + pct + ")");
                                }
                            }
                        }
                        session.fsync(out);
                    }

                    Intent i = new Intent(ACTION_INSTALL).setPackage(getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent p = PendingIntent.getBroadcast(MainActivity.this, id, i, flags);
                    jsState("installing", "");
                    session.commit(p.getIntentSender());
                    session.close();
                    session = null;
                } catch (Exception e) {
                    if (session != null) session.abandon();
                    jsState("error", String.valueOf(e.getMessage()));
                } finally {
                    downloading = false;
                }
            }).start();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_OPEN) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (uri != null) {
                    result = new Uri[]{uri};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
        } else if (requestCode == REQ_CAMERA) {
            if (fileCallback != null) {
                if (resultCode == RESULT_OK && cameraUri != null) {
                    fileCallback.onReceiveValue(new Uri[]{cameraUri});
                } else {
                    if (cameraUri != null) { try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { } }
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = null;
            }
            cameraUri = null;
        } else if (requestCode == REQ_SAVE) {
            if (uri != null && pendingBackup != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Копия сохранена", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Не удалось сохранить копию", Toast.LENGTH_SHORT).show();
                }
            }
            pendingBackup = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.androidBack ? window.androidBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }
}
