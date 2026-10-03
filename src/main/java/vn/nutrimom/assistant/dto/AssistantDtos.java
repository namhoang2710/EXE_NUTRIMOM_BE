package vn.nutrimom.assistant.dto;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class AssistantDtos {
    private AssistantDtos() { }
    public record Preferences(boolean cloudConsent, boolean useProfile, boolean usePregnancy,
                              boolean useMedicalRecords, long version) { }
    public record UpdatePreferences(@NotNull Boolean cloudConsent, @NotNull Boolean useProfile,
                                    @NotNull Boolean usePregnancy, @NotNull Boolean useMedicalRecords,
                                    @NotNull @PositiveOrZero Long version) { }
    public record SendMessage(@NotBlank @Size(max = 2000) String content,
                              @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientMessageId,
                              @Size(max = 180) String pagePath) { }
    public record Source(String id, String type, String title, String href, String excerpt) { }
    public record Action(String id, String label, String href) { }
    public record Message(String id, String role, String content, List<Source> citations,
                          List<Action> actions, List<String> contextUsed, String safetyNotice,
                          boolean escalationRecommended, boolean emergencyDetected,
                          String mode, String fallbackReason, OffsetDateTime createdAt) { }
    public record Conversation(String id, String title, long contextVersion, boolean readOnly,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record ConversationDetail(Conversation conversation, List<Message> messages) { }
    public record Reply(String conversationId, Message userMessage, Message assistantMessage,
                        int remainingAiMessages) { }
    public record Status(boolean aiReady, String provider, int remainingAiMessages,
                         int dailyAiLimit, int retentionDays) { }
    public record ContextItem(String id, String label, String summary, String href) { }
    public record UserOverview(String displayName, String headline, List<ContextItem> items,
                               List<String> suggestions, long contextVersion, OffsetDateTime updatedAt) { }
}
