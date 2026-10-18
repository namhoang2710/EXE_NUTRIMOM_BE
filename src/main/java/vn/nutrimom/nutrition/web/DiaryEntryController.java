package vn.nutrimom.nutrition.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.nutrition.dto.DiaryDtos.CreateDiaryEntryRequest;
import vn.nutrimom.nutrition.dto.DiaryDtos.DiaryView;
import vn.nutrimom.nutrition.dto.DiaryDtos.PatchDiaryEntryRequest;
import vn.nutrimom.nutrition.service.DiaryService;

/**
 * Một dòng nhật ký.
 *
 * <p>Đường dẫn <strong>không mang ngày</strong>: giao diện cầm sẵn mã dòng từ nhật ký vừa đọc, và
 * bắt nó ghép lại ngày chỉ tạo thêm một chỗ gửi sai. Chủ sở hữu được kiểm ngay trong câu truy vấn
 * dòng ({@code findByIdAndUserId}) — tài khoản khác hỏi đúng mã dòng thì nhận 404 (BR19, TC20).</p>
 *
 * <p>Cả ba thao tác trả về <strong>cả ngày đã tính lại</strong>, không riêng dòng vừa chạm: ghi,
 * sửa hay xóa một món đều làm đổi tổng đã ghi và phần còn lại của ngày, và để giao diện tự cộng
 * lại là tạo nguồn sự thật thứ hai cho đúng những con số mà bất biến 4 muốn chỉ có một nguồn.</p>
 *
 * <p><strong>Không thao tác nào ở đây chạm vào thực đơn.</strong> Nhật ký và kế hoạch là hai tập dữ
 * liệu độc lập: tổng dự kiến trong phản hồi giữ nguyên qua mọi lần ghi, sửa và xóa (TC17).</p>
 */
@RestController
@RequestMapping("/api/v1/diary-entries")
@Tag(name = "Diary", description = "Nhật ký món đã ăn và phần còn lại của ngày")
@SecurityRequirement(name = "bearerAuth")
public class DiaryEntryController {

    private final DiaryService service;

    public DiaryEntryController(DiaryService service) {
        this.service = service;
    }

    /**
     * Ghi một món đã ăn (UC05).
     *
     * <p><strong>BR16: nhật ký được tạo sau xác nhận ngày, bữa và số phần.</strong> Ba trường đó
     * bắt buộc và không có giá trị mặc định phía máy chủ — giao diện điền sẵn từ kế hoạch, nhưng
     * thứ tới được đây phải là thứ người dùng đã bấm xác nhận. "Ăn một nửa" chính là gửi
     * {@code "servings": 0.5}, và mục kế hoạch vẫn giữ nguyên số phần của nó (TC15).</p>
     *
     * <p>{@code request_id} nằm trong body và unique cùng người dùng: bấm hai lần vì mạng chậm trả
     * lại đúng bản ghi cũ chứ không ghi hai bữa (TC16).</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ghi một món đã ăn")
    public ApiResponse<DiaryView> create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateDiaryEntryRequest request) {
        return ApiResponses.success(service.create(jwt.getSubject(), request));
    }

    /** Sửa món hoặc số phần thực tế; trả về tổng mới của ngày. */
    @PatchMapping("/{entryId}")
    @Operation(summary = "Sửa một dòng nhật ký")
    public ApiResponse<DiaryView> patch(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable String entryId,
                                        @Valid @RequestBody PatchDiaryEntryRequest request) {
        return ApiResponses.success(service.patch(jwt.getSubject(), entryId, request));
    }

    /** Xóa một dòng; tổng đã ghi được tính lại, tổng dự kiến không đổi (TC17). */
    @DeleteMapping("/{entryId}")
    @Operation(summary = "Xóa một dòng nhật ký")
    public ApiResponse<DiaryView> delete(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable String entryId) {
        return ApiResponses.success(service.delete(jwt.getSubject(), entryId));
    }
}
