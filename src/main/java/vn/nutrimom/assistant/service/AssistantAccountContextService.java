package vn.nutrimom.assistant.service;

import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.assistant.dto.AssistantDtos.Source;
import vn.nutrimom.assistant.service.AssistantContextService.Evidence;
import vn.nutrimom.care.repository.*;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.repository.*;
import vn.nutrimom.contact.domain.ContactRequestStatus;
import vn.nutrimom.contact.repository.ContactRequestRepository;
import vn.nutrimom.family.domain.*;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.knowledge.domain.ArticleStatus;
import vn.nutrimom.knowledge.repository.ArticleBookmarkRepository;
import vn.nutrimom.payment.domain.*;
import vn.nutrimom.payment.repository.*;

/** Read-only account context. Every private root query is bound to the JWT owner. */
@Service
@Transactional(readOnly = true)
public class AssistantAccountContextService {
    public record Data(Map<String, Object> facts, List<Evidence> evidence, List<String> used) { }
    private final UserSubscriptionRepository subscriptions;
    private final PaymentOrderRepository payments;
    private final ConsultationRequestRepository consultations;
    private final AvailabilitySlotRepository slots;
    private final ArticleBookmarkRepository bookmarks;
    private final ContactRequestRepository support;
    private final FamilyMemberRepository family;
    private final PreparationItemRepository preparation;
    private final BirthPlanRepository birthPlans;
    public AssistantAccountContextService(UserSubscriptionRepository subscriptions, PaymentOrderRepository payments,
            ConsultationRequestRepository consultations, AvailabilitySlotRepository slots, ArticleBookmarkRepository bookmarks,
            ContactRequestRepository support, FamilyMemberRepository family, PreparationItemRepository preparation, BirthPlanRepository birthPlans) {
        this.subscriptions = subscriptions; this.payments = payments; this.consultations = consultations; this.slots = slots;
        this.bookmarks = bookmarks; this.support = support; this.family = family; this.preparation = preparation; this.birthPlans = birthPlans;
    }
    public Data account(String userId, String question) {
        Map<String, Object> facts = new LinkedHashMap<>();
        List<Evidence> evidence = new ArrayList<>(); List<String> used = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var current = subscriptions.findCurrentForAssistant(userId, SubscriptionStatus.ACTIVE, now, PageRequest.of(0, 1));
        var subscription = new LinkedHashMap<String, Object>();
        subscription.put("plan_name", current.isEmpty() ? PlanTier.FREE.getDisplayName() : current.get(0).getPlanTier().getDisplayName());
        subscription.put("paid_active", !current.isEmpty());
        if (!current.isEmpty()) subscription.put("end_date", current.get(0).getEndDate().toLocalDate().toString());
        facts.put("subscription", subscription); used.add("SUBSCRIPTION");
        evidence.add(evidence("account:subscription", "SUBSCRIPTION", "Gói dịch vụ của bạn", "/app/pricing", subscription));

        var recent = consultations.findTop5ByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> history = new ArrayList<>();
        var slotIds = recent.stream().map(ConsultationRequestEntity::getSlotId).filter(Objects::nonNull).toList();
        Map<String, AvailabilitySlotEntity> ownedSlots = new HashMap<>();
        for (var slot : slots.findAllById(slotIds)) ownedSlots.put(slot.getId(), slot);
        for (var request : recent) {
            var item = new LinkedHashMap<String, Object>();
            item.put("specialty", switch (request.getSpecialty()) {
                case PSYCHOLOGY -> "Tâm lý";
                case OBSTETRICS -> "Sản khoa";
                case HEALTH -> "Sức khỏe";
            });
            item.put("status", consultationStatus(request.getStatus()));
            var slot = ownedSlots.get(request.getSlotId());
            if (slot != null) { item.put("date", slot.getSlotDate().toString()); item.put("start_time", slot.getStartTime().toString()); }
            history.add(item);
        }
        var consultation = Map.<String, Object>of("total", consultations.countByUserId(userId), "recent", history);
        facts.put("consultations", consultation); used.add("CONSULTATIONS");
        evidence.add(evidence("account:consultations", "CONSULTATION", "Lịch tư vấn của bạn", "/app/consultations/history", consultation));

        long savedCount = bookmarks.countVisibleForAssistant(userId, ArticleStatus.published, now);
        List<String> savedTitles = bookmarks.findVisibleForAssistant(userId, ArticleStatus.published, now, PageRequest.of(0, 5))
                .stream().map(b -> AssistantText.clean(b.getArticle().getTitle(), 100)).toList();
        var saved = Map.<String, Object>of("total", savedCount, "recent_titles", savedTitles);
        facts.put("saved_articles", saved); used.add("SAVED_ARTICLES");
        evidence.add(evidence("account:saved", "BOOKMARK", "Bài viết bạn đã lưu", "/app/profile/saved", saved));
        long pending = support.countByUserIdAndStatus(userId, ContactRequestStatus.PENDING);
        facts.put("support", Map.of("pending_requests", pending)); used.add("SUPPORT");
        evidence.add(evidence("account:support", "SUPPORT", "Yêu cầu hỗ trợ của bạn", "/app/profile/support", Map.of("pending_requests", pending)));
        long groups = family.countActiveGroupsForAssistant(userId, FamilyMemberStatus.ACTIVE, FamilyGroupStatus.ACTIVE);
        facts.put("family", Map.of("active_groups", groups)); used.add("FAMILY");
        evidence.add(evidence("account:family", "FAMILY", "Gia đình của bạn", "/app/family", Map.of("active_groups", groups)));
        if (AssistantText.contains(question, "thanh toan", "don hang", "giao dich", "tom tat", "thong tin cua toi", "biet gi ve toi")) {
            var orders = payments.findTop3ByUserIdOrderByCreatedAtDesc(userId).stream()
                    .map(p -> Map.<String, Object>of("plan", p.getPlanTier().getDisplayName(), "status", p.getStatus().name(),
                            "amount_vnd", p.getAmount(), "created_date", p.getCreatedAt().toLocalDate().toString())).toList();
            facts.put("payments", orders); used.add("PAYMENTS");
            evidence.add(evidence("account:payments", "PAYMENT", "Giao dịch gần đây của bạn", "/app/pricing", orders));
        }
        return new Data(Map.copyOf(facts), List.copyOf(evidence), List.copyOf(used));
    }
    /** Pregnancy id must come from an owner-scoped PregnancyService result, never from the chat request. */
    public Data care(String verifiedPregnancyId) {
        var items = preparation.findByPregnancyIdOrderBySortOrderAsc(verifiedPregnancyId);
        var plan = birthPlans.findByPregnancyId(verifiedPregnancyId);
        Map<String, Object> care = new LinkedHashMap<>();
        care.put("checklist_total", items.size()); care.put("checklist_completed", items.stream().filter(i -> i.isCompleted()).count());
        care.put("next_items", items.stream().filter(i -> !i.isCompleted()).limit(5).map(i -> AssistantText.clean(i.getTitle(), 130)).toList());
        care.put("birth_plan_saved", plan.isPresent());
        plan.ifPresent(p -> {
            care.put("pain_management_note", AssistantText.clean(p.getPainManagementNote(), 250));
            care.put("newborn_care_note", AssistantText.clean(p.getNewbornCareNote(), 250));
            care.put("personal_note", AssistantText.clean(p.getFreeTextNote(), 250));
        });
        return new Data(Map.of("care_plan", care), List.of(evidence("pregnancy:care", "CARE_PLAN", "Kế hoạch chăm sóc của bạn", "/app/profile/care", care)), List.of("CARE_PLAN"));
    }
    private Evidence evidence(String id, String type, String title, String href, Object value) {
        String text = AssistantText.clean(value.toString(), 1800);
        Map<?, ?> facts = value instanceof Map<?, ?> map ? map : Map.of();
        String excerpt = switch (type) {
            case "SUBSCRIPTION" -> "Gói hiện tại: " + facts.get("plan_name") + ".";
            case "CONSULTATION" -> "Có " + facts.get("total") + " yêu cầu tư vấn trong tài khoản; đối chiếu trạng thái tại Lịch sử tư vấn.";
            case "BOOKMARK" -> "Có " + facts.get("total") + " bài viết đã lưu và đang được công bố.";
            case "SUPPORT" -> "Có " + facts.get("pending_requests") + " yêu cầu hỗ trợ đang chờ xử lý.";
            case "FAMILY" -> "Bạn đang tham gia " + facts.get("active_groups") + " nhóm gia đình đang hoạt động.";
            case "CARE_PLAN" -> "Đã hoàn thành " + facts.get("checklist_completed") + "/" + facts.get("checklist_total") + " mục chuẩn bị. Kế hoạch sinh " + (Boolean.TRUE.equals(facts.get("birth_plan_saved")) ? "đã lưu." : "chưa được lưu.");
            case "PAYMENT" -> "Trạng thái các giao dịch gần đây được lấy từ tài khoản của bạn.";
            default -> text;
        };
        return new Evidence(new Source(id, type, title, href, excerpt), text);
    }
    private String consultationStatus(ConsultationStatus status) {
        return switch (status) {
            case PENDING_EXPERT -> "Đang chờ chuyên gia";
            case PENDING_CONSULTATION -> "Đang chờ tư vấn";
            case COMPLETED -> "Đã tư vấn";
            case CANCELLED -> "Đã hủy";
        };
    }
}
