package vn.nutrimom.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/**
 * Chốt hợp đồng row-level của {@link AccessGuard}: không sở hữu → 404 (không lộ tồn tại),
 * thiếu scope chia sẻ → 403. Đây là quy ước chống IDOR mà spec mục 22 kiểm ở tầng API.
 */
class AccessGuardTest {

    private final AccessGuard guard = new AccessGuard();

    @Test
    void requireOwnedReturnsEntityWhenPresent() {
        String entity = "pregnancy-1";
        assertThat(guard.requireOwned(Optional.of(entity))).isEqualTo(entity);
    }

    @Test
    void requireOwnedThrowsNotFoundWhenAbsent() {
        assertThatThrownBy(() -> guard.requireOwned(Optional.empty()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void requireOwnershipThrowsNotFoundOnCrossOwnerAccess() {
        assertThatThrownBy(() -> guard.requireOwnership(false))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code()));
        assertThatCode(() -> guard.requireOwnership(true)).doesNotThrowAnyException();
    }

    @Test
    void requireScopeThrowsForbiddenWhenScopeMissing() {
        assertThatThrownBy(() -> guard.requireScope(false))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.SHARING_SCOPE_REQUIRED.code());
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        assertThatCode(() -> guard.requireScope(true)).doesNotThrowAnyException();
    }
}
