package iq.haji.cashier.print;

import android.graphics.Bitmap;

/**
 * لغة أوامر الطابعة. لإضافة طابعة بلغة مختلفة (CPCL, TSPL, STAR...) اكتب كلاساً جديداً يطبق هذه الواجهة
 * ثم أضفه في PrinterProtocols.current.
 */
public interface PrinterProtocol {
    String id();

    /** يحوّل صورة الإيصال إلى البايتات التي تُرسل للطابعة كاملة (تشمل النسخ والقص). */
    byte[] encode(Bitmap receipt, int copies, boolean cut);

    /** أوامر استعلام حالة الورق إن كانت مدعومة، وإلا null. */
    byte[] paperStatusQuery();

    /** تفسير بايت الاستجابة لحالة الورق. */
    String interpretPaperStatus(int responseByte);
}
