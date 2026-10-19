package iq.haji.cashier.util;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import iq.haji.cashier.R;

/** أدوات واجهة مشتركة بين الشاشات. */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, msg.length() > 40 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    /** يعرض النافذة ويفرض اتجاه RTL حتى لو كانت لغة الجهاز إنجليزية. */
    public static void showRtl(AlertDialog d) {
        d.show();
        Window w = d.getWindow();
        if (w != null) w.getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }

    public static EditText editText(Context c, String hint, int inputType, String text) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setText(text == null ? "" : text);
        et.setTextSize(16);
        et.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        et.setHintTextColor(ContextCompat.getColor(c, R.color.text_hint));
        et.setBackgroundResource(R.drawable.bg_input);
        et.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        et.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        int p = dp(c, 14);
        et.setPadding(p, p, p, p);
        return et;
    }

    /** يلف عنصراً بهوامش مناسبة لوضعه داخل AlertDialog. */
    public static FrameLayout wrap(Context c, View child) {
        FrameLayout f = new FrameLayout(c);
        int p = dp(c, 20);
        f.setPadding(p, dp(c, 8), p, 0);
        f.addView(child, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return f;
    }
}
