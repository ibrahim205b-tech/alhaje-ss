package iq.haji.cashier;

import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import iq.haji.cashier.util.Ui;

/** قالب الشاشات الثانوية: شريط علوي بزر رجوع وعنوان وأزرار إجراءات، ومنطقة محتوى. */
public abstract class PageActivity extends BaseActivity {
    protected FrameLayout content;
    private TextView tvTitle;
    private LinearLayout actions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page);
        content = findViewById(R.id.content);
        tvTitle = findViewById(R.id.tvPageTitle);
        actions = findViewById(R.id.actions);
        findViewById(R.id.tvBack).setOnClickListener(v -> finish());
    }

    protected void setPageTitle(String t) {
        tvTitle.setText(t);
    }

    protected TextView addAction(String text, final Runnable r) {
        TextView t = new TextView(this, null, 0, R.style.Haji_Nav);
        t.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(Ui.dp(this, 8));
        actions.addView(t, p);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    /** ينفّذ العمل في خيط خلفي ثم يسلّم النتيجة لخيط الواجهة. الأخطاء تظهر كرسالة. */
    protected <T> void bg(final Callable<T> work, final Consumer<T> ui) {
        new Thread(() -> {
            T r = null;
            String err = null;
            try {
                r = work.call();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final T fr = r;
            final String ferr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (ferr != null) Ui.toast(this, ferr);
                else ui.accept(fr);
            });
        }, "bg").start();
    }
}
