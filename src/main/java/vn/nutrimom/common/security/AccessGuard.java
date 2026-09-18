package vn.nutrimom.common.security;

import java.util.Optional;
import org.springframework.stereotype.Component;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/**
 * Gom quy ước phân quyền theo dòng dữ liệu (row-level) của dự án về một chỗ để dùng nhất quán.
 *
 * <p>Quy ước (spec mục 21 + contract test mục 22 "IDOR"):</p>
 * <ul>
 *   <li>Không sở hữu tài nguyên → trả {@link ErrorCode#RESOURCE_NOT_FOUND} (404), KHÔNG phải 403,
 *       để không lộ việc tài nguyên có tồn tại. Query nên đã kèm điều kiện chủ sở hữu
 *       (vd {@code findByIdAndOwnerUserId}) rồi bọc kết quả qua {@link #requireOwned}.</li>
 *   <li>Tài nguyên chia sẻ nhưng thiếu scope → {@link ErrorCode#SHARING_SCOPE_REQUIRED} (403).</li>
 * </ul>
 */
@Component
public class AccessGuard {

    /** Trả về entity nếu có (đã lọc theo chủ sở hữu ở tầng query), ngược lại ném 404. */
    public <T> T requireOwned(Optional<T> ownedEntity) {
        return ownedEntity.orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Như {@link #requireOwned(Optional)} nhưng giữ thông điệp lỗi riêng của module. */
    public <T> T requireOwned(Optional<T> ownedEntity, String notFoundMessage) {
        return ownedEntity.orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, notFoundMessage));
    }

    /** Chặn truy cập chéo chủ sở hữu bằng 404 (dùng khi đã tự so sánh owner). */
    public void requireOwnership(boolean owned) {
        if (!owned) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /** Yêu cầu có scope chia sẻ; thiếu → 403 SHARING_SCOPE_REQUIRED. */
    public void requireScope(boolean hasScope) {
        if (!hasScope) {
            throw new BusinessException(ErrorCode.SHARING_SCOPE_REQUIRED);
        }
    }
}
