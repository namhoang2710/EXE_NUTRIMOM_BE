package vn.nutrimom.assistant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WebsiteGuideCatalogTest {
    private final WebsiteGuideCatalog catalog = new WebsiteGuideCatalog();

    @Test
    void shortVietnameseMedicalTermsResolveToRecordsEvenOnAnotherPage() {
        for (String question : new String[]{"Tóm tắt hồ sơ y tế của tôi", "Tôi lưu kết quả khám ở đâu?", "Lưu hồ sơ xét nghiệm như thế nào?"}) {
            assertEquals("/app/profile/records", catalog.search(question, "/app/profile").get(0).href());
        }
    }

    @Test
    void websiteOverviewAndNamedTopicsHaveUsefulDestinations() {
        assertEquals("/app", catalog.search("Hướng dẫn tôi sử dụng NutriMom", "/app/assistant").get(0).href());
        assertEquals("/app/profile/health", catalog.search("Tuần thai hiện tại của tôi", "/app/assistant").get(0).href());
        assertEquals("/app/profile/saved", catalog.search("Bài viết đã lưu ở đâu?", "/app/assistant").get(0).href());
    }

    @Test
    void unfinishedMealFeaturesAndNutritionArticlesHaveDifferentDestinations() {
        assertEquals("/app/nutrition", catalog.search("Tôi tạo thực đơn trong trang dinh dưỡng thế nào?", "/app").get(0).href());
        assertEquals("/app/knowledge", catalog.search("Tìm bài viết dinh dưỡng cho tôi", "/app").get(0).href());
    }
    @Test
    void fullWebsiteMapIncludesVerifiedTopicsAndTheirActualImplementationStatus() {
        assertEquals(26, catalog.all().size());
        String map = catalog.websiteMap().excerpt();
        for (var guide : catalog.all()) assertTrue(map.contains(guide.title()), guide.id());
        assertEquals("PLANNED", catalog.get("guide:nutrition").status());
        assertEquals("PLANNED", catalog.get("guide:password").status());
        assertEquals("ACTIVE", catalog.get("guide:consultation-history").status());
        assertTrue(catalog.get("guide:consultation-history").text().contains("hủy"));
    }
    @Test
    void detailedBookingCancellationAndSettingsQuestionsMatchTheActualWorkflow() {
        assertEquals("guide:consultations", catalog.search("Đặt lịch với chuyên gia ngẫu nhiên", "/app").get(0).id());
        assertEquals("guide:consultation-history", catalog.search("Tôi hủy lịch tư vấn đã đặt thế nào?", "/app").get(0).id());
        assertEquals("guide:password", catalog.search("Tôi đổi mật khẩu ở đâu?", "/app").get(0).id());
        assertEquals("guide:settings", catalog.search("Tôi cài đặt múi giờ ở đâu?", "/app").get(0).id());
    }
}
