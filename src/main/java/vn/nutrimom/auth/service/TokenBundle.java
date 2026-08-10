package vn.nutrimom.auth.service;

public record TokenBundle(String accessToken, long accessExpiresIn, String refreshToken,
                          long refreshExpiresIn, String tokenType) { }
