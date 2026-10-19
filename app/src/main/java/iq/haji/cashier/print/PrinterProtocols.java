package iq.haji.cashier.print;

import iq.haji.cashier.data.AppSettings;

/** اختيار البروتوكول. حالياً ESC/POS فقط؛ أضف حالات جديدة هنا بعد معرفة موديل الطابعة. */
public final class PrinterProtocols {
    public static final String KEY = "printer_protocol";

    private PrinterProtocols() {}

    public static PrinterProtocol current(AppSettings settings) {
        String id = settings.get(KEY, "ESCPOS");
        switch (id) {
            // case "CPCL": return new CpclProtocol();   // مثال لإضافة بروتوكول آخر مستقبلاً
            case "ESCPOS":
            default:
                return new EscPosProtocol();
        }
    }
}
