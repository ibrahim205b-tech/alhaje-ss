package iq.haji.cashier;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.io.File;

import iq.haji.cashier.data.BackupManager;
import iq.haji.cashier.data.DatabaseHelper;
import iq.haji.cashier.util.Ui;

/** شاشة البدء: تفتح قاعدة البيانات في الخلفية، وتنشئ نسخة احتياطية تلقائية مرة كل 24 ساعة، ثم تفتح الكاشير. */
public class SplashActivity extends BaseActivity {
    private static final long MIN_SHOW_MS = 1200;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, R.color.brand_red)));
        setContentView(R.layout.activity_splash);
        boot();
    }

    private void boot() {
        final long t0 = SystemClock.uptimeMillis();
        final Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                DatabaseHelper.get(app).getWritableDatabase();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (error == null) {
                try {
                    autoBackupIfDue(app);
                } catch (Exception ignored) {
                    // فشل النسخ التلقائي لا يمنع تشغيل الكاشير
                }
            }
            final String err = error;
            long wait = Math.max(0, MIN_SHOW_MS - (SystemClock.uptimeMillis() - t0));
            main.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (err == null) goMain();
                else showError(err);
            }, wait);
        }, "boot").start();
    }

    private static void autoBackupIfDue(Context app) {
        long now = System.currentTimeMillis();
        for (File f : BackupManager.listBackups(app)) {
            if (f.getName().startsWith("backup_") && now - f.lastModified() < DAY_MS) return;
        }
        BackupManager.createBackup(app, "backup_");
        try {
            BackupManager.exportToDownloads(app);
        } catch (RuntimeException ignored) {
            // النسخة الداخلية تمت؛ فشل نسخة التنزيلات لا يمنع التشغيل
        }
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void showError(String err) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("تعذر فتح قاعدة البيانات")
                .setMessage(err)
                .setCancelable(false)
                .setPositiveButton(R.string.retry, (x, w) -> boot())
                .setNegativeButton("خروج", (x, w) -> finish())
                .create();
        Ui.showRtl(d);
    }
}
