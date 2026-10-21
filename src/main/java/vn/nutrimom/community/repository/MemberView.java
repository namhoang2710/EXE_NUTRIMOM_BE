package vn.nutrimom.community.repository;

/** Thông tin công khai của một thành viên: chỉ tên hiển thị và avatar. */
public record MemberView(String id, String displayName, String avatarKey) {
}
