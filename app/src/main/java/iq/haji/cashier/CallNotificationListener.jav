package iq.haji.cashier;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneNumberUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CallNotificationListener extends NotificationListenerService {

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)\\+?\\d[\\d\\s().-]{5,}\\d(?!\\d)"
    );

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        String category = notification.category;
        String packageName = sbn.getPackageName();
        String p = packageName == null ? "" : packageName.toLowerCase();

        if (!Notification.CATEGORY_CALL.equals(category)
                && !p.contains("phone")
                && !p.contains("dialer")) {
            return;
        }

        CharSequence[] values = {
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_TEXT),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
        };

        for (CharSequence value : values) {
            if (value == null) continue;

            Matcher matcher = PHONE_PATTERN.matcher(
                    westernDigits(value.toString())
            );

            while (matcher.find()) {
                String number = PhoneNumberUtils.normalizeNumber(
                        matcher.group()
                );

                if (number == null) continue;

                String digits = number.replace("+", "");
                if (digits.length() < 7 || digits.length() > 15) continue;

                CallNumberStore.save(this, number);
                return;
            }
        }
    }

    private static String westernDigits(String input) {
        StringBuilder result = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c >= '\u0660' && c <= '\u0669') {
                c = (char) ('0' + c - '\u0660');
            } else if (c >= '\u06F0' && c <= '\u06F9') {
                c = (char) ('0' + c - '\u06F0');
            }

            result.append(c);
        }

        return result.toString();
    }
}
