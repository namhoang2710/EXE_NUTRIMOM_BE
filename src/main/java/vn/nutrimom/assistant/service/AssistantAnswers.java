package vn.nutrimom.assistant.service;

import java.util.*;
import org.springframework.stereotype.Component;
import vn.nutrimom.assistant.dto.AssistantDtos.*;

@Component
public class AssistantAnswers {
    public static final String HEALTH_NOTICE = "Thông tin này giúp bạn tham khảo và hiểu hồ sơ đã lưu, không thay thế đánh giá của người có chuyên môn.";
    public record Answer(String content, List<Source> citations, List<Action> actions, List<String> used,
                         String safetyNotice, boolean escalation, boolean emergency, String mode, String reason) { }
    private final WebsiteGuideCatalog guides;
    public AssistantAnswers(WebsiteGuideCatalog guides) { this.guides = guides; }

    public Optional<Answer> safety(String question) {
        var expert = guides.get("guide:experts");
        if (AssistantText.contains(question, "ra mau nhieu", "kho tho", "dau nguc", "co giat", "bat tinh", "tu tu", "tu lam hai")) {
            return Optional.of(new Answer("Nếu bạn hoặc người bên cạnh đang có dấu hiệu nguy hiểm, hãy liên hệ cấp cứu hoặc cơ sở y tế gần nhất ngay. Đừng chờ câu trả lời trong chat hoặc một lịch tư vấn trên web. Trợ lý không thể đánh giá mức độ nguy hiểm qua tin nhắn.", List.of(), List.of(), List.of(), HEALTH_NOTICE, true, true, "SAFETY", null));
        }
        if (AssistantText.contains(question, "tai khoan khac", "nguoi dung khac", "user_id", "userid", "toan bo database", "tat ca tai khoan")) {
            return Optional.of(new Answer("Mình chỉ hỗ trợ dữ liệu được phép sử dụng của tài khoản đang đăng nhập. Bạn có thể hỏi cách quản lý quyền chia sẻ trong mục Gia đình.", List.of(guides.get("guide:family").source()), List.of(guides.get("guide:family").action()), List.of("WEBSITE_GUIDE"), null, false, false, "GUIDE", null));
        }
        if (AssistantText.contains(question, "ke don", "tang lieu", "giam lieu", "uống thuốc gì", "uong thuoc gi", "chan doan", "thuc don dieu tri")) {
            return Optional.of(new Answer("Mình có thể giúp bạn tìm bài viết và tóm tắt nội dung đã lưu. Việc chẩn đoán, chọn thuốc, thay đổi liều hoặc xây dựng thực đơn điều trị cần người có chuyên môn đánh giá trực tiếp. Bạn có thể mở danh sách chuyên gia để được hỗ trợ.", List.of(expert.source()), List.of(expert.action()), List.of("WEBSITE_GUIDE"), HEALTH_NOTICE, true, false, "SAFETY", null));
        }
        return Optional.empty();
    }

    public Optional<Answer> personal(AssistantContextService.Context context, String question) {
        boolean overview = AssistantText.contains(question, "biet gi ve toi", "thong tin cua toi", "hieu gi ve toi", "tom tat tai khoan", "tom tat toan bo");
        boolean next = AssistantText.contains(question, "nen lam gi tiep", "buoc tiep theo tren", "bat dau su dung nutrimom");
        if (!overview && !next) return Optional.empty();
        StringBuilder text = new StringBuilder(overview ? "Mình đã nắm được các thông tin đang lưu trong tài khoản của bạn:\n" : "Dựa trên thông tin đang lưu, bạn có thể tiếp tục như sau:\n");
        List<Action> actions = new ArrayList<>();
        if (context.facts().get("profile") instanceof Map<?, ?> profile && overview) {
            text.append("• Hồ sơ cá nhân: ");
            if (profile.containsKey("age_years")) text.append(profile.get("age_years")).append(" tuổi, ");
            text.append("vai trò ").append(roleLabel(profile.get("role"))).append(".\n");
        }
        if (context.facts().get("pregnancy") instanceof Map<?, ?> pregnancy) {
            if (overview) text.append("• Thai kỳ: tuần ").append(pregnancy.get("gestational_week")).append(", ngày ").append(pregnancy.get("gestational_day"))
                    .append("; dự sinh ").append(pregnancy.get("estimated_due_date")).append(".\n");
            else text.append("• Đọc nội dung phù hợp với tuần thai ").append(pregnancy.get("gestational_week")).append(" của bạn trong Kiến thức.\n");
        } else if (next) text.append("• Nếu đang mang thai, bạn có thể tạo hồ sơ ở Thai kỳ & sức khỏe; nếu chưa, hãy bắt đầu với Kiến thức và hồ sơ cá nhân.\n");
        if (context.facts().get("medical_records") instanceof Map<?, ?> medical) {
            if (overview) text.append("• Hồ sơ y tế: ").append(medical.get("total")).append(" hồ sơ đã lưu.\n");
            else text.append("• Xem lại ").append(medical.get("total")).append(" hồ sơ đang có, hoặc lưu kết quả lần khám mới vào Hồ sơ y tế.\n");
            actions.add(guides.get("guide:records").action());
        }
        if (context.facts().get("care_plan") instanceof Map<?, ?> care) {
            if (overview) text.append("• Danh sách chuẩn bị: ").append(care.get("checklist_completed")).append("/").append(care.get("checklist_total")).append(" mục đã hoàn thành; kế hoạch sinh ").append(Boolean.TRUE.equals(care.get("birth_plan_saved")) ? "đã được lưu" : "chưa được lưu").append(".\n");
            else {
                @SuppressWarnings("unchecked") var items = (List<String>) care.get("next_items");
                text.append("• ").append(items.isEmpty() ? "Mở Kế hoạch chăm sóc để kiểm tra danh sách chuẩn bị và kế hoạch sinh." : "Tiếp tục các mục chuẩn bị chưa xong: " + String.join("; ", items.stream().limit(3).toList()) + ".").append("\n");
                actions.add(guides.get("guide:care").action());
            }
        }
        if (context.facts().get("subscription") instanceof Map<?, ?> subscription && overview)
            text.append("• Gói dịch vụ: ").append(subscription.get("plan_name")).append(subscription.containsKey("end_date") ? ", hết hạn " + subscription.get("end_date") : "").append(".\n");
        if (context.facts().get("consultations") instanceof Map<?, ?> consultations && overview)
            text.append("• Lịch tư vấn: ").append(consultations.get("total")).append(" yêu cầu đã tạo; có thể xem trạng thái trong Lịch sử tư vấn.\n");
        if (context.facts().get("saved_articles") instanceof Map<?, ?> saved && overview)
            text.append("• Kiến thức: ").append(saved.get("total")).append(" bài viết đã lưu.\n");
        if (context.facts().get("support") instanceof Map<?, ?> support && overview)
            text.append("• Hỗ trợ: ").append(support.get("pending_requests")).append(" yêu cầu đang chờ xử lý.\n");
        if (context.facts().get("family") instanceof Map<?, ?> family && overview)
            text.append("• Gia đình: ").append(family.get("active_groups")).append(" nhóm đang tham gia.\n");
        var sources = context.evidence().stream().map(e -> e.source()).filter(s -> !Set.of("GUIDE", "ARTICLE").contains(s.type())).toList();
        if (sources.isEmpty()) text.append("Bạn đang tắt các nguồn dữ liệu cá nhân. Mở Cá nhân hóa để cho phép trợ lý đọc thông tin của tài khoản.\n");
        else if (overview) text.append("\nBạn không cần nhập lại các thông tin đã có. Mình sẽ đối chiếu dữ liệu mới khi trả lời; có thể hỏi tiếp về hồ sơ, dinh dưỡng, lịch tư vấn hoặc cách dùng web.");
        actions.add(guides.get("guide:knowledge").action());
        return Optional.of(new Answer(text.toString(), sources, actions.stream().limit(3).toList(), context.used(), null, false, false, "GUIDE", null));
    }

    public Answer guide(AssistantContextService.Context context, String question, String reason) {
        StringBuilder content = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        if (context.facts().containsKey("pregnancy") && AssistantText.contains(question, "thai", "tuan", "suc khoe", "tom tat")) {
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) context.facts().get("pregnancy");
            content.append("Theo hồ sơ đã lưu, thai kỳ hiện tại ở tuần ").append(value.get("gestational_week"))
                .append(", ngày ").append(value.get("gestational_day")).append(". Ngày dự sinh đã lưu: ").append(value.get("estimated_due_date")).append(".\n\n");
            context.evidence().stream().filter(e -> e.source().type().equals("PREGNANCY")).forEach(e -> sources.add(e.source()));
        }
        if (context.facts().containsKey("profile") && AssistantText.contains(question, "ca nhan", "tai khoan", "tuoi", "ho so")) {
            @SuppressWarnings("unchecked") Map<String, Object> basic = (Map<String, Object>) context.facts().get("profile");
            content.append("Hồ sơ tài khoản: vai trò ").append(roleLabel(basic.get("role")));
            if (basic.containsKey("age_years")) content.append(", tuổi ").append(basic.get("age_years"));
            content.append(".\n\n");
            context.evidence().stream().filter(e -> e.source().type().equals("PROFILE")).forEach(e -> sources.add(e.source()));
        }
        var medical = context.evidence().stream().filter(e -> e.source().type().equals("MEDICAL_RECORD") && !e.source().id().equals("records:overview")).toList();
        if (!medical.isEmpty()) {
            content.append("Các hồ sơ phù hợp với câu hỏi của bạn:\n");
            for (var item : medical) { content.append("• ").append(item.source().title()).append(": ").append(item.text()).append("\n"); sources.add(item.source()); }
            content.append("\n");
        }
        if (AssistantText.contains(question, "thai", "suc khoe", "ho so", "ket qua", "tom tat"))
            for (String missing : context.missing()) content.append(missing).append("\n");
        if (context.facts().get("subscription") instanceof Map<?, ?> subscription && AssistantText.contains(question, "goi", "thanh toan", "gia han")) {
            content.append("Gói hiện tại của bạn: ").append(subscription.get("plan_name")).append(".\n\n");
            context.evidence().stream().filter(e -> e.source().type().equals("SUBSCRIPTION")).forEach(e -> sources.add(e.source()));
        }
        if (context.facts().get("consultations") instanceof Map<?, ?> consultations && AssistantText.contains(question, "lich", "tu van")) {
            content.append("Bạn đã tạo ").append(consultations.get("total")).append(" yêu cầu tư vấn.\n");
            if (consultations.get("recent") instanceof List<?> recent) {
                for (Object value : recent) if (value instanceof Map<?, ?> item) {
                    content.append("• ").append(item.get("specialty")).append(" — ").append(item.get("status"));
                    if (item.containsKey("date")) content.append(", ").append(item.get("date")).append(" lúc ").append(item.get("start_time"));
                    content.append(".\n");
                }
            }
            content.append("\n");
            context.evidence().stream().filter(e -> e.source().type().equals("CONSULTATION")).forEach(e -> sources.add(e.source()));
        }
        if (context.facts().get("saved_articles") instanceof Map<?, ?> saved && AssistantText.contains(question, "da luu", "bookmark")) {
            content.append("Bạn có ").append(saved.get("total")).append(" bài đã lưu.\n");
            if (saved.get("recent_titles") instanceof List<?> titles)
                for (Object title : titles) content.append("• ").append(title).append("\n");
            content.append("\n");
            context.evidence().stream().filter(e -> e.source().type().equals("BOOKMARK")).forEach(e -> sources.add(e.source()));
        }
        var first = context.evidence().stream().filter(e -> e.source().type().equals("GUIDE")).findFirst().orElseThrow();
        content.append(first.text()); sources.add(first.source());
        var articles = context.evidence().stream().filter(e -> e.source().type().equals("ARTICLE")).toList();
        if (!articles.isEmpty()) {
            content.append("\n\nBài viết bạn có thể đọc thêm:");
            for (var article : articles) { content.append("\n• ").append(article.source().title()); sources.add(article.source()); }
        }
        String notice = !medical.isEmpty() || AssistantText.contains(question, "dinh duong", "thai", "suc khoe") ? HEALTH_NOTICE : null;
        return new Answer(content.toString(), List.copyOf(sources), context.actions().stream().limit(2).toList(), context.used(), notice, false, false, "GUIDE", reason);
    }
    public Answer fromAi(AiProviderClient.Completion completion, AssistantContextService.Context context, String question) {
        List<Source> sources = context.evidence().stream().map(e -> e.source()).filter(s -> completion.sourceIds().contains(s.id())).toList();
        List<Action> actions = context.actions().stream().filter(a -> completion.actionIds().contains(a.id())).toList();
        if (sources.size() != new HashSet<>(completion.sourceIds()).size() || sources.isEmpty()
                || actions.size() != new HashSet<>(completion.actionIds()).size() || completion.content() == null
                || completion.content().isBlank() || completion.content().length() > 6000) throw new AiProviderClient.ProviderFailure("INVALID_RESPONSE");
        return new Answer(completion.content(), sources, actions, context.used(),
                sources.stream().anyMatch(s -> s.type().equals("MEDICAL_RECORD") && !s.id().equals("records:overview"))
                        || AssistantText.contains(question, "thai", "suc khoe", "dinh duong") ? HEALTH_NOTICE : null,
                completion.escalationRecommended(), false, "AI", null);
    }
    private String roleLabel(Object role) {
        return switch (String.valueOf(role)) {
            case "MOM" -> "mẹ";
            case "FAMILY", "FAMILY_MEMBER" -> "người thân";
            case "PARTNER" -> "người đồng hành";
            default -> "thành viên NutriMom";
        };
    }
}
