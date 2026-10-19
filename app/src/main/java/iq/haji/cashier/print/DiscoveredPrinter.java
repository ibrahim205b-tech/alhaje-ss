package iq.haji.cashier.print;

/** جهاز بلوتوث وُجد أثناء البحث أو من الأجهزة المقترنة مسبقاً. */
public class DiscoveredPrinter {
    public final String name;
    public final String address;
    public final boolean bonded;
    public final boolean printerClass;

    public DiscoveredPrinter(String name, String address, boolean bonded, boolean printerClass) {
        this.name = (name == null || name.isEmpty()) ? "جهاز بدون اسم" : name;
        this.address = address;
        this.bonded = bonded;
        this.printerClass = printerClass;
    }
}
