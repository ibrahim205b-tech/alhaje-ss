package iq.haji.cashier.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import iq.haji.cashier.R;

/** تحميل صور الأصناف في الخلفية مع ذاكرة مؤقتة، دون مكتبات خارجية. */
public final class ImageCache {
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 16)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageCache() {}

    public static void load(final ImageView iv, final String path) {
        iv.setTag(path);
        if (path == null || path.isEmpty()) {
            placeholder(iv);
            return;
        }
        Bitmap cached = CACHE.get(path);
        if (cached != null) {
            show(iv, cached);
            return;
        }
        placeholder(iv);
        EXECUTOR.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            final Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return;
            CACHE.put(path, b);
            MAIN.post(() -> {
                if (path.equals(iv.getTag())) show(iv, b);
            });
        });
    }

    private static void placeholder(ImageView iv) {
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setImageResource(R.drawable.ic_launcher_foreground);
    }

    private static void show(ImageView iv, Bitmap b) {
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
    }
}
