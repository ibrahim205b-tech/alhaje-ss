package iq.haji.cashier.model;

/** ملخص يُحسب من الطلبات المحفوظة فعلياً. المبيعات = الطلبات التي طُبعت ولم تُلغَ. */
public class SalesSummary {
    public int ordersCount;      // طلبات محتسبة في المبيعات
    public int completedCount;   // منها المكتملة
    public int cancelledCount;   // الملغاة
    public int pendingCount;     // بانتظار الطباعة (غير محتسبة)

    public long subtotalTotal;   // قبل الخصم والتوصيل
    public long salesTotal;      // الإجمالي النهائي
    public long discountsTotal;
    public long deliveryTotal;
    public long cancelledTotal;

    public int dineInCount;
    public long dineInTotal;
    public int takeawayCount;
    public long takeawayTotal;
    public int deliveryOrdersCount;
    public long deliveryOrdersTotal;
}
