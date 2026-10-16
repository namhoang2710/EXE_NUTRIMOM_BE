package vn.nutrimom.nutrition.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.nutrition.domain.EatingPreferenceEntity;

/**
 * Hồ sơ ăn uống, khoá bằng {@code app.pregnancies.id}.
 *
 * <p>Không có phương thức tra theo {@code userId}: chủ sở hữu suy từ hồ sơ thai kỳ đang hoạt động
 * của JWT, nên mọi lượt đọc đều đi qua {@code PregnancyRepository} trước. Thêm một lối tra tắt
 * theo user ở đây sẽ mở ra khả năng quên bước kiểm chủ sở hữu (BR19).</p>
 */
public interface EatingPreferenceRepository extends JpaRepository<EatingPreferenceEntity, String> {
}
