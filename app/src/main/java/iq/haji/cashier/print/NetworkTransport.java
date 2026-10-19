package iq.haji.cashier.print;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** طابعة شبكية (Wi-Fi أو Ethernet) عبر TCP خام، المنفذ المعتاد 9100 (RAW / JetDirect). */
public class NetworkTransport implements PrinterTransport {
    private final String host;
    private final int port;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public NetworkTransport(String host, int port) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
    }

    @Override
    public void open() throws PrintException {
        if (host.isEmpty()) throw new PrintException("أدخل عنوان IP للطابعة الشبكية");
        if (port < 1 || port > 65535) throw new PrintException("رقم المنفذ غير صحيح (1 إلى 65535)");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
        } catch (UnknownHostException e) {
            closeQuietly(s);
            throw new PrintException("عنوان الطابعة غير صحيح: " + host, e);
        } catch (SocketTimeoutException e) {
            closeQuietly(s);
            throw new PrintException("انتهت مهلة الاتصال بـ " + host + ":" + port
                    + ". تأكد أن الطابعة مفتوحة وعلى نفس الشبكة.", e);
        } catch (IOException e) {
            closeQuietly(s);
            throw new PrintException("تعذر الاتصال بـ " + host + ":" + port
                    + ". تأكد من العنوان والمنفذ وأن الطابعة على نفس الشبكة.", e);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (out == null) throw new IOException("غير متصل");
        out.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        if (out != null) out.flush();
    }

    @Override
    public int readByte(int timeoutMs) throws IOException {
        if (socket == null || in == null) throw new IOException("غير متصل");
        socket.setSoTimeout(timeoutMs);
        try {
            return in.read();
        } catch (SocketTimeoutException e) {
            return -1;
        }
    }

    @Override
    public void drain() throws IOException {
        if (in == null) return;
        while (in.available() > 0) //noinspection ResultOfMethodCallIgnored
            in.read();
    }

    @Override
    public boolean isOpen() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        if (socket != null) closeQuietly(socket);
        socket = null;
        in = null;
        out = null;
    }

    @Override
    public boolean isBluetooth() {
        return false;
    }

    @Override
    public String describe() {
        return "شبكة " + host + ":" + port;
    }
}
