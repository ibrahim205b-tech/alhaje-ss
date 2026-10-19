package iq.haji.cashier.print;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * أذونات البلوتوث وقت التشغيل (Android 12+). الشاشات تطلبها عند الحاجة فقط:
 * BLUETOOTH_SCAN للبحث عن أجهزة جديدة، وBLUETOOTH_CONNECT للاتصال وقراءة الأجهزة المقترنة.
 * لا حاجة لإذن الموقع لأن المانيفست يعلن neverForLocation.
 */
public final class BtPermissions {
    private BtPermissions() {}

    public static boolean hasScan(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasConnect(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasAll(Context c) {
        return hasScan(c) && hasConnect(c);
    }

    /** الأذونات الناقصة فقط، جاهزة لـ requestPermissions. */
    public static String[] missing(Context c) {
        List<String> m = new ArrayList<>();
        if (!hasScan(c)) m.add(Manifest.permission.BLUETOOTH_SCAN);
        if (!hasConnect(c)) m.add(Manifest.permission.BLUETOOTH_CONNECT);
        return m.toArray(new String[0]);
    }

    public static final String EXPLANATION =
            "يحتاج التطبيق إذن «الأجهزة القريبة» للبحث عن الطابعة عبر البلوتوث والاتصال بها وطباعة الفواتير. "
                    + "لا يُستخدم هذا الإذن لتحديد موقعك ولا لأي غرض آخر.";
}
