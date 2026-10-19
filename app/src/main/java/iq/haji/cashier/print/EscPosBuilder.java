package iq.haji.cashier.print;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;

/** يبني أوامر ESC/POS. كل النصوص تُرسل كصور raster فلا يعتمد الإيصال على جداول الحروف في الطابعة. */
public class EscPosBuilder {
    private static final int STRIP_ROWS = 128;
    private static final int THRESHOLD = 150;

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    /** ESC @ : تهيئة الطابعة. */
    public EscPosBuilder init() {
        buf.write(0x1B);
        buf.write(0x40);
        return this;
    }

    /** ESC d n : تغذية n سطراً. */
    public EscPosBuilder feed(int lines) {
        buf.write(0x1B);
        buf.write(0x64);
        buf.write(Math.max(0, Math.min(lines, 255)));
        return this;
    }

    /** GS V 66 n : تغذية ثم قص جزئي. تتجاهله الطابعات التي بلا قاطع. */
    public EscPosBuilder cut() {
        buf.write(0x1D);
        buf.write(0x56);
        buf.write(66);
        buf.write(3);
        return this;
    }

    /** GS v 0 : صورة نقطية بشرائح من 128 صفاً لتجنب فيض ذاكرة الطابعة. عرض الصورة من مضاعفات 8. */
    public EscPosBuilder raster(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int bytesPerRow = (w + 7) / 8;
        int[] px = new int[w];
        for (int y0 = 0; y0 < h; y0 += STRIP_ROWS) {
            int sh = Math.min(STRIP_ROWS, h - y0);
            byte[] data = new byte[bytesPerRow * sh];
            for (int r = 0; r < sh; r++) {
                bmp.getPixels(px, 0, w, 0, y0 + r, w, 1);
                int base = r * bytesPerRow;
                for (int x = 0; x < w; x++) {
                    int p = px[x];
                    int a = p >>> 24;
                    int lum = (299 * ((p >> 16) & 0xFF) + 587 * ((p >> 8) & 0xFF) + 114 * (p & 0xFF)) / 1000;
                    if (a > 128 && lum < THRESHOLD) {
                        data[base + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                    }
                }
            }
            buf.write(0x1D);
            buf.write(0x76);
            buf.write(0x30);
            buf.write(0x00);
            buf.write(bytesPerRow & 0xFF);
            buf.write((bytesPerRow >> 8) & 0xFF);
            buf.write(sh & 0xFF);
            buf.write((sh >> 8) & 0xFF);
            buf.write(data, 0, data.length);
        }
        return this;
    }

    public byte[] build() {
        return buf.toByteArray();
    }
}
