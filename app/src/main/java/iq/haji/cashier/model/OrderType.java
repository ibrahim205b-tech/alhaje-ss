package iq.haji.cashier.model;

public final class OrderType {
    public static final String DINE_IN = "DINE_IN";
    public static final String TAKEAWAY = "TAKEAWAY";
    public static final String DELIVERY = "DELIVERY";

    private OrderType() {}

    public static String label(String type) {
        if (DINE_IN.equals(type)) return "داخل المطعم";
        if (TAKEAWAY.equals(type)) return "سفري";
        if (DELIVERY.equals(type)) return "دليفري";
        return type == null ? "" : type;
    }
}
