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
import android.os.StatFs;
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
    private static final int REQ_GEO = 5;
    private String geoPending;

    private WebView web;
    private String pendingBackup;
    private ValueCallback<Uri[]> fileCallback;
    private volatile boolean downloading = false;
    private volatile boolean prefetching = false;
    private java.util.concurrent.ExecutorService batch;
    private volatile boolean batchStop = false;
    private volatile String lastInstallUrl;
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
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED && lastInstallUrl != null) //noinspection ResultOfMethodCallIgnored
                    updFile(lastInstallUrl).delete();
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

    private File updFile(String url) {
        File dir = new File(getCacheDir(), "update");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, Integer.toHexString(url.hashCode()) + ".apk");
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
                    boolean gh = url.contains("github") || url.contains("nominatim");
                    c.setRequestProperty("User-Agent", gh ? "organizer-app" : "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36 organizer-app");
                    c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
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

        /* ---- геолокация ---- */
        @JavascriptInterface
        public void getLocation(final String id) {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                        && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    geoPending = id;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
                } else locate(id);
            });
        }

        /* ---- PDF-документы объектов: файлы во внутренней памяти, c_* — временные (скачанные из справочника) ---- */
        private File docFile(String id) { return Store.docFile(MainActivity.this, id); }

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
                File f = docFile(id);
                Uri u = PhotoProvider.docUri(id, name, Store.ext(f));
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(Store.mime(Store.ext(f)));
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

        /** Скачивает файл по ссылке во внутреннюю папку документа id. Возвращает (код, ошибка, тип, размер). */
        private Object[] download(String url, String id) {
            int code;
            String err = "", ext = "";
            long size = 0;
            String sid = Store.clean(id);
            File dir = sid.startsWith("k_") ? Store.catDir(MainActivity.this) : docFile(sid).getParentFile();
            File tmp = new File(dir, sid + ".part");
            try {
                String u = url, hint = "";
                HttpURLConnection c = null;
                for (int hop = 0; hop < 8; hop++) {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
                    c.setRequestProperty("Accept", "application/pdf,application/octet-stream,*/*");
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(20000);
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
                if (code != 200) err = "HTTP " + code + (c.getResponseMessage() != null ? " " + c.getResponseMessage() : "") + (u.equals(url) ? "" : " (после перенаправления на " + u + ")");
                if (code == 200) {
                    String cd = c.getHeaderField("Content-Disposition");
                    if (cd != null) {
                        Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
                        if (m.find()) hint = java.net.URLDecoder.decode(m.group(1), "UTF-8");
                    }
                    if (hint.isEmpty()) hint = Uri.parse(u).getLastPathSegment();
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); size += n; }
                    }
                    ext = Store.sniff(tmp, hint);
                    if ("html".equals(ext)) {
                        code = -2;
                        String head = "";
                        try (java.io.FileInputStream in = new java.io.FileInputStream(tmp)) { byte[] hb = new byte[600]; int hn = in.read(hb); if (hn > 0) head = new String(hb, 0, hn, StandardCharsets.UTF_8); } catch (Exception ignored) { }
                        Matcher tm = Pattern.compile("<title>([^<]{1,120})", Pattern.CASE_INSENSITIVE).matcher(head);
                        err = "по ссылке не файл, а страница сайта" + (tm.find() ? ": «" + tm.group(1).trim() + "»" : "") + (u.equals(url) ? "" : " (" + u + ")");
                    }
                    else {
                        File[] old = dir.listFiles((d, nm) -> nm.startsWith(sid + ".") && !nm.endsWith(".part"));
                        if (old != null) for (File x : old) //noinspection ResultOfMethodCallIgnored
                            x.delete();
                        File dst = sid.startsWith("k_") ? new File(dir, sid + "." + ext) : docFile(sid);
                        if (!tmp.renameTo(dst)) { code = -1; err = "не удалось сохранить файл"; }
                    }
                }
            } catch (Exception e) {
                code = -1;
                err = String.valueOf(e.getMessage());
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return new Object[]{code, err, ext, size};
        }

        /** Скачивает файл по ссылке (документация из справочника), результат — window.onDocFetch(req, код, ошибка, тип, размер). */
        @JavascriptInterface
        public void docFetch(final String req, final String url, final String id) {
            new Thread(() -> {
                Object[] r = download(url, id);
                callJs("window.onDocFetch && window.onDocFetch(" + JSONObject.quote(req) + "," + r[0] + "," + JSONObject.quote((String) r[1]) + "," + JSONObject.quote((String) r[2]) + "," + r[3] + ")");
            }).start();
        }

        /** Есть ли скачанный документ: {"ok":true,"size":…,"ext":"pdf"}. */
        @JavascriptInterface
        public String docInfo(String id) {
            File f = docFile(id);
            try {
                JSONObject o = new JSONObject();
                o.put("ok", f.exists() && f.length() > 0);
                o.put("size", f.exists() ? f.length() : 0);
                o.put("ext", Store.ext(f));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Открывает не-PDF файл (архив, Word…) в другом приложении телефона. */
        @JavascriptInterface
        public void docOpenExt(final String id, final String name) {
            runOnUiThread(() -> {
                File f = docFile(id);
                Uri u = PhotoProvider.docUri(id, name, Store.ext(f));
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(u, Store.mime(Store.ext(f)));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Открыть")); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_SHORT).show(); }
            });
        }

        /** Копия документа в «Загрузки/Органайзер/Документация». Возвращает, куда сохранено, или "" при ошибке. */
        @JavascriptInterface
        public String docSaveDl(String id, String name) {
            File f = docFile(id);
            if (!f.exists()) return "";
            String ext = Store.ext(f);
            String nm = (name == null ? "document" : name).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (nm.isEmpty()) nm = "document";
            if (!nm.toLowerCase().endsWith("." + ext)) nm += "." + ext;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, nm);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, Store.mime(ext));
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Органайзер/Документация");
                    Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return "";
                    try (InputStream in = new java.io.FileInputStream(f); OutputStream out = cr.openOutputStream(u)) {
                        if (out == null) return "";
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    return "Загрузки/Органайзер/Документация/" + nm;
                } else {
                    File dir = new File(getExternalFilesDir(null), "Документация");
                    if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    try (InputStream in = new java.io.FileInputStream(f); OutputStream out = new FileOutputStream(new File(dir, nm))) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    return "Android/data/" + getPackageName() + "/files/Документация/" + nm;
                }
            } catch (Exception e) { return ""; }
        }

        /* ---- скачивание всей документации справочника: очередь в фоне, 3 файла одновременно ---- */
        @JavascriptInterface
        public void catBatch(final String json) {
            if (batch != null && !batch.isShutdown()) return;
            final org.json.JSONArray list;
            try { list = new org.json.JSONArray(json); } catch (Exception e) { return; }
            batchStop = false;
            batch = java.util.concurrent.Executors.newFixedThreadPool(3);
            final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(), ok = new java.util.concurrent.atomic.AtomicInteger(), fail = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
            final int total = list.length();
            for (int i = 0; i < total; i++) {
                final JSONObject it = list.optJSONObject(i);
                if (it == null) continue;
                batch.execute(() -> {
                    String id = it.optString("id"), url = it.optString("url");
                    String ext = "", err = "";
                    long sz = 0;
                    int code = 0;
                    if (!batchStop) {
                        File have = docFile(id);
                        if (have.exists() && have.length() > 0) { code = 200; ext = Store.ext(have); sz = have.length(); }
                        else {
                            StatFs st = new StatFs(Store.catDir(MainActivity.this).getPath());
                            if (st.getAvailableBytes() < 50L * 1024 * 1024) { batchStop = true; code = -3; }
                            else { Object[] r = download(url, id); code = (int) r[0]; err = (String) r[1]; ext = (String) r[2]; sz = (long) r[3]; }
                        }
                    }
                    if (code == 200) { ok.incrementAndGet(); bytes.addAndGet(sz); } else if (!batchStop || code == -3) fail.incrementAndGet();
                    int d = done.incrementAndGet();
                    try {
                        JSONObject o = new JSONObject();
                        o.put("done", d); o.put("total", total); o.put("ok", ok.get()); o.put("fail", fail.get()); o.put("bytes", bytes.get());
                        o.put("id", id); o.put("url", url); o.put("err", err); o.put("code", code); o.put("ext", ext); o.put("size", sz); o.put("stopped", batchStop); o.put("nospace", code == -3);
                        callJs("window.onCatBatch && window.onCatBatch(" + JSONObject.quote(o.toString()) + ")");
                    } catch (Exception ignored) { }
                    if (d == total) batch.shutdown();
                });
            }
        }

        /** Текстовый файл (отчёт об ошибках) для отправки или сохранения: id вида r_… */
        @JavascriptInterface
        public boolean writeText(String id, String text) {
            try (OutputStream out = new FileOutputStream(docFile(id))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String deviceInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                o.put("build", currentVersion());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void catBatchCancel() { batchStop = true; if (batch != null) batch.shutdownNow(); }

        @JavascriptInterface
        public boolean catBatchRunning() { return batch != null && !batch.isTerminated(); }

        /* ---- память: сколько занято, сколько свободно, где хранить документацию ---- */
        @JavascriptInterface
        public String storageInfo() { return Store.info(MainActivity.this).toString(); }

        @JavascriptInterface
        public void storageMove(final String to) {
            new Thread(() -> {
                String err = Store.move(MainActivity.this, to);
                callJs("window.onStorageMoved && window.onStorageMoved(" + JSONObject.quote(err) + ")");
            }).start();
        }

        @JavascriptInterface
        public void catClear() {
            for (String l : new String[]{"phone", "sd"}) {
                File[] fs = Store.catDir(MainActivity.this, l).listFiles();
                if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
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

        /** Фото или PDF (из документов) пачкой до 10 (sendMediaGroup), одиночный — sendPhoto/sendDocument.
         *  itemsJson: [{id, doc:true|false, name, cap}] */
        @JavascriptInterface
        public void tgFilesVia(final String id, final String base, final String token, final String chat, final String itemsJson) {
            new Thread(() -> {
                int code = -1;
                String body = "";
                try {
                    JSONArray items = new JSONArray(itemsJson), media = new JSONArray();
                    java.util.List<File> files = new java.util.ArrayList<>();
                    java.util.List<String> names = new java.util.ArrayList<>(), types = new java.util.ArrayList<>(), caps = new java.util.ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        boolean doc = it.optBoolean("doc");
                        File f = doc ? docFile(it.optString("id")) : photoFile(it.optString("id"));
                        if (f == null || !f.exists()) continue;
                        String cap = it.optString("cap", "");
                        if (cap.length() > 1024) cap = cap.substring(0, 1024);
                        String nm = it.optString("name", "");
                        if (nm.isEmpty()) nm = "chek_" + i + (doc ? ".pdf" : ".jpg");
                        JSONObject m = new JSONObject();
                        m.put("type", doc ? "document" : "photo");
                        m.put("media", "attach://f" + files.size());
                        if (!cap.isEmpty()) m.put("caption", cap);
                        media.put(m);
                        files.add(f); names.add(nm.replace("\"", "")); types.add(doc ? "document" : "photo"); caps.add(cap);
                    }
                    if (files.isEmpty()) {
                        code = 200;
                        body = "{\"ok\":true,\"result\":[]}";
                    } else {
                        boolean one = files.size() == 1;
                        String method = one ? ("document".equals(types.get(0)) ? "/sendDocument" : "/sendPhoto") : "/sendMediaGroup";
                        String b0 = (base == null || base.isEmpty()) ? "https://api.telegram.org" : base.replaceAll("/+$", "");
                        HttpURLConnection c = (HttpURLConnection) new URL(b0 + "/bot" + token + method).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(180000);
                        c.setDoOutput(true);
                        String b = "----org" + System.currentTimeMillis();
                        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
                        try (OutputStream out = c.getOutputStream()) {
                            writePart(out, b, "chat_id", chat);
                            if (one) writePart(out, b, "caption", caps.get(0));
                            else writePart(out, b, "media", media.toString());
                            for (int i = 0; i < files.size(); i++) {
                                String field = one ? types.get(i) : "f" + i;
                                String mime = "document".equals(types.get(i)) ? "application/pdf" : "image/jpeg";
                                out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + names.get(i) + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                                try (InputStream in = new java.io.FileInputStream(files.get(i))) {
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
                    out.put("notes", rel.optString("body", ""));
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

        /** Заранее и тихо скачивает APK новой версии, чтобы «Обновить» сразу открывал установку. */
        @JavascriptInterface
        public void prefetchUpdate(final String url) {
            if (downloading || prefetching) return;
            final File f = updFile(url);
            if (f.exists() && f.length() > 0) { callJs("window.onUpdateFetched && window.onUpdateFetched(true)"); return; }
            prefetching = true;
            new Thread(() -> {
                boolean ok = false;
                File dir = f.getParentFile(), tmp = new File(f.getPath() + ".part");
                try {
                    File[] old = dir.listFiles();
                    if (old != null) for (File x : old) if (!x.getName().equals(f.getName())) //noinspection ResultOfMethodCallIgnored
                        x.delete();
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("User-Agent", "organizer-app");
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    long total = c.getContentLengthLong(), done = 0;
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; }
                    }
                    if (total > 0 && done != total) throw new Exception("обрыв загрузки");
                    ok = tmp.renameTo(f);
                } catch (Exception e) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                } finally {
                    prefetching = false;
                }
                callJs("window.onUpdateFetched && window.onUpdateFetched(" + ok + ")");
            }).start();
        }

        /** Скачивает APK (или берёт заранее скачанный) и передаёт его системному установщику. */
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
                    lastInstallUrl = url;
                    File cached = updFile(url);
                    InputStream src;
                    long total;
                    if (cached.exists() && cached.length() > 0) {
                        src = new java.io.FileInputStream(cached);
                        total = cached.length();
                    } else {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestProperty("User-Agent", "organizer-app");
                        c.setInstanceFollowRedirects(true);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                        total = c.getContentLengthLong();
                        src = c.getInputStream();
                    }

                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(getPackageName());
                    int id = pi.createSession(params);
                    session = pi.openSession(id);

                    try (InputStream in = src;
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
                    //noinspection ResultOfMethodCallIgnored
                    updFile(url).delete();
                    jsState("error", String.valueOf(e.getMessage()));
                } finally {
                    downloading = false;
                }
            }).start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_GEO && geoPending != null) {
            String id = geoPending;
            geoPending = null;
            boolean ok = false;
            for (int g : grantResults) if (g == PackageManager.PERMISSION_GRANTED) ok = true;
            if (ok) locate(id);
            else geoResult(id, 0, 0, 0, "Нет разрешения на геолокацию. Его можно включить в настройках приложения.");
        }
    }

    /* ---------- текущее местоположение (координаты объекта) ---------- */
    private void geoResult(String id, double lat, double lon, float acc, String err) {
        callJs("window.onGeo && window.onGeo(" + JSONObject.quote(id) + "," + lat + "," + lon + "," + acc + "," + JSONObject.quote(err) + ")");
    }

    @SuppressWarnings("MissingPermission")
    private void locate(final String id) {
        final android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        final java.util.List<String> provs = new java.util.ArrayList<>();
        try {
            if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) provs.add(android.location.LocationManager.GPS_PROVIDER);
            if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) provs.add(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) { }
        if (provs.isEmpty()) { geoResult(id, 0, 0, 0, "Включите геолокацию (GPS) в настройках телефона"); return; }
        final boolean[] done = {false};
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final android.location.LocationListener l = new android.location.LocationListener() {
            @Override public void onLocationChanged(android.location.Location loc) {
                if (done[0]) return;
                if (loc.hasAccuracy() && loc.getAccuracy() > 100 && android.location.LocationManager.NETWORK_PROVIDER.equals(loc.getProvider())) return; // ждём точнее
                done[0] = true;
                try { lm.removeUpdates(this); } catch (Exception ignored) { }
                geoResult(id, loc.getLatitude(), loc.getLongitude(), loc.hasAccuracy() ? loc.getAccuracy() : 0, "");
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try {
            for (String p : provs) lm.requestLocationUpdates(p, 0, 0, l, getMainLooper());
        } catch (SecurityException e) {
            geoResult(id, 0, 0, 0, "Нет разрешения на геолокацию");
            return;
        }
        h.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            try { lm.removeUpdates(l); } catch (Exception ignored) { }
            android.location.Location best = null;
            for (String p : provs) {
                try {
                    android.location.Location x = lm.getLastKnownLocation(p);
                    if (x != null && (best == null || x.getTime() > best.getTime())) best = x;
                } catch (Exception ignored) { }
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60 * 1000)
                geoResult(id, best.getLatitude(), best.getLongitude(), best.hasAccuracy() ? best.getAccuracy() : 0, "");
            else geoResult(id, 0, 0, 0, "Не удалось определить место. Выйдите на открытое место и попробуйте ещё раз.");
        }, 30000);
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
