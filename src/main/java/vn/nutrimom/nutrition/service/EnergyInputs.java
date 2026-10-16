package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import vn.nutrimom.nutrition.domain.ActivityCategory;

/**
 * Dữ liệu người dùng tự khai cho ước tính năng lượng EER.
 *
 * <p><strong>Mọi trường đều cho phép null.</strong> Bước khai thông tin năng lượng là bước tùy
 * chọn trong onboarding (nhánh 3): bỏ qua được mà vẫn dùng được thực đơn. Thiếu trường nào thì
 * {@link EnergyEstimator} trả về "chưa ước tính được" kèm tên trường còn thiếu, chứ không điền
 * giá trị giả và không mặc định 2.000 kcal cho mọi người (BR20).</p>
 *
 * @param ageYears             tuổi tính theo năm
 * @param heightCm             chiều cao, centimet
 * @param currentWeightKg      cân nặng hiện tại, kilogram
 * @param prePregnancyWeightKg cân nặng <strong>trước</strong> thai kỳ, kilogram — chỉ dùng để
 *                             chọn thành phần {@code D} của mô hình ở tam cá nguyệt 2 và 3
 * @param gestationalDays      số ngày thai tại thời điểm tính; tuần thai {@code G} = số ngày / 7
 * @param activity             nhóm vận động người dùng tự chọn, không suy từ số bước chân
 */
public record EnergyInputs(Integer ageYears, BigDecimal heightCm, BigDecimal currentWeightKg,
                           BigDecimal prePregnancyWeightKg, Integer gestationalDays,
                           ActivityCategory activity) {
}
