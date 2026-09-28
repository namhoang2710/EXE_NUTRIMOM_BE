package vn.nutrimom.consultation.domain;

/** Trạng thái một ô trong lưới theo góc nhìn chuyên gia. */
public enum SlotState {
    /** Còn trống, đang nhận lịch. */
    OPEN,
    /** Đã có người đặt — chuyên gia không được đóng. */
    BOOKED,
    /** Chuyên gia đã tự đóng. */
    CLOSED
}
