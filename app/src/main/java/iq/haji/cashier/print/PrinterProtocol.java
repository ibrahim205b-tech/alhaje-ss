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

    /** أمر يُخرج الطابعة من حالة الخطأ ويمسح ذاكرتها قبل مهمة جديدة، أو null. */
    default byte[] recoverCommand() {
        return null;
    }

    /** استعلام حالة الغطاء/التوقف، أو null. */
    default byte[] coverStatusQuery() {
        return null;
    }

    /**
     * وصف مشكلة تمنع الطباعة (ورق نفد، غطاء مفتوح) أو null.
     * القيمة -1 تعني أن الطابعة لم ترد، فلا تُعد مشكلة.
     */
    default String blockingProblem(int coverByte, int paperByte) {
        return null;
    }
}
