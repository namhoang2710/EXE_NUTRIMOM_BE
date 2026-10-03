package vn.nutrimom.assistant.service;

import java.util.List;
import vn.nutrimom.assistant.dto.AssistantDtos.Message;

public interface AiProviderClient {
    record Completion(String content, List<String> sourceIds, List<String> actionIds, boolean escalationRecommended) { }
    boolean ready();
    Completion complete(AssistantContextService.Context context, String question, List<Message> history);
    class ProviderFailure extends RuntimeException {
        private final String reason;
        public ProviderFailure(String reason) { super("Assistant provider unavailable"); this.reason = reason; }
        public String reason() { return reason; }
    }
}
