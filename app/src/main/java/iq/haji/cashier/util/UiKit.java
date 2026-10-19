package iq.haji.cashier.util;

import android.content.Context;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.function.IntConsumer;

import iq.haji.cashier.R;

/** مولّدات واجهة برمجية موحّدة الشكل لشاشات الإدارة. */
public final class UiKit {
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private UiKit() {}

    public static TextView button(Context c, String text, boolean primary) {
        TextView t = new TextView(c, null, 0, primary ? R.style.Haji_Button : R.style.Haji_Button_Secondary);
        t.setText(text);
        return t;
    }

    public static TextView chip(Context c, String text) {
        TextView t = new TextView(c, null, 0, R.style.Haji_Chip);
        t.setText(text);
        return t;
    }

    public static void setChip(TextView t, boolean selected) {
        t.setSelected(selected);
        t.setTextColor(ContextCompat.getColor(t.getContext(), selected ? R.color.white : R.color.text_primary));
    }

    public static void select(TextView[] chips, int idx) {
        for (int i = 0; i < chips.length; i++) if (chips[i] != null) setChip(chips[i], i == idx);
    }

    public static TextView text(Context c, String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(ContextCompat.getColor(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView title(Context c, String s) {
        return text(c, s, 19, R.color.text_primary, true);
    }

    public static TextView label(Context c, String s) {
        return text(c, s, 14, R.color.text_secondary, false);
    }

    public static TextView hint(Context c, String s) {
        return text(c, s, 13, R.color.text_hint, false);
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = vertical(c);
        l.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }

    public static LinearLayout.LayoutParams weight(Context c, float w, int startDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, MATCH, w);
        p.setMarginStart(Ui.dp(c, startDp));
        return p;
    }

    /** سطر «تسمية: قيمة». */
    public static LinearLayout kv(Context c, String k, String v, boolean bold) {
        LinearLayout r = row(c);
        r.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
        TextView a = text(c, k, 15, R.color.text_secondary, false);
        TextView b = text(c, v == null ? "" : v, bold ? 18 : 15, bold ? R.color.brand_red : R.color.text_primary, true);
        r.addView(a, new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        return r;
    }

    /** عمود قابل للتمرير يُضاف إلى صف أفقي ويعيد الحاوية الداخلية. */
    public static LinearLayout column(Context c, LinearLayout parent, float weight, boolean startMargin) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(false);
        LinearLayout inner = vertical(c);
        sv.addView(inner, new ScrollView.LayoutParams(MATCH, WRAP));
        parent.addView(sv, weight(c, weight, startMargin ? 12 : 0));
        return inner;
    }

    public static SwitchCompat toggle(Context c, String text, boolean checked) {
        SwitchCompat s = new SwitchCompat(c);
        s.setText(text);
        s.setTextSize(16);
        s.setChecked(checked);
        s.setMinHeight(Ui.dp(c, 52));
        s.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        return s;
    }

    public static LinearLayout chipRow(Context c, String[] labels, TextView[] out, int selected, IntConsumer click) {
        LinearLayout r = row(c);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = chip(c, labels[i]);
            setChip(t, i == selected);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
            p.setMarginEnd(Ui.dp(c, 8));
            r.addView(t, p);
            out[i] = t;
            t.setOnClickListener(v -> {
                select(out, idx);
                click.accept(idx);
            });
        }
        return r;
    }

    public static HorizontalScrollView scrollRow(Context c, LinearLayout row) {
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row, new HorizontalScrollView.LayoutParams(WRAP, WRAP));
        return hs;
    }
}
