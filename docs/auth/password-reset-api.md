# API quên mật khẩu và đổi mật khẩu

Nhánh `feature/forgot-password-email` · 25/10/2026 · Migration `V68__password_reset_tokens.sql`

JSON là snake_case, bọc `ApiResponse` (`data`, `meta`). Lỗi theo `docs/error-codes.md`.

## Luồng
```
/forgot-password  →  POST /auth/password/forgot {email}   (luôn 200, cùng một câu)
mail: {APP_FRONTEND_BASE_URL}{APP_FRONTEND_RESET_PASSWORD_PATH}?token=<43 ký tự>   (mặc định /reset-password, sống 30 phút, dùng 1 lần)
/reset-password?token=…  →  POST /auth/password/reset/validate {token}   →  form mật khẩu mới
                          →  POST /auth/password/reset {token, new_password}   →  về trang đăng nhập
```

## Endpoint
| Method | Path | Đăng nhập | Body | Kết quả |
|---|---|---|---|---|
| POST | `/api/v1/auth/password/forgot` | Không | `{ "email" }` | 200 `{ accepted: true, message }`. Câu trả lời **giống hệt nhau** dù email có tồn tại, bị khoá hay bị chặn spam. FE hiện nguyên `message`, không suy luận gì thêm |
| POST | `/api/v1/auth/password/reset/validate` | Không | `{ "token" }` | 200 `{ valid: true, expires_at, email_masked }` (vd. `l***@gmail.com`). Lỗi: 400 `PASSWORD_RESET_TOKEN_INVALID`, 410 `PASSWORD_RESET_TOKEN_EXPIRED` |
| POST | `/api/v1/auth/password/reset` | Không | `{ "token", "new_password" }` | 200 `{ reset: true, message }`. **Không** trả phiên đăng nhập, FE chuyển sang trang đăng nhập. Lỗi: như validate; 422 `VALIDATION_ERROR` khi mật khẩu sai luật (token vẫn dùng lại được) |
| POST | `/api/v1/auth/password/change` | Có | `{ "current_password", "new_password" }` | 204. Lỗi: 400 `CURRENT_PASSWORD_INCORRECT`; 409 `PASSWORD_NOT_SET` (tài khoản tạo bằng magic link, chưa có mật khẩu: hướng người dùng sang Quên mật khẩu); 422 `VALIDATION_ERROR` (sai luật, hoặc trùng mật khẩu cũ: `fields.new_password`) |

Luật mật khẩu giống đăng ký (`PasswordPolicy`): 8–20 ký tự, có chữ hoa, chữ thường, chữ số, ký tự đặc biệt, không khoảng trắng.

## Hành vi cần biết
- Đặt lại hoặc đổi mật khẩu thành công thì **mọi refresh token bị thu hồi**: mọi thiết bị phải đăng nhập lại.
  Access token đang có vẫn dùng được tới khi hết hạn (tối đa 15 phút).
  Sau `/change`, FE nên xoá phiên hiện tại và đưa người dùng về đăng nhập.
- Hệ thống gửi thư "Mật khẩu đã được thay đổi" tới email của tài khoản.
- Xin link mới thì link cũ hết hiệu lực. Mỗi tài khoản tối đa 1 thư/60 giây và 5 thư/giờ. Vượt mức, API vẫn trả cùng câu.
- Tài khoản chưa kích hoạt: đặt lại mật khẩu thành công thì **kích hoạt luôn** (đã chứng minh sở hữu email).
- Tài khoản chỉ có số điện thoại (không email) không đặt lại được qua email. Dùng đăng nhập OTP.
- Thư được gửi sau khi request đã trả về, nên thư có thể đến chậm vài giây.

## Rate limit (theo IP, không còn ghép `X-Device-Id`)
| Route | Policy | Ngưỡng |
|---|---|---|
| `/password/forgot`, `/resend-activation`, `/magic-link/request` | `email-send` | 10 / 15 phút |
| `/password/reset`, `/password/reset/validate`, `/activate`, `/magic-link/verify` | `code-verify` | 20 / 15 phút |
| `/register` | `register` | 10 / giờ |
| `/password/change` (theo người dùng) | `password-change` | 5 / 15 phút |

Vượt ngưỡng: 429 `RATE_LIMITED` kèm header `Retry-After`.

## Biến môi trường mới (tuỳ chọn)
`NUTRIMOM_PASSWORD_RESET_TTL` (mặc định `PT30M`), `APP_FRONTEND_RESET_PASSWORD_PATH` (mặc định `/reset-password`).
Link trong thư dùng `APP_FRONTEND_BASE_URL` hiện có. Deploy thật phải đặt đúng domain FE.
