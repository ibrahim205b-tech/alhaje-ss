package iq.haji.cashier.print;

/** خطأ طباعة/اتصال برسالة عربية جاهزة للعرض. */
public class PrintException extends Exception {
    public PrintException(String message) {
        super(message);
    }

    public PrintException(String message, Throwable cause) {
        super(message, cause);
    }
}
