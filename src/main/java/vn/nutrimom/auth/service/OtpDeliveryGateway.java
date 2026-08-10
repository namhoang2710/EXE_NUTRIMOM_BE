package vn.nutrimom.auth.service;

public interface OtpDeliveryGateway {
    String deliver(String phone, String code);
}
