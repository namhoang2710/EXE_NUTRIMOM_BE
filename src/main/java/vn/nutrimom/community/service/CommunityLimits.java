package vn.nutrimom.community.service;

import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/** Giới hạn cứng của hợp đồng API (không cấu hình được, FE dựa vào chúng). */
public final class CommunityLimits {
    public static final int POST_MAX_CHARS = 5000;
    public static final int COMMENT_MAX_CHARS = 1000;
    public static final int NOTE_MAX_CHARS = 500;
    public static final int FEED_MAX_PAGE = 20;

    private CommunityLimits() {
    }

    public static void requirePageSize(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit phải từ 1 đến " + max + ".");
        }
    }
}
