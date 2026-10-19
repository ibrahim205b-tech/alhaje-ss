package iq.haji.cashier.data;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;

/**
 * رمز مدير اختياري (4 إلى 8 أرقام). إذا لم يُعيَّن رمز فكل العمليات متاحة.
 * يُخزَّن مجزَّأً بملح عشوائي (SHA-256) ولا يُخزَّن الرمز نفسه.
 */
public class AdminGuard {
    private static final String KEY = "admin_pin_hash";
    private final AppSettings settings;

    public AdminGuard(Context ctx) {
        this.settings = new AppSettings(ctx);
    }

    public boolean isEnabled() {
        return !settings.get(KEY, "").isEmpty();
    }

    /** رمز فارغ يعطّل الحماية. */
    public void setPin(String pin) {
        String p = Fmt.normalizeDigits(pin);
        if (p.isEmpty()) {
            settings.set(KEY, "");
            return;
        }
        if (!p.matches("\\d{4,8}")) throw new AppException("الرمز يجب أن يكون من 4 إلى 8 أرقام");
        byte[] saltBytes = new byte[8];
        new SecureRandom().nextBytes(saltBytes);
        String salt = hex(saltBytes);
        settings.set(KEY, salt + ":" + hash(salt, p));
    }

    public boolean verify(String pin) {
        String stored = settings.get(KEY, "");
        if (stored.isEmpty()) return true;
        int i = stored.indexOf(':');
        if (i < 0) return false;
        String salt = stored.substring(0, i);
        String expected = stored.substring(i + 1);
        return expected.equals(hash(salt, Fmt.normalizeDigits(pin)));
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
