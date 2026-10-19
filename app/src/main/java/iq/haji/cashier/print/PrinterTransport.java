package iq.haji.cashier.print;

import java.io.IOException;

/** قناة اتصال بالطابعة (بلوتوث أو شبكة). */
public interface PrinterTransport {
    void open() throws PrintException;

    void write(byte[] b, int off, int len) throws IOException;

    void flush() throws IOException;

    /** يقرأ بايتاً واحداً ويعيد -1 عند انتهاء المهلة. */
    int readByte(int timeoutMs) throws IOException;

    /** يتخلص من أي بيانات قديمة واردة من الطابعة. */
    void drain() throws IOException;

    boolean isOpen();

    void close();

    boolean isBluetooth();

    String describe();
}
