package vn.nutrimom.knowledge.service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import jakarta.persistence.criteria.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.knowledge.domain.*;
import vn.nutrimom.knowledge.dto.ArticleRequest;
import vn.nutrimom.knowledge.dto.ArticleRequest.ImageInput;
import vn.nutrimom.knowledge.dto.ArticleDtos.*;
import vn.nutrimom.knowledge.repository.*;

@Service
public class KnowledgeArticleService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeArticleService.class);
    public static final Set<String> CATEGORIES = Set.of("nutrition", "wellness", "exercise", "pregnancy",
            "postpartum", "preconception", "Dinh dưỡng", "Sống khỏe", "Vận động", "Thai kỳ", "Sau sinh", "Chuẩn bị");
    public static final Set<String> STAGES = Set.of("preconception", "pregnancy", "trimester-1", "trimester-2",
            "trimester-3", "postpartum", "Chuẩn bị mang thai", "Trong thai kỳ", "Sau sinh");
    private final KnowledgeArticleRepository articles;
    private final ArticleMediaRepository media;
    private final ArticleBookmarkRepository bookmarks;
    private final UserRepository users;
    private final KnowledgeArticleMapper mapper;
    private final TransactionTemplate tx;
    @PersistenceContext private EntityManager entityManager;

    public KnowledgeArticleService(KnowledgeArticleRepository articles, ArticleMediaRepository media,
            ArticleBookmarkRepository bookmarks, UserRepository users, KnowledgeArticleMapper mapper,
            PlatformTransactionManager transactions) {
        this.articles = articles; this.media = media; this.bookmarks = bookmarks;
        this.users = users; this.mapper = mapper; this.tx = new TransactionTemplate(transactions);
    }

    public Detail create(ArticleRequest request, String adminId) {
        return write(() -> {
            if (articles.existsBySlug(request.slug())) throw duplicateSlug();
            KnowledgeArticle article = new KnowledgeArticle();
            apply(article, request, adminId);
            articles.saveAndFlush(article);
            log.info("Article created id={} slug={}", article.getId(), article.getSlug());
            return mapper.detail(article);
        });
    }

    public Detail update(String id, ArticleRequest request, String adminId) {
        return write(() -> {
            KnowledgeArticle article = require(id);
            if (articles.existsBySlugAndIdNot(request.slug(), id)) throw duplicateSlug();
            apply(article, request, adminId);
            articles.flush();
            log.info("Article updated id={}", id);
            return mapper.detail(article);
        });
    }

    public void delete(String id) {
        write(() -> {
            KnowledgeArticle article = require(id);
            bookmarks.deleteByArticleId(id);
            articles.delete(article);
            articles.flush();
            log.info("Article deleted id={}", id);
            return true;
        });
    }

    @Transactional(readOnly = true)
    public Detail adminDetail(String id) { return mapper.detail(require(id)); }

    @Transactional(readOnly = true)
    public Detail publicDetail(String slug) {
        KnowledgeArticle article = articles.findBySlug(slug).orElseThrow(this::notFound);
        if (!visible(article)) throw notFound();
        return mapper.detail(article);
    }

    @Transactional(readOnly = true)
    public Pagination<?> list(int page, int pageSize, String category, String stage, String topic,
            boolean savedOnly, String sort, String userId, boolean admin, ArticleStatus status) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw invalid("page must be >= 1; pageSize must be 1..100");
        if ((long) (page - 1) * pageSize > Integer.MAX_VALUE) throw invalid("Requested page exceeds the supported offset");
        validateFilter(category, CATEGORIES, "category");
        validateFilter(stage, STAGES, "stage");
        if (topic != null && topic.length() > 100) throw invalid("topic is too long");
        if (savedOnly && userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "Sign in to read saved articles.");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Specification<KnowledgeArticle> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!admin) {
                predicates.add(cb.equal(root.get("status"), ArticleStatus.published));
                predicates.add(cb.lessThanOrEqualTo(root.get("publishedAt"), now));
            } else if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (has(category)) predicates.add(cb.equal(root.get("category"), category));
            if (has(stage)) predicates.add(cb.equal(root.get("stage"), stage));
            if (has(topic)) predicates.add(cb.isMember(topic, root.get("topics")));
            if (savedOnly) {
                Subquery<String> saved = query.subquery(String.class);
                Root<ArticleBookmark> bookmark = saved.from(ArticleBookmark.class);
                saved.select(bookmark.get("article").get("id"));
                saved.where(cb.equal(bookmark.get("user").get("id"), userId),
                        cb.equal(bookmark.get("article").get("id"), root.get("id")));
                predicates.add(cb.exists(saved));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Sort ordering = sort(sort);
        Page<KnowledgeArticle> result = articles.findAll(spec, PageRequest.of(page - 1, pageSize, ordering));
        // Match the current UI's convention of clamping an out-of-range page.
        int currentPage = Math.min(page, Math.max(1, result.getTotalPages()));
        if (currentPage != page) result = articles.findAll(spec, PageRequest.of(currentPage - 1, pageSize, ordering));
        List<?> items = admin ? result.getContent().stream().map(mapper::detail).toList()
                : result.getContent().stream().map(mapper::summary).toList();
        return new Pagination<>(items, result.getTotalElements(), result.getTotalPages(), currentPage, pageSize);
    }

    public void bookmark(String slug, String userId, boolean save) {
        write(() -> {
            // Serialize bookmark changes on the account so PUT remains idempotent under concurrency.
            UserEntity user = entityManager.find(UserEntity.class, userId, LockModeType.PESSIMISTIC_WRITE);
            if (user == null) throw notFound();
            KnowledgeArticle article = articles.findBySlug(slug).orElseThrow(this::notFound);
            if (!visible(article)) throw notFound();
            if (save && !bookmarks.existsByArticleIdAndUserId(article.getId(), userId)) {
                ArticleBookmark bookmark = new ArticleBookmark();
                bookmark.setArticle(article); bookmark.setUser(user); bookmarks.saveAndFlush(bookmark);
            } else if (!save) bookmarks.deleteByArticleIdAndUserId(article.getId(), userId);
            return true;
        });
    }

    private void apply(KnowledgeArticle article, ArticleRequest r, String adminId) {
        validateFilter(r.category(), CATEGORIES, "category"); validateFilter(r.stage(), STAGES, "stage");
        article.setSlug(r.slug()); article.setTitle(r.title().trim()); article.setExcerpt(r.excerpt());
        article.setCategory(r.category()); article.setStage(r.stage()); article.setLead(r.lead());
        article.setStatus(r.status());
        article.setPublishedAt(r.status() == ArticleStatus.published
                ? (r.publishedAt() != null ? r.publishedAt() : article.getPublishedAt() != null
                    ? article.getPublishedAt() : OffsetDateTime.now(ZoneOffset.UTC)) : null);
        String authorId = r.authorId() != null ? r.authorId()
                : article.getAuthor() != null ? article.getAuthor().getId() : adminId;
        article.setAuthor(users.findById(authorId).orElseThrow(() -> invalid("Unknown authorId")));
        article.setTopics(new LinkedHashSet<>(r.topics() == null ? List.of() : r.topics()));
        ArticleMedia cover = resolve(r.coverImage());
        article.setCoverMedia(cover); article.setCoverImageUrl(cover == null ? null : cover.getImageUrl());
        article.setCoverImageAlt(cover == null ? null : r.coverImage().alt() != null ? r.coverImage().alt() : cover.getAlt());
        article.setCoverImageCaption(cover == null ? null : r.coverImage().caption() != null ? r.coverImage().caption() : cover.getCaption());
        article.setSourceLabel(r.source() == null ? null : r.source().label());
        if (r.source() != null) validateUrl(r.source().href());
        article.setSourceHref(r.source() == null ? null : r.source().href());
        article.getSections().clear();
        if (r.sections() != null) for (int i = 0; i < r.sections().size(); i++) {
            var input = r.sections().get(i);
            ArticleSection section = new ArticleSection();
            section.setId(UUID.randomUUID().toString()); section.setArticle(article); section.setSortOrder(i);
            section.setHeading(input.heading()); section.setParagraphs(mapper.encode(input.paragraphs()));
            section.setBullets(mapper.encode(input.bullets()));
            ArticleMedia image = resolve(input.image()); section.setMedia(image);
            if (image != null) {
                section.setImageUrl(image.getImageUrl());
                section.setImageAlt(input.image().alt() != null ? input.image().alt() : image.getAlt());
                section.setImageCaption(input.image().caption() != null ? input.image().caption() : image.getCaption());
            }
            article.getSections().add(section);
        }
    }

    private ArticleMedia resolve(ImageInput input) {
        if (input == null) return null;
        ArticleMedia result = has(input.id()) ? media.findById(input.id()).orElseThrow(() -> invalid("Unknown media id"))
                : media.findByImageUrl(input.url() == null ? "" : input.url()).orElseThrow(() -> invalid("Image must be uploaded through the media API"));
        if (input.url() != null && !input.url().equals(result.getImageUrl())) throw invalid("Media id and URL do not match");
        return result;
    }

    private <T> T write(Supplier<T> operation) {
        try { return tx.execute(transaction -> operation.get()); }
        catch (BusinessException ex) { throw ex; }
        catch (ObjectOptimisticLockingFailureException ex) { throw ex; }
        catch (RuntimeException ex) {
            log.error("Knowledge DB persistence failed", ex);
            if (ex instanceof DataIntegrityViolationException && String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("ux_knowledge_articles_slug")) throw duplicateSlug();
            throw new BusinessException(ErrorCode.DB_SAVE_FAILED, "Content could not be saved.");
        }
    }

    private KnowledgeArticle require(String id) { return articles.findById(id).orElseThrow(this::notFound); }
    private boolean visible(KnowledgeArticle a) {
        return a.getStatus() == ArticleStatus.published && a.getPublishedAt() != null
                && !a.getPublishedAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }
    private Sort sort(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 2 || !Set.of("publishedAt", "title", "createdAt", "updatedAt").contains(parts[0])
                || !Set.of("asc", "desc").contains(parts[1])) throw invalid("Invalid sort; use publishedAt:desc or field:asc");
        return Sort.by(Sort.Direction.fromString(parts[1]), parts[0]).and(Sort.by("id"));
    }
    private boolean has(String value) { return value != null && !value.isBlank(); }
    private void validateFilter(String value, Set<String> allowed, String field) {
        if (has(value) && !allowed.contains(value)) throw invalid("Unsupported " + field);
    }
    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw invalid("source.href must be an HTTP(S) URL"); }
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
    private BusinessException notFound() { return new BusinessException(ErrorCode.ARTICLE_NOT_FOUND, "Article not found."); }
    private BusinessException duplicateSlug() { return new BusinessException(ErrorCode.SLUG_ALREADY_EXISTS, "Article slug already exists."); }
}
