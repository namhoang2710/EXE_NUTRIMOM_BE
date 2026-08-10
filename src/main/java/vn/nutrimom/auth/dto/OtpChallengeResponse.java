package vn.nutrimom.auth.dto;

public record OtpChallengeResponse(String challengeId, String maskedPhone,
                                   String deliveryChannel, long expiresIn,
                                   long resendAfter, String debugCode) { }
