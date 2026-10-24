package iq.haji.cashier.model;

/** صنف في المنيو. السعر بالدينار العراقي كعدد صحيح لتجنب أخطاء الفاصلة العائمة. */
public class Product {
    public long id;
    public long categoryId;
    public String name;
    public long price;
    public String imageUri; // اختياري
    public boolean active = true;
    /** نص السعر المعروض على بطاقة الكاشير لبطاقة مجمّعة الأحجام. لا يُحفظ في قاعدة البيانات. */
    public String priceLabel;

    public Product() {}
}
