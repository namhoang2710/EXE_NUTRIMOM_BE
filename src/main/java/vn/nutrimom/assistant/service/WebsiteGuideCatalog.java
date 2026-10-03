package vn.nutrimom.assistant.service;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.nutrimom.assistant.dto.AssistantDtos.*;

@Component
public class WebsiteGuideCatalog {
    public record Guide(String id, String title, String href, String keywords, String status, String text) {
        public Source source() { return new Source(id, "GUIDE", title, href, text); }
        public Action action() { return new Action(id, "Mở " + title.toLowerCase(Locale.ROOT), href); }
    }
    public record Knowledge(String version, List<Guide> guides) { }
    private final Knowledge knowledge;
    public WebsiteGuideCatalog() { this(JsonMapper.builder().build()); }
    @Autowired
    public WebsiteGuideCatalog(ObjectMapper mapper) {
        try (var input = new ClassPathResource("assistant/website-knowledge.json").getInputStream()) {
            knowledge = mapper.readValue(input, Knowledge.class);
            if (knowledge.guides().isEmpty() || knowledge.guides().stream().map(Guide::id).distinct().count() != knowledge.guides().size())
                throw new IllegalStateException("Invalid assistant website catalog");
            for (var g : knowledge.guides()) {
                if (!g.href().startsWith("/app") || !Set.of("ACTIVE", "PARTIAL", "PLANNED").contains(g.status()) || g.text().isBlank())
                    throw new IllegalStateException("Invalid assistant website entry: " + g.id());
            }
        } catch (Exception error) { throw new IllegalStateException("Cannot load assistant website knowledge", error); }
    }
    public List<Guide> all() { return List.copyOf(knowledge.guides()); }
    public String version() { return knowledge.version(); }
    public Source websiteMap() {
        StringBuilder text = new StringBuilder("NutriMom là web hỗ trợ hành trình chăm sóc. Bản kiến thức " + version() + ". ACTIVE = đã hoạt động; PARTIAL = một phần; PLANNED = chưa hoàn thiện.\n");
        for (var g : knowledge.guides()) text.append(g.title()).append(" [").append(g.status()).append("]: ")
                .append(g.text().split("\\.\\n|\\.")[0]).append(".\n");
        return new Source("guide:website-map", "GUIDE", "Chức năng và giới hạn của NutriMom", "/app", text.toString());
    }
    public List<Guide> search(String question, String pagePath) {
        String query = AssistantText.fold(question);
        Set<String> stop = Set.of("toi", "cua", "cho", "duoc", "voi", "cac", "nhung", "cach", "nhu", "the", "nao", "va", "tu", "de", "co", "ban", "minh", "lam", "muon", "can", "hay", "ve", "den", "bi", "nen");
        List<String> tokens = Arrays.stream(query.split("[^a-z0-9]+"))
                .filter(t -> t.length() >= 2 && !stop.contains(t)).distinct().toList();
        return knowledge.guides().stream().sorted(Comparator.<Guide>comparingInt(g -> {
            int score = pagePath != null && pagePath.equals(g.href()) ? 1 : 0;
            if (query.contains(AssistantText.fold(g.title()))) score += 12;
            if (g.id().equals("guide:records") && AssistantText.contains(question, "ho so y te", "ket qua kham", "luu ho so", "xet nghiem", "sieu am")) score += 15;
            if (g.id().equals("guide:knowledge") && AssistantText.contains(question, "bai viet", "kien thuc", "blog")
                    && !AssistantText.contains(question, "da luu", "yeu thich", "danh dau", "bookmark")) score += 20;
            if (g.id().equals("guide:nutrition") && AssistantText.contains(question, "trang dinh duong", "nhat ky", "ke hoach dinh duong", "thuc don", "scan mon an")) score += 15;
            if (g.id().equals("guide:consultation-history") && AssistantText.contains(question, "huy lich", "huy tu van", "lich su tu van", "danh gia")) score += 20;
            if (g.id().equals("guide:consultations") && AssistantText.contains(question, "dat lich", "cho chuyen gia", "ngau nhien")) score += 15;
            if (g.id().equals("guide:password") && AssistantText.contains(question, "mat khau")) score += 20;
            Set<String> keywords = Set.copyOf(Arrays.asList(g.keywords().split("\\s+")));
            for (String token : tokens) if (keywords.contains(token)) score += 2;
            return score;
        }).reversed()).limit(3).toList();
    }
    public Guide get(String id) { return knowledge.guides().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow(); }
}
