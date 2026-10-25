package vn.nutrimom.review.domain;

public enum WebReviewStatus {
    /** Hiện trên trang công khai. */
    PUBLISHED,
    /** Admin ẩn vì vi phạm; người viết vẫn thấy kèm lý do. */
    HIDDEN,
    /** Người viết tự xoá (xoá mềm). */
    DELETED
}
