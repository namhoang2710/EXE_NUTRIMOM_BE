package vn.nutrimom.payment.domain;

public enum PlanTier {
    FREE(0, "Gói Miễn phí"),
    PLAN_99K(99000, "Gói Cơ bản 99K"),
    PLAN_399K(399000, "Gói Toàn diện 399K");

    private final int price;
    private final String displayName;

    PlanTier(int price, String displayName) {
        this.price = price;
        this.displayName = displayName;
    }

    public int getPrice() {
        return price;
    }

    public String getDisplayName() {
        return displayName;
    }
}
