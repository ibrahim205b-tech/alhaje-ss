package iq.haji.cashier.model;

public class Category {
    public long id;
    public String name;
    public int sortOrder;
    public boolean active = true;

    public Category() {}

    public Category(long id, String name, int sortOrder, boolean active) {
        this.id = id;
        this.name = name;
        this.sortOrder = sortOrder;
        this.active = active;
    }
}
