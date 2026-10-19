package iq.haji.cashier.util;

/** خطأ منطقي برسالة عربية جاهزة للعرض للمستخدم. */
public class AppException extends RuntimeException {
    public AppException(String message) {
        super(message);
    }
}
