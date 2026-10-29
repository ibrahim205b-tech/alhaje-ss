package iq.haji.cashier.print;

import android.graphics.Bitmap;

/** ESC/POS القياسي: يعمل مع معظم الطابعات الحرارية (Xprinter, Rongta, Epson-compatible, Sunmi...). */
public class EscPosProtocol implements PrinterProtocol {

    @Override
    public String id() {
        return "ESCPOS";
    }

    @Override
    public byte[] encode(Bitmap receipt, int copies, boolean cut) {
        EscPosBuilder b = new EscPosBuilder().init();
        int n = Math.max(1, copies);
        for (int i = 0; i < n; i++) {
            b.raster(receipt);
            if (cut) b.feed(3).cut();
            else b.feed(5);
        }
        return b.build();
    }

    /** DLE ENQ 2 : الخروج من حالة الخطأ (مثل نفاد الورق) ومسح ذاكرة الاستقبال والطباعة. */
    @Override
    public byte[] recoverCommand() {
        return new byte[]{0x10, 0x05, 0x02};
    }

    /** DLE EOT 2 : سبب توقف الطابعة (الغطاء، نفاد الورق، خطأ). */
    @Override
    public byte[] coverStatusQuery() {
        return new byte[]{0x10, 0x04, 0x02};
    }

    /** رد حالة صحيح في ESC/POS: البت 1 و 4 دائماً 1، والبت 0 و 7 دائماً 0. */
    private static boolean valid(int b) {
        return b >= 0 && (b & 0x93) == 0x12;
    }

    @Override
    public String blockingProblem(int cover, int paper) {
        if (valid(cover) && (cover & 0x04) != 0) {
            return "غطاء الطابعة مفتوح. أغلقه جيداً ثم أعد المحاولة.";
        }
        if ((valid(paper) && (paper & 0x60) != 0) || (valid(cover) && (cover & 0x20) != 0)) {
            return "الورق نفد في الطابعة. ضع لفة جديدة وأغلق الغطاء ثم أعد المحاولة.";
        }
        return null;
    }

    /** DLE EOT 4 : حالة مستشعر الورق. */
    @Override
    public byte[] paperStatusQuery() {
        return new byte[]{0x10, 0x04, 0x04};
    }

    @Override
    public String interpretPaperStatus(int b) {
        if ((b & 0x60) != 0) return "الورق نفد";
        if ((b & 0x0C) != 0) return "الورق على وشك النفاد";
        return "الورق متوفر";
    }
}
