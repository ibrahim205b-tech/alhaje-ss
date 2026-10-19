package iq.haji.cashier.model;

public class Shift {
    public long id;
    public long startedAt;
    public long endedAt; // 0 = مفتوحة
    public String cashierName;
    public long openingCash;
    public long closingCash;
    public String closingNote;
    public boolean closed;

    // ملخص يُحفظ عند الإغلاق كتقرير ثابت
    public int ordersCount;
    public int cancelledCount;
    public long salesTotal;
    public long discountsTotal;
    public long deliveryTotal;
}
