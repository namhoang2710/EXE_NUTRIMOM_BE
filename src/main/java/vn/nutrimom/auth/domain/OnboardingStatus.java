package vn.nutrimom.auth.domain;

public enum OnboardingStatus { PROFILE_REQUIRED, CONTEXT_REQUIRED, COMPLETED }
// PROFILE_REQUIRED — vừa verify OTP xong, chưa nhập hồ sơ → FE mở màn hình nhập tên/gender.
// CONTEXT_REQUIRED — đã nhập hồ sơ cơ bản, nhưng chưa có ngữ cảnh thai kỳ (chưa POST / pregnancies hoặc chưa nhận lời mời) → FE mở bước tiếp theo.
// COMPLETED — xong hết → vào thẳng dashboard.