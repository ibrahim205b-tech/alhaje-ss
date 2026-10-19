package iq.haji.cashier.model;

/** سطر في الطلب. الاسم والسعر منسوخان وقت البيع حتى لا يتغير التاريخ إذا تعدّل المنيو. */
public class OrderLine {
    public long id;
    public long orderId;
    public long productId;
    public String name;
    public long unitPrice;
    public int qty;
    public String note;

    public OrderLine() {}

    public long total() {
        return unitPrice * qty;
    }
}
