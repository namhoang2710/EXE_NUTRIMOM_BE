package vn.nutrimom.common.api;

public record ApiResponse<T>(T data, ApiMeta meta) {
}
