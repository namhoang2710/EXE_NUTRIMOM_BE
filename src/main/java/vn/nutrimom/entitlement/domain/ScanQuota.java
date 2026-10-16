package vn.nutrimom.entitlement.domain;

/**
 * Hạn mức AI scan của một gói (spec mục 15 + 17).
 *
 * <p>Hiện mới chỉ được <em>công bố</em> qua catalog: module scan chưa tồn tại nên chưa có chỗ nào
 * trừ lượt. Khi task 15 có thật thì đây là con số nó phải đọc.</p>
 */
public record ScanQuota(int limit, QuotaPeriod period) {

    public static ScanQuota unlimited() {
        return new ScanQuota(0, QuotaPeriod.UNLIMITED);
    }

    public static ScanQuota perDay(int limit) {
        return new ScanQuota(limit, QuotaPeriod.DAILY);
    }

    public static ScanQuota lifetime(int limit) {
        return new ScanQuota(limit, QuotaPeriod.LIFETIME);
    }

    public boolean isUnlimited() {
        return period == QuotaPeriod.UNLIMITED;
    }
}
