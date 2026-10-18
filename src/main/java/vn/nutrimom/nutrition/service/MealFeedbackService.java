package vn.nutrimom.nutrition.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.nutrition.domain.MealFeedbackEntity;
import vn.nutrimom.nutrition.domain.MealFeedbackSignal;
import vn.nutrimom.nutrition.dto.DiaryDtos.MealFeedbackRequest;
import vn.nutrimom.nutrition.dto.DiaryDtos.MealFeedbackView;
import vn.nutrimom.nutrition.repository.MealFeedbackRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;

/**
 * Phản hồi sau bữa: một lần chạm, và vòng lặp khép lại ở điểm P của nhánh 4.
 *
 * <h2>Hai việc, cố ý nằm cạnh nhau</h2>
 *
 * <p>Lớp này vừa <strong>ghi</strong> phản hồi vừa <strong>đọc</strong> nó ra thành tín hiệu chấm
 * điểm. Hai việc đó phải chung một chỗ vì chúng chia nhau đúng một luật dễ làm sai: hạn bảy ngày
 * của {@link MealFeedbackSignal#HARD_NOW}. Đặt phép ghi ở đây và phép đọc ở
 * {@code RecommendationService} sẽ cho hai nơi cùng phải nhớ luật ấy, và nơi quên sẽ là nơi áp một
 * cơn nghén đã qua từ tháng trước lên thứ hạng hôm nay.</p>
 *
 * <h2>Phản hồi không phải khai báo an toàn</h2>
 *
 * <p>Không giá trị nào ở đây biến thành dị ứng hay thành một bộ lọc cứng. "Không thích" hạ điểm
 * khẩu vị 25 điểm, "khó ăn lúc này" hạ 20 điểm trong bảy ngày — món vẫn còn trong danh sách ứng
 * viên và vẫn xuất hiện nếu nó thật sự hợp. Muốn chặn hẳn một món thì đó là {@code BLOCKED} ở hồ sơ
 * ăn uống (nhánh 3): một lựa chọn chủ động, không hạn, và nó loại món khỏi ứng viên chứ không trừ
 * điểm (QĐ-03).</p>
 */
@Service
public class MealFeedbackService {

    private final MealFeedbackRepository feedbacks;
    private final RecipeRepository recipes;
    private final Clock clock;

    public MealFeedbackService(MealFeedbackRepository feedbacks, RecipeRepository recipes,
                               Clock clock) {
        this.feedbacks = feedbacks;
        this.recipes = recipes;
        this.clock = clock;
    }

    /**
     * Ghi một lần phản hồi.
     *
     * <p>Thêm dòng mới chứ không sửa dòng cũ: người ta đổi ý theo thời gian và chuỗi đổi ý đó là dữ
     * liệu thật. Chỉ có <strong>một</strong> phản hồi gần nhất còn hiệu lực được đem ra chấm điểm,
     * nên lịch sử dài không làm sai kết quả.</p>
     */
    @Transactional
    public MealFeedbackView submit(String userId, MealFeedbackRequest request) {
        if (!recipes.existsById(request.recipeId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "Không tìm thấy món này trong danh mục.");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        MealFeedbackEntity feedback = new MealFeedbackEntity();
        feedback.setUserId(userId);
        feedback.setRecipeId(request.recipeId());
        feedback.setType(request.type());
        feedback.setCreatedAt(now);
        // Hạn lấy từ chính enum, không viết lại con số bảy ngày ở đây: một hằng số chép ra hai chỗ
        // là một hằng số sẽ lệch.
        feedback.setExpiresAt(request.type().validity().map(now::plus).orElse(null));

        MealFeedbackEntity saved = feedbacks.save(feedback);
        return new MealFeedbackView(saved.getId(), saved.getRecipeId(), saved.getType(),
                saved.getCreatedAt(), saved.getExpiresAt(), List.of());
    }

    /**
     * Phản hồi gần nhất <strong>còn hiệu lực</strong> của mỗi món, cho bước chấm điểm P.
     *
     * <p>Truy vấn trả về mới nhất trước và đã lọc hạn, nên dòng đầu tiên gặp cho mỗi món chính là
     * dòng duy nhất được áp — {@code putIfAbsent} ở đây là cách diễn đạt "chỉ một phản hồi gần
     * nhất" của QĐ-03, không phải một mẹo tối ưu.</p>
     *
     * <p>Nạp một lượt cho cả lượt gợi ý thay vì tra từng món trong vòng lặp: một người dùng có vài
     * chục phản hồi, còn một lượt gợi ý duyệt tới 200 món.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, MealFeedbackSignal> latestSignals(String userId) {
        Map<String, MealFeedbackSignal> latest = new LinkedHashMap<>();
        for (MealFeedbackEntity feedback : feedbacks.findValid(userId, OffsetDateTime.now(clock))) {
            latest.putIfAbsent(feedback.getRecipeId(), feedback.getType());
        }
        return Map.copyOf(latest);
    }
}
