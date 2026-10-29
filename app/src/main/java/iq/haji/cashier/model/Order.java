package iq.haji.cashier.model;

import java.util.ArrayList;
import java.util.List;

public class Order {
    /** رقم الطلب المتسلسل: يُمنح من قاعدة البيانات (AUTOINCREMENT) ولا يتكرر أبداً. 0 = لم يُحفظ بعد. */
    public long id;
    /** رقم الفاتورة الظاهر للزبون: يبدأ من 1 في كل وردية. 0 = لم يُحفظ بعد. */
    public long no;
    /** عُدّل بعد طباعته بإذن المدير. */
    public boolean edited;
    public String uuid;
    public long createdAt;
    public long shiftId;
    public String type = OrderType.TAKEAWAY;
    public String status = OrderStatus.NEW;
    public String customerName;
    public String phone;
    /** رقم واتساب الزبون، يُكتب يدوياً (اختياري). */
    public String whatsapp;
    public String address;
    public String driver;
    public String notes;
    public String paymentMethod = "نقدي";
    public long deliveryFee;
    public long discount;
    public long subtotal;
    public long total;
    public int printCount;
    public long printedAt;
    public String cashierName;
    public final List<OrderLine> lines = new ArrayList<>();

    public void recalc() {
        long s = 0;
        for (OrderLine l : lines) s += l.total();
        subtotal = s;
        if (discount < 0) discount = 0;
        if (discount > subtotal) discount = subtotal;
        if (!OrderType.DELIVERY.equals(type)) deliveryFee = 0;
        if (deliveryFee < 0) deliveryFee = 0;
        total = subtotal - discount + deliveryFee;
    }

    public int itemCount() {
        int c = 0;
        for (OrderLine l : lines) c += l.qty;
        return c;
    }

    /** الرقم المطبوع والمعروض للفاتورة. */
    public long displayNo() {
        return no > 0 ? no : id;
    }
}
