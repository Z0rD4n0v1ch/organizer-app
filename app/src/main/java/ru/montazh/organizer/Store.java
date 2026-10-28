package ru.montazh.organizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.File;

/** Где лежат файлы органайзера: фото и документы объектов — в памяти телефона,
 *  скачанная документация справочника — в памяти телефона или на флешке (выбирается в настройках). */
final class Store {
    private Store() { }

    static final String PREFS = "store";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Папка приложения на флешке (своя папка, разрешения не нужны), или null, если флешки нет. */
    static File sdRoot(Context c) {
        try {
            File[] dirs = c.getExternalFilesDirs(null);
            for (int i = 1; dirs != null && i < dirs.length; i++) {
                File d = dirs[i];
                if (d != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(d))) return d;
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String loc(Context c) {
        String l = prefs(c).getString("catLoc", "phone");
        if ("sd".equals(l) && sdRoot(c) == null) return "phone";
        return l;
    }

    static File catDir(Context c, String loc) {
        File root = "sd".equals(loc) ? sdRoot(c) : c.getFilesDir();
        if (root == null) root = c.getFilesDir();
        File d = new File(root, "catdocs");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    static File catDir(Context c) { return catDir(c, loc(c)); }

    static String clean(String id) { return id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", ""); }

    /** Файл документа по id. k_* — документация справочника (любое расширение), c_* — временные, остальные — PDF объектов. */
    static File docFile(Context c, String id) {
        String s = clean(id);
        if (s.startsWith("k_")) {
            File dir = catDir(c);
            File[] fs = dir.listFiles((d, n) -> n.startsWith(s + ".") && !n.endsWith(".part"));
            if (fs != null && fs.length > 0) return fs[0];
            return new File(dir, s + ".pdf");
        }
        if (s.startsWith("z_")) {   // файл объекта для «Поделиться» / принятый архив объекта
            File dir = new File(c.getCacheDir(), "share");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            return new File(dir, s + ".zip");
        }
        if (s.startsWith("r_")) {
            File dir = new File(c.getCacheDir(), "reports");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            return new File(dir, s + ".txt");
        }
        File dir = new File(s.startsWith("c_") ? c.getCacheDir() : c.getFilesDir(), "docs");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, s + ".pdf");
    }

    static String ext(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase();
    }

    static String mime(String ext) {
        switch (ext) {
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "rar": return "application/vnd.rar";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    /** Определяет тип скачанного файла по первым байтам, иначе по имени из заголовка сервера. */
    static String sniff(File f, String nameHint) {
        byte[] b = new byte[8];
        int n = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { n = in.read(b); } catch (Exception ignored) { }
        if (n >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "pdf";
        if (n >= 4 && b[0] == 'P' && b[1] == 'K') {
            String h = nameHint == null ? "" : nameHint.toLowerCase();
            if (h.endsWith(".docx")) return "docx";
            if (h.endsWith(".xlsx")) return "xlsx";
            return "zip";
        }
        if (n >= 4 && b[0] == 'R' && b[1] == 'a' && b[2] == 'r' && b[3] == '!') return "rar";
        if (n >= 4 && (b[0] & 0xff) == 0xD0 && (b[1] & 0xff) == 0xCF) {
            String h = nameHint == null ? "" : nameHint.toLowerCase();
            return h.endsWith(".xls") ? "xls" : "doc";
        }
        if (n >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) return "jpg";
        if (n >= 4 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (n >= 1 && (b[0] == '<' || b[0] == ' ' || b[0] == '\n' || b[0] == '\r')) return "html";
        if (nameHint != null) {
            int i = nameHint.lastIndexOf('.');
            if (i > 0 && nameHint.length() - i <= 5) return nameHint.substring(i + 1).toLowerCase().replaceAll("[^a-z0-9]", "");
        }
        return "bin";
    }

    static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) s += size(x);
        return s;
    }

    static int count(File d) {
        File[] fs = d.listFiles((x, n) -> !n.endsWith(".part"));
        return fs == null ? 0 : fs.length;
    }

    static JSONObject info(Context c) {
        JSONObject o = new JSONObject();
        try {
            StatFs st = new StatFs(c.getFilesDir().getPath());
            o.put("phoneFree", st.getAvailableBytes());
            o.put("phoneTotal", st.getTotalBytes());
            File sd = sdRoot(c);
            o.put("sd", sd != null);
            if (sd != null) {
                StatFs s2 = new StatFs(sd.getPath());
                o.put("sdFree", s2.getAvailableBytes());
                o.put("sdTotal", s2.getTotalBytes());
            }
            o.put("loc", loc(c));
            File files = c.getFilesDir();
            long photos = size(new File(files, "photos")) + size(new File(files, "videos")), objdocs = size(new File(files, "docs"));
            long catPhone = size(new File(files, "catdocs")), catSd = sd == null ? 0 : size(new File(sd, "catdocs"));
            File dataDir = files.getParentFile();
            long all = size(dataDir) + size(c.getExternalFilesDir(null)) + (sd == null ? 0 : size(sd)) + size(c.getExternalCacheDir());
            o.put("photos", photos);
            o.put("objdocs", objdocs);
            o.put("catdocs", catPhone + catSd);
            o.put("catPhone", catPhone);
            o.put("catSd", catSd);
            o.put("catCount", count(catDir(c)));
            o.put("cache", size(c.getCacheDir()) + size(c.getExternalCacheDir()));
            o.put("total", all);
            o.put("other", Math.max(0, all - photos - objdocs - catPhone - catSd));
        } catch (Exception ignored) { }
        return o;
    }

    /** Переносит скачанную документацию между телефоном и флешкой. */
    static String move(Context c, String to) {
        String from = loc(c);
        if (from.equals(to)) return "";
        if ("sd".equals(to) && sdRoot(c) == null) return "флешка не найдена";
        File a = catDir(c, from), b = catDir(c, to);
        File[] fs = a.listFiles();
        if (fs != null) for (File f : fs) {
            File t = new File(b, f.getName());
            if (f.renameTo(t)) continue;
            try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream out = new java.io.FileOutputStream(t)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                t.delete();
                return "не хватает места или флешка недоступна";
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        prefs(c).edit().putString("catLoc", to).apply();
        return "";
    }
}
