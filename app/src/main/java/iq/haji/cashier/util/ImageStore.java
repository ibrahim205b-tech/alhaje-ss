package iq.haji.cashier.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * ينسخ صورة الصنف المختارة (عبر منتقي الصور النظامي بدون أي إذن) إلى مجلد التطبيق الخاص بحجم صغير،
 * فتبقى الصورة متاحة دائماً ولا تعتمد على ملف خارجي.
 */
public final class ImageStore {
    private static final int MAX_SIDE = 600;

    private ImageStore() {}

    public static String importImage(Context ctx, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("تعذر فتح الصورة");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("ملف الصورة غير صالح");

        int sample = 1;
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;

        Bitmap bmp;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("تعذر فتح الصورة");
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("تعذر قراءة الصورة");

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int max = Math.max(w, h);
        if (max > MAX_SIDE) {
            float r = (float) MAX_SIDE / max;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * r)),
                    Math.max(1, Math.round(h * r)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }

        File dir = new File(ctx.getFilesDir(), "product_images");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد الصور");
        File out = new File(dir, UUID.randomUUID().toString() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        } finally {
            bmp.recycle();
        }
        return out.getAbsolutePath();
    }
}
