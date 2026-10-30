package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import iq.haji.cashier.util.AppException;

/**
 * نسخ احتياطي واستعادة لملف قاعدة البيانات كاملاً (طلبات، أصناف، ورديات، إعدادات).
 * الاستعادة تتحقق من سلامة الملف أولاً، وتحفظ نسخة من البيانات الحالية قبل الاستبدال، وتتراجع عند الفشل.
 * بعد الاستعادة يجب إعادة تشغيل الواجهة (تفعل ذلك الشاشات في الجزء 5).
 */
public final class BackupManager {
    private static final int KEEP_AUTO_BACKUPS = 15;
    private static final String[] REQUIRED_TABLES = {
            "categories", "products", "shifts", "orders", "order_items", "order_status_log", "settings"};

    private BackupManager() {}

    public static File backupDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "backups");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** ينشئ نسخة متسقة من القاعدة (VACUUM INTO) داخل files/backups. */
    public static synchronized File createBackup(Context ctx, String prefix) {
        SQLiteDatabase db = DatabaseHelper.get(ctx).getWritableDatabase();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(backupDir(ctx), prefix + stamp + ".db");
        if (out.exists()) //noinspection ResultOfMethodCallIgnored
            out.delete();
        try {
            db.execSQL("VACUUM INTO '" + out.getAbsolutePath().replace("'", "''") + "'");
        } catch (SQLiteException e) {
            // بديل: دمج سجل WAL ثم نسخ الملف مباشرة
            try {
                try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
                    c.moveToFirst();
                }
                copyFile(ctx.getDatabasePath(DatabaseHelper.DB_NAME), out);
            } catch (IOException | SQLiteException e2) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new AppException("تعذر إنشاء النسخة الاحتياطية: " + e2.getMessage());
            }
        }
        if ("backup_".equals(prefix)) pruneOld(ctx);
        return out;
    }

    public static List<File> listBackups(Context ctx) {
        File[] files = backupDir(ctx).listFiles((d, name) -> name.endsWith(".db"));
        List<File> out = new ArrayList<>();
        if (files != null) out.addAll(Arrays.asList(files));
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private static void pruneOld(Context ctx) {
        List<File> autos = new ArrayList<>();
        for (File f : listBackups(ctx)) if (f.getName().startsWith("backup_")) autos.add(f);
        for (int i = KEEP_AUTO_BACKUPS; i < autos.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            autos.get(i).delete();
        }
    }

    /** مجلد النسخ الظاهر للمستخدم: Download/AlHaji. يبقى حتى لو حُذف التطبيق. */
    public static final String PUBLIC_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/AlHaji/";
    private static final int KEEP_PUBLIC_BACKUPS = 30;

    /**
     * ينسخ القاعدة كاملة إلى مجلد التنزيلات (Download/AlHaji) دون أي إذن.
     * هذه النسخة تبقى على الجهاز حتى لو حُذف التطبيق، وتُستعاد من الإعدادات ← «استعادة من ملف».
     */
    public static synchronized void exportToDownloads(Context ctx) {
        File backup = createBackup(ctx, "public_");
        try {
            String name = "alhaji-backup-"
                    + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date()) + ".db";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, PUBLIC_FOLDER);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = ctx.getContentResolver().insert(collection, cv);
            if (item == null) throw new AppException("تعذر إنشاء ملف النسخة في التنزيلات");
            try (InputStream in = new FileInputStream(backup);
                 OutputStream out = ctx.getContentResolver().openOutputStream(item)) {
                if (out == null) throw new IOException("no stream");
                copyStream(in, out);
            } catch (IOException e) {
                ctx.getContentResolver().delete(item, null, null);
                throw new AppException("تعذر كتابة النسخة في التنزيلات: " + e.getMessage());
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(item, done, null, null);
            prunePublic(ctx, collection);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
        }
    }

    /** يُبقي آخر النسخ فقط في مجلد التنزيلات (التطبيق يرى ملفاته هو فقط). */
    private static void prunePublic(Context ctx, Uri collection) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(collection,
                new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE 'alhaji-backup-%'",
                new String[]{PUBLIC_FOLDER}, MediaStore.Downloads.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) ids.add(c.getLong(0));
        } catch (RuntimeException ignored) {
            return;
        }
        for (int i = KEEP_PUBLIC_BACKUPS; i < ids.size(); i++) {
            try {
                ctx.getContentResolver().delete(
                        android.content.ContentUris.withAppendedId(collection, ids.get(i)), null, null);
            } catch (RuntimeException ignored) {
                // الحذف ليس ضرورياً
            }
        }
    }

    /** ينشئ نسخة جديدة ويكتبها في الموقع الذي اختاره المستخدم (ACTION_CREATE_DOCUMENT). */
    public static void exportBackupToUri(Context ctx, Uri target) {
        File backup = createBackup(ctx, "backup_");
        try (InputStream in = new FileInputStream(backup);
             OutputStream out = ctx.getContentResolver().openOutputStream(target)) {
            if (out == null) throw new AppException("تعذر فتح الموقع المحدد للحفظ");
            copyStream(in, out);
        } catch (IOException e) {
            throw new AppException("فشل تصدير النسخة الاحتياطية: " + e.getMessage());
        }
    }

    /** استعادة من ملف اختاره المستخدم (ACTION_OPEN_DOCUMENT). */
    public static void restoreFromUri(Context ctx, Uri source) {
        File tmp = tmpFile(ctx);
        try (InputStream in = ctx.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new AppException("تعذر قراءة الملف المحدد");
            copyStream(in, out);
        } catch (IOException e) {
            cleanupTmp(tmp);
            throw new AppException("تعذر قراءة الملف: " + e.getMessage());
        }
        installFromTemp(ctx, tmp);
    }

    /** استعادة من نسخة محفوظة داخل التطبيق. */
    public static void restoreFromFile(Context ctx, File backup) {
        File tmp = tmpFile(ctx);
        try {
            copyFile(backup, tmp);
        } catch (IOException e) {
            cleanupTmp(tmp);
            throw new AppException("تعذر قراءة النسخة: " + e.getMessage());
        }
        installFromTemp(ctx, tmp);
    }

    private static File tmpFile(Context ctx) {
        File tmp = new File(ctx.getCacheDir(), "restore_tmp.db");
        cleanupTmp(tmp);
        return tmp;
    }

    private static void cleanupTmp(File tmp) {
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(tmp.getPath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(tmp.getPath() + "-shm").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(tmp.getPath() + "-journal").delete();
    }

    private static void validate(File f) {
        SQLiteDatabase t = null;
        try {
            t = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            int v = t.getVersion();
            if (v < 1 || v > DatabaseHelper.DB_VERSION) {
                throw new AppException("إصدار النسخة غير متوافق مع هذا التطبيق");
            }
            try (Cursor c = t.rawQuery("PRAGMA integrity_check", null)) {
                if (!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0))) {
                    throw new AppException("ملف النسخة تالف");
                }
            }
            for (String table : REQUIRED_TABLES) {
                try (Cursor c = t.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        new String[]{table})) {
                    if (!c.moveToFirst()) throw new AppException("الملف ليس نسخة احتياطية لهذا التطبيق");
                }
            }
        } catch (SQLiteException e) {
            throw new AppException("الملف ليس نسخة احتياطية صالحة");
        } finally {
            if (t != null) t.close();
        }
    }

    private static void installFromTemp(Context ctx, File tmp) {
        try {
            validate(tmp);
            File safety = createBackup(ctx, "pre_restore_");
            File dbFile = ctx.getDatabasePath(DatabaseHelper.DB_NAME);

            DatabaseHelper.reset();
            try {
                deleteDbFiles(dbFile);
                copyFile(tmp, dbFile);
            } catch (IOException e) {
                try {
                    deleteDbFiles(dbFile);
                    copyFile(safety, dbFile);
                } catch (IOException ignored) {
                    // آخر محاولة؛ النسخة الأمان تبقى داخل files/backups
                }
                DatabaseHelper.get(ctx).getWritableDatabase();
                throw new AppException("فشلت الاستعادة وأُعيدت بياناتك السابقة");
            }
            DatabaseHelper.get(ctx).getWritableDatabase();
            AuditLog.log(ctx, "RESTORE", "تمت استعادة نسخة احتياطية");
        } finally {
            cleanupTmp(tmp);
        }
    }

    private static void deleteDbFiles(File dbFile) {
        //noinspection ResultOfMethodCallIgnored
        dbFile.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getPath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getPath() + "-shm").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getPath() + "-journal").delete();
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
