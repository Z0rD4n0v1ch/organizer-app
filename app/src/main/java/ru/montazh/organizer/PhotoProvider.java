package ru.montazh.organizer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Отдаёт фото из внутренней памяти другим приложениям (Telegram и т.п.) только для чтения. */
public class PhotoProvider extends ContentProvider {
    public static final String AUTH = "ru.montazh.organizer.photos";

    public static Uri uriFor(String id) {
        return Uri.parse("content://" + AUTH + "/" + id.replaceAll("[^A-Za-z0-9_-]", "") + ".jpg");
    }

    /** Документ: content://…/doc/<id>/<имя файла> — имя нужно, чтобы получатель видел понятное название. */
    public static Uri docUri(String id, String name, String ext) {
        if (ext == null || ext.isEmpty()) ext = "pdf";
        String n = (name == null ? "" : name).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) n = "document";
        if (!n.toLowerCase().endsWith("." + ext)) n += "." + ext;
        return new Uri.Builder().scheme("content").authority(AUTH).appendPath("doc")
                .appendPath(Store.clean(id)).appendPath(n).build();
    }

    public static Uri docUri(String id, String name) { return docUri(id, name, "pdf"); }

    /** Видео объекта: content://…/video/<id>/<имя файла>. */
    public static Uri videoUri(String id, String name) {
        return new Uri.Builder().scheme("content").authority(AUTH).appendPath("video")
                .appendPath(Store.clean(id)).appendPath(name).build();
    }

    private static boolean isVideo(Uri uri) {
        java.util.List<String> s = uri.getPathSegments();
        return s.size() == 3 && "video".equals(s.get(0));
    }

    static String videoMime(String ext) {
        switch (ext) {
            case "3gp": return "video/3gpp";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            default: return "video/mp4";
        }
    }

    private static boolean isDoc(Uri uri) {
        java.util.List<String> s = uri.getPathSegments();
        return s.size() == 3 && "doc".equals(s.get(0));
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (isVideo(uri)) {
            String id = uri.getPathSegments().get(1);
            if (!id.matches("[A-Za-z0-9_-]+")) throw new FileNotFoundException();
            File f = MainActivity.videoFile(getContext(), id);
            if (!f.exists()) throw new FileNotFoundException();
            return f;
        }
        if (isDoc(uri)) {
            String id = uri.getPathSegments().get(1);
            if (!id.matches("[A-Za-z0-9_-]+")) throw new FileNotFoundException();
            File f = Store.docFile(getContext(), id);
            if (!f.exists()) throw new FileNotFoundException();
            return f;
        }
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9_-]+\\.jpg")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getFilesDir(), "photos"), name);
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { if (isVideo(uri)) { try { return videoMime(Store.ext(fileFor(uri))); } catch (FileNotFoundException e) { return "video/mp4"; } } if (!isDoc(uri)) return "image/jpeg"; try { return Store.mime(Store.ext(fileFor(uri))); } catch (FileNotFoundException e) { return "application/octet-stream"; } }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = isDoc(uri) || isVideo(uri) ? uri.getLastPathSegment() : "probeg.jpg";
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
