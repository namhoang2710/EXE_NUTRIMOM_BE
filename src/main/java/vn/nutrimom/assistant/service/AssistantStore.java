package vn.nutrimom.assistant.service;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.assistant.config.AssistantProperties;
import vn.nutrimom.assistant.dto.AssistantDtos.*;
import vn.nutrimom.assistant.persistence.*;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.*;

/** Short database transactions; provider requests always happen outside this service. */
@Service
public class AssistantStore {
    public record Prepared(String conversationId, String clientMessageId, Preferences preferences,
                           List<Message> history, Message userMessage, Reply replay) { }
    private final UserRepository users;
    private final AssistantPreferencesRepository preferences;
    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final AssistantQuotaRepository quotas;
    private final AssistantProperties properties;
    private final ObjectMapper json;

    public AssistantStore(UserRepository users, AssistantPreferencesRepository preferences,
            AssistantConversationRepository conversations, AssistantMessageRepository messages,
            AssistantQuotaRepository quotas, AssistantProperties properties, ObjectMapper json) {
        this.users = users; this.preferences = preferences; this.conversations = conversations;
        this.messages = messages; this.quotas = quotas; this.properties = properties; this.json = json;
    }
    @Transactional
    public void initializeQuota() {
        if (!quotas.existsById("global")) quotas.saveAndFlush(new AssistantQuota());
    }
    @Transactional
    public Preferences preferences(String userId) { lockUser(userId); return preferenceDto(prefs(userId)); }
    @Transactional
    public Preferences updatePreferences(String userId, UpdatePreferences request) {
        lockUser(userId);
        var p = prefs(userId);
        if (p.contextVersion != request.version()) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        if (p.cloudConsent != request.cloudConsent() || p.useProfile != request.useProfile()
                || p.usePregnancy != request.usePregnancy() || p.useMedicalRecords != request.useMedicalRecords()) {
            p.cloudConsent = request.cloudConsent(); p.useProfile = request.useProfile();
            p.usePregnancy = request.usePregnancy(); p.useMedicalRecords = request.useMedicalRecords();
            p.contextVersion++;
        }
        return preferenceDto(p);
    }
    @Transactional
    public Conversation create(String userId) {
        lockUser(userId);
        var p = prefs(userId);
        removeExpiredForOwner(userId);
        if (conversations.countByOwnerUserId(userId) >= 20) throw new BusinessException(ErrorCode.ASSISTANT_HISTORY_LIMIT, "Bạn đã có 20 cuộc trò chuyện. Hãy xóa một cuộc trò chuyện cũ để tạo mới.");
        var c = new AssistantConversation();
        c.id = UUID.randomUUID().toString(); c.ownerUserId = userId; c.title = "Cuộc trò chuyện mới";
        c.contextVersion = p.contextVersion; c.createdAt = now(); c.updatedAt = c.createdAt;
        conversations.save(c);
        return conversationDto(c, p);
    }
    @Transactional
    public List<Conversation> list(String userId) {
        lockUser(userId); var p = prefs(userId); removeExpiredForOwner(userId);
        return conversations.findTop20ByOwnerUserIdOrderByUpdatedAtDesc(userId).stream().map(c -> conversationDto(c, p)).toList();
    }
    @Transactional
    public ConversationDetail detail(String userId, String id) {
        lockUser(userId); var p = prefs(userId); var c = owned(userId, id);
        return new ConversationDetail(conversationDto(c, p), history(id));
    }
    @Transactional
    public void delete(String userId, String id) {
        lockUser(userId); var c = owned(userId, id);
        messages.deleteByConversationId(c.id); conversations.delete(c);
    }
    @Transactional
    public Prepared prepare(String userId, String id, SendMessage request) {
        lockUser(userId); var p = prefs(userId); var c = owned(userId, id);
        if (c.contextVersion != p.contextVersion) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Quyền sử dụng dữ liệu đã thay đổi. Hãy tạo cuộc trò chuyện mới.");
        String content = request.content().trim();
        var existing = messages.findByConversationIdAndClientMessageIdAndRole(id, request.clientMessageId(), "USER");
        if (existing.isPresent()) {
            if (!existing.get().content.equals(content)) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            var response = messages.findByConversationIdAndClientMessageIdAndRole(id, request.clientMessageId(), "ASSISTANT");
            if (response.isPresent()) {
                Reply replay = new Reply(id, messageDto(existing.get()), messageDto(response.get()), remaining(p));
                return new Prepared(id, request.clientMessageId(), preferenceDto(p), List.of(), messageDto(existing.get()), replay);
            }
        }
        if (conversations.existsByOwnerUserIdAndProcessingSinceAfter(userId, now().minusMinutes(2))) throw new BusinessException(ErrorCode.ASSISTANT_BUSY);
        long count = messages.countByConversationId(id);
        if (count + (existing.isEmpty() ? 2 : 1) > 60) throw new BusinessException(ErrorCode.ASSISTANT_HISTORY_LIMIT, "Cuộc trò chuyện đã đủ dung lượng. Hãy tạo cuộc trò chuyện mới.");
        List<Message> history = history(id);
        var userMessage = existing.orElseGet(() -> {
            var m = new AssistantMessage(); m.id = UUID.randomUUID().toString(); m.conversationId = id;
            m.clientMessageId = request.clientMessageId(); m.role = "USER"; m.content = content; m.createdAt = now();
            return messages.save(m);
        });
        c.processingMessageId = request.clientMessageId(); c.processingSince = now(); c.updatedAt = now();
        if (history.isEmpty()) c.title = AssistantText.clean(content, 95);
        return new Prepared(id, request.clientMessageId(), preferenceDto(p), history.stream().filter(m -> !m.id().equals(userMessage.id)).toList(), messageDto(userMessage), null);
    }
    @Transactional
    public boolean reserveAiQuota(String userId, long contextVersion, int estimatedTokens) {
        lockUser(userId); var p = prefs(userId);
        if (p.contextVersion != contextVersion) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(p.usageDay)) { p.usageDay = today; p.aiRequests = 0; }
        var global = quotas.lockGlobal().orElseThrow(() -> new IllegalStateException("Assistant quota not initialized"));
        if (!today.equals(global.usageDay)) { global.usageDay = today; global.requests = 0; global.reservedTokens = 0; }
        if (p.aiRequests >= properties.getDailyUserLimit() || global.requests >= properties.getDailyGlobalLimit()
                || global.reservedTokens + estimatedTokens > properties.getDailyTokenBudget()) return false;
        p.aiRequests++; global.requests++; global.reservedTokens += estimatedTokens;
        return true;
    }
    @Transactional
    public Reply finish(String userId, Prepared prepared, AssistantAnswers.Answer answer) {
        lockUser(userId); var p = prefs(userId);
        var found = conversations.findByIdAndOwnerUserId(prepared.conversationId(), userId);
        if (found.isEmpty()) return null;
        var c = found.get();
        if (!Objects.equals(c.processingMessageId, prepared.clientMessageId())) return null;
        c.processingMessageId = null; c.processingSince = null; c.updatedAt = now();
        if (p.contextVersion != prepared.preferences().version()) return null;
        var m = new AssistantMessage(); m.id = UUID.randomUUID().toString(); m.conversationId = c.id;
        m.clientMessageId = prepared.clientMessageId(); m.role = "ASSISTANT"; m.content = answer.content(); m.createdAt = now();
        Message result = new Message(m.id, m.role, m.content, answer.citations(), answer.actions(), answer.used(),
                answer.safetyNotice(), answer.escalation(), answer.emergency(), answer.mode(), answer.reason(), m.createdAt);
        m.responseJson = json.writeValueAsString(result); messages.save(m);
        return new Reply(c.id, prepared.userMessage(), result, remaining(p));
    }
    @Transactional
    public void release(String userId, Prepared prepared) {
        // Share prepare/finish's user lock so cleanup cannot clear a newer request claim.
        if (users.findByIdForUpdate(userId).isEmpty()) return;
        conversations.findByIdAndOwnerUserId(prepared.conversationId(), userId).ifPresent(c -> {
            if (Objects.equals(c.processingMessageId, prepared.clientMessageId())) { c.processingMessageId = null; c.processingSince = null; }
        });
    }
    @Transactional
    public int remaining(String userId) { lockUser(userId); return remaining(prefs(userId)); }
    @Transactional
    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    public void purgeExpired() {
        OffsetDateTime before = cutoff();
        messages.deleteExpired(before);
        conversations.deleteExpired(before);
    }
    private void removeExpiredForOwner(String userId) {
        for (var c : conversations.findTop20ByOwnerUserIdOrderByUpdatedAtDesc(userId)) {
            if (c.updatedAt.isBefore(cutoff())) { messages.deleteByConversationId(c.id); conversations.delete(c); }
        }
    }
    private void lockUser(String userId) {
        users.findByIdForUpdate(userId).filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE));
    }
    private AssistantPreferences prefs(String userId) {
        return preferences.findById(userId).orElseGet(() -> {
            var p = new AssistantPreferences(); p.userId = userId; return preferences.saveAndFlush(p);
        });
    }
    private AssistantConversation owned(String userId, String id) {
        return conversations.findByIdAndOwnerUserId(id, userId).filter(c -> !c.updatedAt.isBefore(cutoff()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private List<Message> history(String id) {
        return messages.findByConversationIdOrderByCreatedAtAscIdAsc(id).stream().map(this::messageDto).toList();
    }
    private Message messageDto(AssistantMessage m) {
        if (m.responseJson != null) return json.readValue(m.responseJson, Message.class);
        return new Message(m.id, "USER", m.content, List.of(), List.of(), List.of(), null, false, false, "USER", null, m.createdAt);
    }
    private Preferences preferenceDto(AssistantPreferences p) { return new Preferences(p.cloudConsent, p.useProfile, p.usePregnancy, p.useMedicalRecords, p.contextVersion); }
    private Conversation conversationDto(AssistantConversation c, AssistantPreferences p) { return new Conversation(c.id, c.title, c.contextVersion, c.contextVersion != p.contextVersion, c.createdAt, c.updatedAt); }
    private int remaining(AssistantPreferences p) {
        return Math.max(0, properties.getDailyUserLimit() - (LocalDate.now(ZoneOffset.UTC).equals(p.usageDay) ? p.aiRequests : 0));
    }
    private OffsetDateTime cutoff() { return now().minusDays(Math.max(1, properties.getRetentionDays())); }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
