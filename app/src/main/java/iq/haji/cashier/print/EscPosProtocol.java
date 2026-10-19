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
