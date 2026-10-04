package vn.nutrimom.assistant.service;

import org.springframework.stereotype.Service;
import vn.nutrimom.assistant.config.AssistantProperties;
import vn.nutrimom.assistant.dto.AssistantDtos.*;
import vn.nutrimom.common.exception.*;

@Service
public class AssistantService {
    private final AssistantStore store;
    private final AssistantContextService contextService;
    private final AssistantAnswers answers;
    private final AiProviderClient provider;
    private final AssistantProperties properties;
    public AssistantService(AssistantStore store, AssistantContextService contextService,
            AssistantAnswers answers, AiProviderClient provider, AssistantProperties properties) {
        this.store = store; this.contextService = contextService; this.answers = answers;
        this.provider = provider; this.properties = properties;
    }
    public Status status(String userId) {
        return new Status(provider.ready(), "groq", store.remaining(userId), properties.getDailyUserLimit(), properties.getRetentionDays());
    }
    public UserOverview overview(String userId) { return contextService.overview(userId, store.preferences(userId)); }
    public Reply send(String userId, String conversationId, SendMessage request) {
        var prepared = store.prepare(userId, conversationId, request);
        if (prepared.replay() != null) return prepared.replay();
        try {
            var safety = answers.safety(request.content());
            AssistantAnswers.Answer answer;
            if (safety.isPresent()) answer = safety.get();
            else {
                var identity = contextService.identity(userId, prepared.preferences(), request.content());
                if (identity.isPresent()) return completeLocal(userId, prepared, identity.get());
                String query = request.content();
                if (AssistantText.contains(query, "tiep theo", "chi tiet hon", "buoc sau", "huong dan tiep") && !prepared.history().isEmpty()) {
                    String prior = prepared.history().stream().filter(m -> m.role().equals("USER")).reduce((a, b) -> b).map(m -> m.content()).orElse("");
                    query = AssistantText.clean(prior, 300) + " " + query;
                }
                var context = contextService.build(userId, prepared.preferences(), query, request.pagePath());
                var summary = answers.personal(context, request.content());
                if (summary.isPresent()) answer = summary.get();
                else if (!provider.ready()) answer = answers.guide(context, query, "NOT_READY");
                else if (!prepared.preferences().cloudConsent()) answer = answers.guide(context, request.content(), "CONSENT_REQUIRED");
                else {
                    int chars = context.evidence().stream().mapToInt(e -> e.text().length()).sum() + request.content().length() + 1600;
                    int estimatedTokens = 1100 + (int) Math.ceil(chars * 1.5);
                    if (!store.reserveAiQuota(userId, prepared.preferences().version(), estimatedTokens)) answer = answers.guide(context, request.content(), "DAILY_LIMIT");
                    else {
                        var modelHistory = prepared.history().stream().filter(m -> !m.contextUsed().contains("LOCAL_IDENTITY")).toList();
                        try { answer = answers.fromAi(provider.complete(context, request.content(), modelHistory), context, request.content()); }
                        catch (AiProviderClient.ProviderFailure failure) { answer = answers.guide(context, request.content(), failure.reason()); }
                    }
                }
            }
            var result = store.finish(userId, prepared, answer);
            if (result == null) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Cuộc trò chuyện hoặc quyền sử dụng dữ liệu đã thay đổi. Hãy tạo cuộc trò chuyện mới.");
            return result;
        } finally { store.release(userId, prepared); }
    }
    private Reply completeLocal(String userId, AssistantStore.Prepared prepared, AssistantAnswers.Answer answer) {
        var result = store.finish(userId, prepared, answer);
        if (result == null) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        return result;
    }
}
