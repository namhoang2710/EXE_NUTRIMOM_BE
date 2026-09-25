package vn.nutrimom.contact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import vn.nutrimom.auth.domain.Gender;
import vn.nutrimom.contact.domain.ContactRequestStatus;
import vn.nutrimom.contact.domain.ContactTopic;

/** DTO của hộp thư hỗ trợ (Contact). */
public final class ContactDtos {
    private ContactDtos() {
    }

    public record CreateContactRequest(
            @NotNull(message = "Vui lòng chọn chủ đề thắc mắc.") ContactTopic topic,
            @NotBlank(message = "Vui lòng nhập nội dung thắc mắc.")
            @Size(max = 2000, message = "Nội dung thắc mắc tối đa 2000 ký tự.") String message) {
    }

    /** Yêu cầu hỗ trợ nhìn từ phía user. */
    public record ContactRequestResponse(
            String id,
            ContactTopic topic,
            String message,
            ContactRequestStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            OffsetDateTime cancelledAt) {
    }

    /** Một dòng trong hộp thư admin. */
    public record AdminContactSummary(
            String id,
            ContactTopic topic,
            String messagePreview,
            ContactRequestStatus status,
            String userId,
            String userDisplayName,
            String userPhone,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {
    }

    /** Thông tin liên hệ của user để admin gọi điện giải đáp. */
    public record ContactUserInfo(
            String id,
            String displayName,
            String phone,
            String email,
            Gender gender,
            LocalDate dateOfBirth) {
    }

    public record AdminContactDetail(
            String id,
            ContactTopic topic,
            String message,
            ContactRequestStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            String completedBy,
            OffsetDateTime cancelledAt,
            ContactUserInfo user) {
    }

    /** Envelope phân trang (page bắt đầu từ 1), cùng shape với các module khác. */
    public record ContactPage<T>(
            List<T> items,
            int page,
            int pageSize,
            long totalItems,
            int totalPages) {

        public static <T> ContactPage<T> of(Page<?> source, List<T> items) {
            return new ContactPage<>(List.copyOf(items), source.getNumber() + 1, source.getSize(),
                    source.getTotalElements(), source.getTotalPages());
        }
    }
}
