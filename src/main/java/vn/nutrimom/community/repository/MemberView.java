package vn.nutrimom.community.repository;

/**
 * Thông tin công khai của một thành viên: tên hiển thị, avatar và cờ {@code official}
 * (tài khoản hiện có role ADMIN — bài của họ hiện nhãn "Ban quản trị"). Không mang role nào khác.
 */
public record MemberView(String id, String displayName, String avatarKey, boolean official) {
}
