package vn.nutrimom.assistant.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.assistant.dto.AssistantDtos.*;
import vn.nutrimom.knowledge.domain.*;
import vn.nutrimom.knowledge.repository.KnowledgeArticleRepository;
import vn.nutrimom.medicalrecord.repository.MedicalRecordRepository;
import vn.nutrimom.medicalrecord.domain.MedicalRecordCategory;
import vn.nutrimom.medicalrecord.domain.MedicalRecordEntity;
import vn.nutrimom.pregnancy.service.PregnancyService;
import vn.nutrimom.user.service.UserProfileService;

@Service
public class AssistantContextService {
    private static final String VIETNAMESE_DIACRITICS = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ";
    private static final String VIETNAMESE_ASCII = AssistantText.fold(VIETNAMESE_DIACRITICS);
    public record Evidence(Source source, String text) { }
    public record Context(List<Evidence> evidence, List<Action> actions, List<String> used,
                          Map<String, Object> facts, List<String> missing) { }
    private final WebsiteGuideCatalog guides;
    private final UserProfileService profiles;
    private final PregnancyService pregnancies;
    private final MedicalRecordRepository records;
    private final KnowledgeArticleRepository articles;
    private final AssistantAccountContextService accountContext;

    public AssistantContextService(WebsiteGuideCatalog guides, UserProfileService profiles,
            PregnancyService pregnancies, MedicalRecordRepository records, KnowledgeArticleRepository articles,
            AssistantAccountContextService accountContext) {
        this.guides = guides; this.profiles = profiles; this.pregnancies = pregnancies;
        this.records = records; this.articles = articles; this.accountContext = accountContext;
    }

    @Transactional(readOnly = true)
    public Context build(String userId, Preferences preferences, String question, String pagePath) {
        List<Evidence> evidence = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        List<String> used = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, Object> facts = new LinkedHashMap<>();
        for (var guide : guides.search(question, pagePath)) {
            evidence.add(new Evidence(guide.source(), guide.text())); actions.add(guide.action());
        }
        used.add("WEBSITE_GUIDE");
        if (preferences.useProfile()) {
            var profile = profiles.getProfile(userId);
            Map<String, Object> basic = new LinkedHashMap<>();
            basic.put("role", profile.role());
            if (profile.gender() != null) basic.put("gender", profile.gender());
            basic.put("onboarding_status", profile.onboardingStatus());
            if (profile.dateOfBirth() != null) basic.put("age_years", Period.between(profile.dateOfBirth(), LocalDate.now(ZoneOffset.UTC)).getYears());
            facts.put("profile", basic); used.add("PROFILE");
            evidence.add(new Evidence(new Source("profile:me", "PROFILE", "Hồ sơ cá nhân của bạn", "/app/profile", "Vai trò và tuổi được lấy từ hồ sơ đã lưu."), basic.toString()));
            var activity = accountContext.account(userId, question);
            facts.putAll(activity.facts()); evidence.addAll(activity.evidence()); used.addAll(activity.used());
        }
        String stage = null;
        if (preferences.usePregnancy()) {
            var current = pregnancies.findCurrent(userId);
            if (current.isPresent()) {
                var pregnancy = current.get();
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("gestational_week", pregnancy.gestationalWeek());
                value.put("gestational_day", pregnancy.gestationalDay());
                value.put("trimester", pregnancy.trimester());
                value.put("days_until_due", pregnancy.daysUntilDue());
                if (pregnancy.isFirstPregnancy() != null) value.put("first_pregnancy", pregnancy.isFirstPregnancy());
                if (pregnancy.multiplePregnancy() != null) value.put("multiple_pregnancy", pregnancy.multiplePregnancy());
                value.put("calculation_source", pregnancy.calculationSource());
                value.put("estimated_due_date", String.valueOf(pregnancy.estimatedDueDate()));
                value.put("updated_at", pregnancy.updatedAt().toString());
                facts.put("pregnancy", value); used.add("PREGNANCY");
                stage = "trimester-" + pregnancy.trimester();
                evidence.add(new Evidence(new Source("pregnancy:current", "PREGNANCY", "Thai kỳ hiện tại của bạn", "/app/profile/health", "Tuần " + pregnancy.gestationalWeek() + ", ngày " + pregnancy.gestationalDay() + " theo hồ sơ đã lưu."), value.toString()));
                var care = accountContext.care(pregnancy.id());
                facts.putAll(care.facts()); evidence.addAll(care.evidence()); used.addAll(care.used());
            } else {
                missing.add("Chưa có thai kỳ đang hoạt động trong tài khoản.");
            }
        }
        if (preferences.useMedicalRecords()) {
            long total = records.countByOwnerUserIdAndDeletedAtIsNull(userId);
            facts.put("medical_records", Map.of("total", total));
            used.add("MEDICAL_RECORDS");
            evidence.add(new Evidence(new Source("records:overview", "MEDICAL_RECORD", "Hồ sơ y tế của bạn", "/app/profile/records",
                    "Có " + total + " hồ sơ y tế đang được lưu."), "Có " + total + " hồ sơ y tế đang được lưu. Đây là số hồ sơ, không phải kết quả chẩn đoán."));
        }
        if (preferences.useMedicalRecords() && AssistantText.contains(question, "ho so", "ket qua", "kham", "suc khoe", "xet nghiem", "sieu am", "tom tat", "biet gi ve toi", "dinh duong", "an uong", "di ung", "benh", "duong huyet", "huyet ap", "thuoc")) {
            var recent = searchRecords(userId, question);
            for (var record : recent) {
                String date = record.getOccurredAt().atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                String text = "Ngày: " + date + ". Loại: " + medicalCategoryLabel(record.getCategory())
                        + ". Tóm tắt do người dùng lưu: " + AssistantText.clean(record.getSummary(), 450);
                evidence.add(new Evidence(new Source("record:" + record.getId(), "MEDICAL_RECORD",
                        AssistantText.clean(record.getTitle(), 100), "/app/profile/records", text), text));
            }
            if (recent.isEmpty()) missing.add("Chưa có hồ sơ y tế đã lưu.");
        }
        List<KnowledgeArticle> found = searchArticles(question, stage);
        for (var article : found) {
            StringBuilder text = new StringBuilder(Optional.ofNullable(article.getLead()).orElse(Optional.ofNullable(article.getExcerpt()).orElse("")));
            for (var section : article.getSections()) {
                if (text.length() > 800) break;
                text.append(" ").append(section.getHeading()).append(" ").append(section.getParagraphs()).append(" ").append(section.getBullets());
            }
            String excerpt = AssistantText.clean(text.toString(), 850);
            if (article.getSlug().matches("[\\p{L}\\p{N}_-]+")) {
                evidence.add(new Evidence(new Source("article:" + article.getId(), "ARTICLE", article.getTitle(), "/app/knowledge/" + article.getSlug(), excerpt), excerpt));
            }
        }
        if (!found.isEmpty()) used.add("PUBLISHED_ARTICLES");
        var map = guides.websiteMap();
        evidence.add(new Evidence(map, map.excerpt()));
        facts.put("website_knowledge_version", guides.version());
        return new Context(List.copyOf(evidence), List.copyOf(actions), List.copyOf(used), Map.copyOf(facts), List.copyOf(missing));
    }

    @Transactional(readOnly = true)
    public UserOverview overview(String userId, Preferences preferences) {
        var context = build(userId, preferences, "Tóm tắt thông tin của tôi", "/app/assistant");
        String displayName = preferences.useProfile() ? profiles.getProfile(userId).displayName() : null;
        List<ContextItem> items = new ArrayList<>();
        if (context.facts().containsKey("profile")) items.add(new ContextItem("profile", "Tài khoản", "Hồ sơ và hoạt động đã được đồng bộ", "/app/profile"));
        String headline = "Đã nhận diện tài khoản của bạn";
        if (context.facts().get("pregnancy") instanceof Map<?, ?> pregnancy) {
            headline = "Thai kỳ tuần " + pregnancy.get("gestational_week") + " · ngày " + pregnancy.get("gestational_day");
            items.add(new ContextItem("pregnancy", "Thai kỳ", headline, "/app/profile/health"));
        }
        if (context.facts().get("medical_records") instanceof Map<?, ?> medical)
            items.add(new ContextItem("medical", "Hồ sơ y tế", medical.get("total") + " hồ sơ đã lưu", "/app/profile/records"));
        if (context.facts().get("care_plan") instanceof Map<?, ?> care)
            items.add(new ContextItem("care", "Chuẩn bị", ((Number) care.get("checklist_total")).intValue() == 0 ? "Chưa tạo danh sách chuẩn bị" : care.get("checklist_completed") + "/" + care.get("checklist_total") + " mục đã hoàn thành", "/app/profile/care"));
        if (context.facts().get("subscription") instanceof Map<?, ?> subscription)
            items.add(new ContextItem("subscription", "Gói dịch vụ", String.valueOf(subscription.get("plan_name")), "/app/pricing"));
        if (context.facts().get("consultations") instanceof Map<?, ?> consultations)
            items.add(new ContextItem("consultations", "Tư vấn", consultations.get("total") + " yêu cầu tư vấn", "/app/consultations/history"));
        if (context.facts().get("saved_articles") instanceof Map<?, ?> saved)
            items.add(new ContextItem("saved", "Kiến thức", saved.get("total") + " bài viết đã lưu", "/app/profile/saved"));
        if (context.facts().get("family") instanceof Map<?, ?> family)
            items.add(new ContextItem("family", "Gia đình", family.get("active_groups") + " nhóm đang tham gia", "/app/family"));
        if (context.facts().get("support") instanceof Map<?, ?> support)
            items.add(new ContextItem("support", "Hỗ trợ", support.get("pending_requests") + " yêu cầu chờ xử lý", "/app/profile/support"));
        List<String> suggestions = new ArrayList<>();
        suggestions.add("Bạn biết gì về thông tin của tôi?");
        suggestions.add(context.facts().containsKey("pregnancy") ? "Gợi ý bài viết dinh dưỡng phù hợp với thai kỳ của tôi" : "Tôi nên bắt đầu sử dụng NutriMom như thế nào?");
        suggestions.add("Tôi nên làm gì tiếp theo trên NutriMom?");
        return new UserOverview(displayName, headline, List.copyOf(items), List.copyOf(suggestions), preferences.version(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** Identity/contact answers stay in NutriMom; they never go through the cloud provider. */
    @Transactional(readOnly = true)
    public Optional<AssistantAnswers.Answer> identity(String userId, Preferences preferences, String question) {
        if (!preferences.useProfile() || !AssistantText.contains(question, "toi la ai", "ten cua toi", "ten toi", "email cua toi", "so dien thoai cua toi", "ngay sinh cua toi")) return Optional.empty();
        var profile = profiles.getProfile(userId);
        String content = "Tên trong hồ sơ của bạn là " + profile.displayName() + ".";
        if (AssistantText.contains(question, "email")) content = profile.email() == null ? "Bạn chưa lưu email trong hồ sơ." : "Email đã lưu của bạn: " + profile.email() + ".";
        else if (AssistantText.contains(question, "so dien thoai")) content = "Số điện thoại đăng nhập của bạn: " + profile.phone() + ".";
        else if (AssistantText.contains(question, "ngay sinh")) content = profile.dateOfBirth() == null ? "Bạn chưa lưu ngày sinh." : "Ngày sinh đã lưu của bạn: " + profile.dateOfBirth().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".";
        return Optional.of(new AssistantAnswers.Answer(content, List.of(guides.get("guide:profile").source()),
                List.of(guides.get("guide:profile").action()), List.of("PROFILE", "LOCAL_IDENTITY"), null, false, false, "GUIDE", null));
    }

    private List<MedicalRecordEntity> searchRecords(String userId, String question) {
        Set<String> stop = Set.of("tom", "tat", "ho", "so", "y", "te", "cua", "toi", "gan", "day", "nhat", "ca", "thong", "tin", "biet", "gi", "ve", "ket", "qua", "kham", "dinh", "duong", "bai", "viet", "va", "duoc", "nhu", "the", "nao", "cho", "luu", "mot");
        List<String> words = Arrays.stream(AssistantText.fold(question).split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2 && !stop.contains(w)).distinct().limit(5).toList();
        Specification<MedicalRecordEntity> owned = (root, query, cb) -> cb.and(cb.equal(root.get("ownerUserId"), userId), cb.isNull(root.get("deletedAt")));
        if (!words.isEmpty()) {
            Specification<MedicalRecordEntity> matching = (root, query, cb) -> {
                List<jakarta.persistence.criteria.Predicate> terms = new ArrayList<>();
                for (var word : words) {
                    terms.add(cb.like(folded(cb, root.get("title")), "%" + word + "%"));
                    terms.add(cb.like(folded(cb, root.get("summary")), "%" + word + "%"));
                }
                return cb.or(terms.toArray(jakarta.persistence.criteria.Predicate[]::new));
            };
            var found = records.findAll(owned.and(matching), PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "occurredAt", "id"))).getContent();
            if (!found.isEmpty()) return found;
        }
        return records.findTop3ByOwnerUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(userId);
    }

    private String medicalCategoryLabel(MedicalRecordCategory category) {
        return switch (category) {
            case PRENATAL_VISIT -> "Khám thai";
            case ULTRASOUND -> "Siêu âm";
            case LAB_RESULT -> "Kết quả xét nghiệm";
            case PRESCRIPTION -> "Đơn thuốc";
            case DISCHARGE -> "Giấy ra viện";
            case OTHER -> "Hồ sơ khác";
        };
    }

    private List<KnowledgeArticle> searchArticles(String question, String stage) {
        boolean nutrition = AssistantText.contains(question, "dinh duong", "an uong", "bua an", "thuc don", "calo", "protein");
        if (!nutrition && !AssistantText.contains(question, "bai viet", "kien thuc", "blog", "suc khoe", "thai", "ho so", "tom tat")) return List.of();
        Set<String> stop = Set.of("toi", "cua", "cho", "nhung", "duoc", "nhu", "the", "nao", "phu", "hop", "hien", "tai", "can", "biet", "muon", "giup", "voi", "trong");
        List<String> words = Arrays.stream(AssistantText.fold(question).split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2 && !stop.contains(w)).distinct().limit(6).toList();
        Specification<KnowledgeArticle> visible = (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), ArticleStatus.published), cb.lessThanOrEqualTo(root.get("publishedAt"), OffsetDateTime.now(ZoneOffset.UTC)));
        Specification<KnowledgeArticle> relevant = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> matches = new ArrayList<>();
            if (stage != null) matches.add(cb.equal(root.get("stage"), stage));
            for (String word : words) {
                matches.add(cb.like(folded(cb, root.get("title")), "%" + word + "%"));
                matches.add(cb.like(folded(cb, root.get("excerpt")), "%" + word + "%"));
            }
            if (query != null && query.getResultType() != Long.class) {
                var priority = cb.<Integer>selectCase();
                if (stage != null) priority.when(cb.equal(root.get("stage"), stage), 2);
                priority.when(cb.equal(root.get("stage"), "pregnancy"), 1).otherwise(0);
                query.orderBy(cb.desc(priority), cb.desc(root.get("publishedAt")), cb.asc(root.get("id")));
            }
            return nutrition ? cb.equal(root.get("category"), "nutrition")
                    : matches.isEmpty() ? cb.disjunction() : cb.or(matches.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return articles.findAll(visible.and(relevant), PageRequest.of(0, 3)).getContent();
    }
    private Expression<String> folded(CriteriaBuilder cb, Expression<String> value) {
        // PostgreSQL and H2 both support translate; no database extension is needed.
        return cb.function("translate", String.class, cb.lower(value), cb.literal(VIETNAMESE_DIACRITICS), cb.literal(VIETNAMESE_ASCII));
    }
}
