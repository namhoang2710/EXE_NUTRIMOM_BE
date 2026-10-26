package vn.nutrimom.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.community.config.CommunityProperties;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.domain.CommunityPostImage;
import vn.nutrimom.community.domain.ImageStatus;
import vn.nutrimom.community.repository.CommunityPostImageRepository;
import vn.nutrimom.community.service.CommunityMediaCleanupJob;
import vn.nutrimom.community.service.CommunityR2StorageService;

/**
 * Luồng cộng đồng chạy thật qua HTTP (MockMvc + H2): quyền, kiểm duyệt, idempotent, thông báo,
 * phân trang và số câu truy vấn của feed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class CommunityIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    vn.nutrimom.consultation.video.LiveKitClient livekit;
    private static final String POSTS = "/api/v1/community/posts";
    private static final String ADMIN = "/api/v1/admin/community";
    private static final String NOTIFICATIONS = "/api/v1/community/notifications";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired CommunityPostImageRepository images;
    @Autowired CommunityProperties properties;
    @Autowired vn.nutrimom.admin.realtime.AdminRealtimeRepository realtimeRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    String author;
    String reader;
    String admin;

    @BeforeEach
    void users() {
        // Bài ghim là trạng thái toàn cục: dọn để các test ghim không ảnh hưởng nhau.
        jdbc.update("update app.community_posts set pinned_at = null, pinned_by = null");
        author = user(UserStatus.ACTIVE, UserRole.USER);
        reader = user(UserStatus.ACTIVE, UserRole.USER);
        admin = user(UserStatus.ACTIVE, UserRole.USER, UserRole.ADMIN);
    }

    // ---------------------------------------------------------------------------------------------
    // Bài viết + kiểm duyệt
    // ---------------------------------------------------------------------------------------------

    @Test
    void pendingCountTracksOutstandingPostsAcrossModerationAndReopening() throws Exception {
        mvc.perform(get(ADMIN + "/posts/pending-count")).andExpect(status().isUnauthorized());
        perform(get(ADMIN + "/posts/pending-count"), author).andExpect(status().isForbidden());
        long baseline = data(perform(get(ADMIN + "/posts/pending-count"), admin)
                .andExpect(status().isOk())).at("/pending_count").longValue();
        String first = createPost(author, "Bài chờ thứ nhất", List.of());
        String second = createPost(author, "Bài chờ thứ hai", List.of());
        String third = createPost(reader, "Bài chờ thứ ba", List.of());
        perform(get(ADMIN + "/posts/pending-count"), admin).andExpect(jsonPath("$.data.pending_count").value(baseline + 3));
        assertThat(realtimeRepository.snapshots(Set.of(admin)).get(admin).communityPendingCount()).isEqualTo(baseline + 3);
        perform(get(ADMIN + "/posts?status=PENDING&limit=1"), admin).andExpect(status().isOk());
        perform(post(ADMIN + "/posts/" + first + "/approve"), admin).andExpect(status().isOk());
        perform(post(ADMIN + "/posts/" + second + "/approve"), admin).andExpect(status().isOk());
        for (int visit = 0; visit < 2; visit++) {
            perform(get(ADMIN + "/posts/pending-count"), admin).andExpect(jsonPath("$.data.pending_count").value(baseline + 1));
        }
        assertThat(realtimeRepository.snapshots(Set.of(admin)).get(admin).communityPendingCount()).isEqualTo(baseline + 1);
        perform(post(ADMIN + "/posts/" + third + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}"), admin).andExpect(status().isUnprocessableEntity());
        perform(get(ADMIN + "/posts/pending-count"), admin).andExpect(jsonPath("$.data.pending_count").value(baseline + 1));
        perform(delete(POSTS + "/" + third), reader).andExpect(status().isOk());
        perform(get(ADMIN + "/posts/pending-count"), admin).andExpect(jsonPath("$.data.pending_count").value(baseline));
    }

    @Test
    void pendingPostIsHiddenUntilApprovedAndAuthorIsNotified() throws Exception {
        String id = createPost(author, "Mẹ bầu tuần 20 nên ăn gì?", List.of());

        JsonNode created = data(perform(get(POSTS + "/" + id), author).andExpect(status().isOk()));
        assertThat(created.at("/status").stringValue()).isEqualTo("PENDING");
        assertThat(created.at("/can_edit").booleanValue()).isTrue();

        // Người khác không thấy bài chờ duyệt: 404 chứ không phải 403 (không lộ id tồn tại).
        perform(get(POSTS + "/" + id), reader).andExpect(status().isNotFound());
        assertThat(feedIds(reader, "")).doesNotContain(id);

        perform(post(ADMIN + "/posts/" + id + "/approve"), admin).andExpect(status().isOk());
        assertThat(feedIds(reader, "")).contains(id);
        JsonNode seen = data(perform(get(POSTS + "/" + id), reader).andExpect(status().isOk()));
        assertThat(seen.at("/can_edit").booleanValue()).isFalse();
        assertThat(notificationTypes(author)).contains("POST_APPROVED");
    }

    @Test
    void anonymousAndNonAdminAreRejected() throws Exception {
        mvc.perform(get(POSTS)).andExpect(status().isUnauthorized());
        String id = createPost(author, "Bài viết", List.of());
        perform(post(ADMIN + "/posts/" + id + "/approve"), reader).andExpect(status().isForbidden());
        perform(get(ADMIN + "/reports"), reader).andExpect(status().isForbidden());
    }

    @Test
    void lockedAccountCannotPost() throws Exception {
        String locked = user(UserStatus.LOCKED, UserRole.USER);
        perform(post(POSTS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Xin chào", "topic", "OTHER"))), locked)
                .andExpect(status().isForbidden());
    }

    @Test
    void contentIsValidatedAndClientCannotSetServerFields() throws Exception {
        perform(post(POSTS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", " ​\n ", "topic", "OTHER"))), author)
                .andExpect(status().isUnprocessableContent());
        // status/author_id/like_count trong body bị bỏ qua: bài vẫn PENDING, thuộc người gửi token.
        JsonNode created = data(perform(post(POSTS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "<script>alert(1)</script>", "topic", "OTHER",
                        "status", "APPROVED", "author_id", reader, "like_count", 99))), author)
                .andExpect(status().isCreated()));
        assertThat(created.at("/status").stringValue()).isEqualTo("PENDING");
        assertThat(created.at("/author/id").stringValue()).isEqualTo(author);
        assertThat(created.at("/like_count").intValue()).isZero();
        // Lưu nguyên văn bản thuần; FE hiển thị bằng text nên thẻ không được thực thi.
        assertThat(created.at("/content").stringValue()).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void editingApprovedPostSendsItBackToReviewAndChecksVersion() throws Exception {
        String id = approvedPost(author, "Nội dung đầu");
        JsonNode current = data(perform(get(POSTS + "/" + id), author));
        long version = current.at("/version").longValue();

        perform(patch(POSTS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Sửa", "topic", "OTHER", "version", version))), reader)
                .andExpect(status().isNotFound());

        JsonNode edited = data(perform(patch(POSTS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Nội dung đã sửa", "topic", "NUTRITION",
                        "version", version))), author).andExpect(status().isOk()));
        assertThat(edited.at("/status").stringValue()).isEqualTo("PENDING");
        assertThat(feedIds(reader, "")).doesNotContain(id);

        perform(patch(POSTS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Ghi đè", "topic", "OTHER", "version", version))), author)
                .andExpect(status().isConflict());
    }

    @Test
    void authorDeletesOwnPostOnly() throws Exception {
        String id = approvedPost(author, "Sẽ xoá");
        perform(delete(POSTS + "/" + id), reader).andExpect(status().isNotFound());
        perform(delete(POSTS + "/" + id), author).andExpect(status().isOk());
        perform(get(POSTS + "/" + id), author).andExpect(status().isNotFound());
        assertThat(feedIds(reader, "")).doesNotContain(id);
    }

    // ---------------------------------------------------------------------------------------------
    // Tim, lưu, bình luận
    // ---------------------------------------------------------------------------------------------

    @Test
    void likeIsIdempotentAndNotifiesOnce() throws Exception {
        String id = approvedPost(author, "Tim bài này");
        perform(put(POSTS + "/" + id + "/like"), reader).andExpect(jsonPath("$.data.like_count").value(1));
        perform(put(POSTS + "/" + id + "/like"), reader).andExpect(jsonPath("$.data.like_count").value(1));
        perform(delete(POSTS + "/" + id + "/like"), reader).andExpect(jsonPath("$.data.like_count").value(0));
        perform(delete(POSTS + "/" + id + "/like"), reader).andExpect(jsonPath("$.data.like_count").value(0));
        perform(put(POSTS + "/" + id + "/like"), reader).andExpect(jsonPath("$.data.liked").value(true));
        // Tự tim bài mình: đếm nhưng không tạo thông báo.
        perform(put(POSTS + "/" + id + "/like"), author).andExpect(jsonPath("$.data.like_count").value(2));

        assertThat(notificationTypes(author).stream().filter("POST_LIKED"::equals)).hasSize(1);
        JsonNode item = data(perform(get(POSTS + "/" + id), reader));
        assertThat(item.at("/liked_by_me").booleanValue()).isTrue();
    }

    @Test
    void cannotInteractWithPendingPost() throws Exception {
        String id = createPost(author, "Chưa duyệt", List.of());
        perform(put(POSTS + "/" + id + "/like"), reader).andExpect(status().isNotFound());
        perform(put(POSTS + "/" + id + "/save"), reader).andExpect(status().isNotFound());
        perform(post(POSTS + "/" + id + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"), reader).andExpect(status().isNotFound());
    }

    @Test
    void savedPostsAppearInFeedFilterAndUnifiedSavedList() throws Exception {
        String id = approvedPost(author, "Lưu lại đọc sau");
        perform(put(POSTS + "/" + id + "/save"), reader).andExpect(jsonPath("$.data.saved").value(true));
        perform(put(POSTS + "/" + id + "/save"), reader).andExpect(status().isOk());

        assertThat(feedIds(reader, "&saved=true")).containsExactly(id);
        JsonNode saved = data(perform(get("/api/v1/me/saved-items?type=COMMUNITY"), reader).andExpect(status().isOk()));
        assertThat(saved.at("/items/0/type").stringValue()).isEqualTo("COMMUNITY");
        assertThat(saved.at("/items/0/id").stringValue()).isEqualTo(id);
        JsonNode knowledgeOnly = data(perform(get("/api/v1/me/saved-items?type=KNOWLEDGE"), reader));
        assertThat(knowledgeOnly.at("/items").size()).isZero();

        perform(delete(POSTS + "/" + id + "/save"), reader).andExpect(jsonPath("$.data.saved").value(false));
        assertThat(data(perform(get("/api/v1/me/saved-items"), reader)).at("/items").size()).isZero();
    }

    @Test
    void commentsCountNotifyAndOnlyAuthorDeletes() throws Exception {
        String id = approvedPost(author, "Hỏi đáp");
        JsonNode comment = data(perform(post(POSTS + "/" + id + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Mẹ nên ăn thêm rau xanh\"}"), reader).andExpect(status().isCreated()));
        String commentId = comment.at("/id").stringValue();
        assertThat(data(perform(get(POSTS + "/" + id), reader)).at("/comment_count").intValue()).isEqualTo(1);
        assertThat(notificationTypes(author)).contains("POST_COMMENTED");

        perform(delete("/api/v1/community/comments/" + commentId), author).andExpect(status().isNotFound());
        perform(delete("/api/v1/community/comments/" + commentId), reader).andExpect(status().isOk());
        assertThat(data(perform(get(POSTS + "/" + id), reader)).at("/comment_count").intValue()).isZero();
        assertThat(data(perform(get(POSTS + "/" + id + "/comments"), reader)).at("/items").size()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Báo cáo
    // ---------------------------------------------------------------------------------------------

    @Test
    void reportIsDeduplicatedPrivateAndRemovalNotifiesWithoutReporter() throws Exception {
        String id = approvedPost(author, "Nội dung bị báo cáo");
        String body = "{\"reason\":\"SPAM\",\"note\":\"quảng cáo\"}";
        perform(post(POSTS + "/" + id + "/reports").contentType(MediaType.APPLICATION_JSON).content(body), reader)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.created").value(true));
        perform(post(POSTS + "/" + id + "/reports").contentType(MediaType.APPLICATION_JSON).content(body), reader)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(false));
        perform(post(POSTS + "/" + id + "/reports").contentType(MediaType.APPLICATION_JSON).content(body), author)
                .andExpect(status().isUnprocessableContent());
        // Bị báo cáo chưa tạo thông báo cho chủ bài.
        assertThat(notificationTypes(author)).doesNotContain("POST_REMOVED");

        JsonNode reports = data(perform(get(ADMIN + "/reports?status=OPEN"), admin).andExpect(status().isOk()));
        JsonNode report = null;
        for (JsonNode item : reports.at("/items")) if (item.at("/post_id").stringValue().equals(id)) report = item;
        assertThat(report).isNotNull();
        assertThat(report.at("/reporter/id").stringValue()).isEqualTo(reader);

        perform(post(ADMIN + "/reports/" + report.at("/id").stringValue() + "/resolve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REMOVE_POST\",\"note\":\"spam\"}"), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTION_TAKEN"));
        perform(get(POSTS + "/" + id), reader).andExpect(status().isNotFound());

        JsonNode notes = data(perform(get(NOTIFICATIONS), author));
        JsonNode removed = null;
        for (JsonNode item : notes.at("/items")) if ("POST_REMOVED".equals(item.at("/type").stringValue())) removed = item;
        assertThat(removed).isNotNull();
        assertThat(removed.has("actor")).isFalse();
        assertThat(removed.toString()).doesNotContain(reader);
    }

    // ---------------------------------------------------------------------------------------------
    // Ghim, feed, phân trang, N+1
    // ---------------------------------------------------------------------------------------------

    @Test
    void pinnedPostsComeFirstOnlyOnFirstPageAndAreLimited() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) ids.add(approvedPost(author, "Ghim " + i));
        for (int i = 0; i < 3; i++) perform(put(ADMIN + "/posts/" + ids.get(i) + "/pin"), admin).andExpect(status().isOk());
        perform(put(ADMIN + "/posts/" + ids.get(3) + "/pin"), admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMUNITY_PIN_LIMIT_REACHED"));

        JsonNode first = data(perform(get(POSTS + "?limit=20"), reader));
        Set<String> pinned = new HashSet<>();
        first.at("/pinned").forEach(item -> pinned.add(item.at("/id").stringValue()));
        assertThat(pinned).containsExactlyInAnyOrderElementsOf(ids.subList(0, 3));
        first.at("/items").forEach(item -> assertThat(pinned).doesNotContain(item.at("/id").stringValue()));

        perform(delete(ADMIN + "/posts/" + ids.get(0) + "/pin"), admin).andExpect(status().isOk());
        perform(put(ADMIN + "/posts/" + ids.get(3) + "/pin"), admin).andExpect(status().isOk());
    }

    @Test
    void cursorPaginationWalksEveryPostOnceWithConstantQueryCount() throws Exception {
        String topicAuthor = user(UserStatus.ACTIVE, UserRole.USER);
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) created.add(approvedPost(topicAuthor, "Bài " + i, "BABY_CARE"));
        for (String id : created) perform(put(POSTS + "/" + id + "/like"), reader);

        List<String> seen = new ArrayList<>();
        String cursor = "";
        boolean hasMore = true;
        while (hasMore) {
            JsonNode page = data(perform(get(POSTS + "?topic=BABY_CARE&limit=2" + cursor), reader));
            page.at("/items").forEach(item -> seen.add(item.at("/id").stringValue()));
            hasMore = page.at("/has_more").booleanValue();
            if (hasMore) cursor = "&cursor=" + page.at("/next_cursor").stringValue();
            assertThat(page.at("/items").size()).isLessThanOrEqualTo(2);
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates();

        // Số câu SQL không đổi theo số bài trong trang (không N+1).
        long small = statementsFor("/api/v1/community/posts?topic=BABY_CARE&limit=1&cursor=" + cursorAfter(2));
        long large = statementsFor("/api/v1/community/posts?topic=BABY_CARE&limit=5");
        // Trang đầu thêm đúng một câu lấy bài ghim (không có bài ghim nên không dựng item nào).
        assertThat(large).isEqualTo(small + 1);
        perform(get(POSTS + "?limit=21"), reader).andExpect(status().isUnprocessableContent());
        perform(get(POSTS + "?cursor=not-a-cursor"), reader).andExpect(status().isUnprocessableContent());
    }

    // ---------------------------------------------------------------------------------------------
    // Ảnh
    // ---------------------------------------------------------------------------------------------

    @Test
    void imagesAttachOnlyForOwnerAndOnlyOnce() throws Exception {
        String mine = pendingImage(author, OffsetDateTime.now().plusHours(1));
        String others = pendingImage(reader, OffsetDateTime.now().plusHours(1));
        String expired = pendingImage(author, OffsetDateTime.now().minusMinutes(1));

        expectMediaInvalid(List.of(others));
        expectMediaInvalid(List.of(expired));
        String id = createPost(author, "Có ảnh", List.of(mine));
        JsonNode post = data(perform(get(POSTS + "/" + id), author));
        assertThat(post.at("/images/0/id").stringValue()).isEqualTo(mine);
        // Ảnh đã gắn bài này không gắn được vào bài khác.
        expectMediaInvalid(List.of(mine));
    }

    @Test
    void uploadFailsCleanlyWhenStorageIsNotConfigured() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1, 2, 3});
        mvc.perform(multipart("/api/v1/community/media").file(file).with(token(author)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("R2_UPLOAD_FAILED"));
    }

    @Test
    void cleanupDeletesObjectBeforeRowAndKeepsRowWhenR2Fails() throws Exception {
        String due = pendingImage(author, OffsetDateTime.now().minusDays(1));
        String failing = pendingImage(author, OffsetDateTime.now().minusDays(1));
        String fresh = pendingImage(author, OffsetDateTime.now().plusDays(1));
        String failingKey = images.findById(failing).orElseThrow().getImageKey();

        CommunityR2StorageService store = Mockito.mock(CommunityR2StorageService.class);
        Mockito.doThrow(new RuntimeException("timeout")).when(store).delete(failingKey);
        @SuppressWarnings("unchecked")
        ObjectProvider<CommunityR2StorageService> provider = Mockito.mock(ObjectProvider.class);
        new CommunityMediaCleanupJob(images, provider, properties).cleanup(store);

        assertThat(images.findById(due)).isEmpty();
        assertThat(images.findById(failing)).get().extracting(CommunityPostImage::getCleanupAttempts).isEqualTo(1);
        assertThat(images.findById(fresh)).isPresent();
    }

    // ---------------------------------------------------------------------------------------------
    // Thông báo
    // ---------------------------------------------------------------------------------------------

    @Test
    void notificationsAreScopedToRecipientAndMarkReadIsIdempotent() throws Exception {
        String id = approvedPost(author, "Thông báo");
        perform(put(POSTS + "/" + id + "/like"), reader);
        JsonNode list = data(perform(get(NOTIFICATIONS + "?unread_only=true"), author));
        String notificationId = list.at("/items/0/id").stringValue();
        assertThat(data(perform(get(NOTIFICATIONS + "/unread-count"), author)).at("/unread_count").longValue())
                .isGreaterThanOrEqualTo(1);

        perform(post(NOTIFICATIONS + "/" + notificationId + "/read"), reader).andExpect(status().isNotFound());
        perform(post(NOTIFICATIONS + "/" + notificationId + "/read"), author).andExpect(status().isOk());
        perform(post(NOTIFICATIONS + "/" + notificationId + "/read"), author).andExpect(status().isOk());
        perform(post(NOTIFICATIONS + "/read-all"), author).andExpect(status().isOk());
        assertThat(data(perform(get(NOTIFICATIONS + "/unread-count"), author)).at("/unread_count").longValue()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Admin đăng bài, ghim, phân quyền kiểm duyệt
    // ---------------------------------------------------------------------------------------------

    @Test
    void adminPostIsApprovedOfficialAndCanBePinnedOnCreate() throws Exception {
        JsonNode created = data(perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Lịch tư vấn dinh dưỡng tháng 11", "topic", "NUTRITION",
                        "pinned", true))), admin).andExpect(status().isCreated()));
        String id = created.at("/post/id").stringValue();
        assertThat(created.at("/post/status").stringValue()).isEqualTo("APPROVED");
        assertThat(created.at("/post/pinned").booleanValue()).isTrue();
        assertThat(created.at("/post/official").booleanValue()).isTrue();

        JsonNode feed = data(perform(get(POSTS + "?limit=20"), reader));
        JsonNode pinned = null;
        for (JsonNode item : feed.at("/pinned")) if (item.at("/id").stringValue().equals(id)) pinned = item;
        assertThat(pinned).isNotNull();
        assertThat(pinned.at("/official").booleanValue()).isTrue();
        String userPost = approvedPost(author, "Bài của mẹ");
        assertThat(data(perform(get(POSTS + "/" + userPost), reader)).at("/official").booleanValue()).isFalse();

        JsonNode pinnedList = data(perform(get(ADMIN + "/posts?pinned=true"), admin).andExpect(status().isOk()));
        List<String> pinnedIds = new ArrayList<>();
        pinnedList.at("/items").forEach(item -> pinnedIds.add(item.at("/post/id").stringValue()));
        assertThat(pinnedIds).contains(id).doesNotContain(userPost);
    }

    @Test
    void adminPostWithPinWhenLimitReachedCreatesNothing() throws Exception {
        for (int i = 0; i < 3; i++) {
            String id = approvedPost(author, "Ghim " + i);
            perform(put(ADMIN + "/posts/" + id + "/pin"), admin).andExpect(status().isOk());
        }
        Long before = jdbc.queryForObject("select count(*) from app.community_posts", Long.class);
        perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "Không được tạo", "topic", "OTHER", "pinned", true))), admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMUNITY_PIN_LIMIT_REACHED"));
        assertThat(jdbc.queryForObject("select count(*) from app.community_posts", Long.class)).isEqualTo(before);
        // Không ghim thì vẫn đăng được.
        perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "Bài thường của ban quản trị", "topic", "OTHER"))), admin)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.post.pinned").value(false));
    }

    @Test
    void adminPostSkipsUserDailyQuotaButUsersStillHitIt() throws Exception {
        for (int i = 0; i <= properties.dailyPostLimit(); i++) {
            perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                    Map.of("content", "Thông báo " + i, "topic", "OTHER"))), admin).andExpect(status().isCreated());
        }
        String mom = user(UserStatus.ACTIVE, UserRole.USER);
        for (int i = 0; i < properties.dailyPostLimit(); i++) createPost(mom, "Bài " + i, List.of());
        perform(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "Bài vượt quota", "topic", "OTHER"))), mom)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("QUOTA_EXCEEDED"));
    }

    @Test
    void adminPostIsValidated() throws Exception {
        perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "  ", "topic", "OTHER"))), admin).andExpect(status().isUnprocessableContent());
        perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "a".repeat(5001), "topic", "OTHER"))), admin).andExpect(status().isUnprocessableContent());
        perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"content\":\"x\",\"topic\":\"VIDEO\"}"), admin).andExpect(status().is4xxClientError());
        perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "x", "topic", "OTHER", "image_ids", List.of("a", "b", "c", "d", "e")))), admin)
                .andExpect(status().isUnprocessableContent());
        String othersImage = pendingImage(reader, OffsetDateTime.now().plusHours(1));
        perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "x", "topic", "OTHER", "image_ids", List.of(othersImage)))), admin)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("COMMUNITY_MEDIA_INVALID"));
    }

    @Test
    void onlyActiveAdminsCanModerate() throws Exception {
        String target = approvedPost(author, "Bài để thử quyền");
        String comment = data(perform(post(POSTS + "/" + target + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"bình luận\"}"), reader)).at("/id").stringValue();
        perform(post(POSTS + "/" + target + "/reports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"SPAM\"}"), reader).andExpect(status().isCreated());
        String report = jdbc.queryForObject("select id from app.community_reports where post_id = ?", String.class, target);

        List<Supplier<MockHttpServletRequestBuilder>> adminCalls = List.of(
                () -> post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\",\"topic\":\"OTHER\"}"),
                () -> get(ADMIN + "/posts"),
                () -> get(ADMIN + "/posts/" + target),
                () -> post(ADMIN + "/posts/" + target + "/approve"),
                () -> post(ADMIN + "/posts/" + target + "/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),
                () -> put(ADMIN + "/posts/" + target + "/pin"),
                () -> delete(ADMIN + "/posts/" + target + "/pin"),
                () -> delete(ADMIN + "/posts/" + target),
                () -> delete(ADMIN + "/comments/" + comment),
                () -> get(ADMIN + "/reports"),
                () -> post(ADMIN + "/reports/" + report + "/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"DISMISS\"}"));

        String lockedAdmin = user(UserStatus.LOCKED, UserRole.USER, UserRole.ADMIN);
        for (Supplier<MockHttpServletRequestBuilder> call : adminCalls) {
            mvc.perform(call.get()).andExpect(status().isUnauthorized());
            perform(call.get(), reader).andExpect(status().isForbidden());
            perform(call.get(), author).andExpect(status().isForbidden());
            // Token còn ghi role ADMIN nhưng tài khoản trong DB đã bị khoá → vẫn bị chặn.
            mvc.perform(call.get().with(tokenWithRoles(lockedAdmin, List.of("USER", "ADMIN")))).andExpect(status().isForbidden());
        }
        // Không lệnh nào ở trên được thực thi: bài vẫn duyệt, chưa ghim, bình luận còn, báo cáo còn mở.
        JsonNode post = data(perform(get(POSTS + "/" + target), reader).andExpect(status().isOk()));
        assertThat(post.at("/status").stringValue()).isEqualTo("APPROVED");
        assertThat(post.at("/pinned").booleanValue()).isFalse();
        assertThat(post.at("/comment_count").intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from app.community_reports where id = ?", String.class, report))
                .isEqualTo("OPEN");
    }

    @Test
    void authorsStillDeleteTheirOwnPosts() throws Exception {
        String id = approvedPost(author, "Bài tự xoá");
        perform(delete(POSTS + "/" + id), reader).andExpect(status().isNotFound());
        perform(delete(POSTS + "/" + id), admin).andExpect(status().isNotFound());
        perform(delete(POSTS + "/" + id), author).andExpect(status().isOk());
    }

    @Test
    void adminModeratingOwnPostDoesNotNotifyThemself() throws Exception {
        String id = data(perform(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "Admin đăng như người dùng", "topic", "OTHER"))), admin)
                .andExpect(status().isCreated())).at("/id").stringValue();
        perform(post(ADMIN + "/posts/" + id + "/approve"), admin).andExpect(status().isOk());
        perform(delete(ADMIN + "/posts/" + id), admin).andExpect(status().isOk());
        assertThat(notificationTypes(admin)).doesNotContain("POST_APPROVED", "POST_REMOVED");
    }

    @Test
    void adminEditingOwnApprovedPostKeepsItPublished() throws Exception {
        JsonNode created = data(perform(post(ADMIN + "/posts").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(Map.of("content", "Bản đầu", "topic", "OTHER"))), admin));
        String id = created.at("/post/id").stringValue();
        long version = created.at("/post/version").longValue();
        JsonNode edited = data(perform(patch(POSTS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(Map.of("content", "Bản đã sửa", "topic", "OTHER", "version", version))), admin)
                .andExpect(status().isOk()));
        assertThat(edited.at("/status").stringValue()).isEqualTo("APPROVED");
        assertThat(feedIds(reader, "")).contains(id);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void expectMediaInvalid(List<String> imageIds) throws Exception {
        perform(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", "Có ảnh", "topic", "OTHER", "image_ids", imageIds))), author)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("COMMUNITY_MEDIA_INVALID"));
    }

    private String pendingImage(String owner, OffsetDateTime deleteAfter) {
        CommunityPostImage image = new CommunityPostImage();
        String id = UUID.randomUUID().toString();
        image.setId(id);
        image.setOwnerId(owner);
        image.setImageKey("community/test/" + id + ".jpg");
        image.setImageUrl("https://example.test/community/test/" + id + ".jpg");
        image.setWidth(10);
        image.setHeight(10);
        image.setSizeBytes(100);
        image.setStatus(ImageStatus.PENDING);
        image.setDeleteAfter(deleteAfter);
        images.saveAndFlush(image);
        return id;
    }

    private long statementsFor(String url) throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        perform(get(url), reader).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    private String cursorAfter(int index) throws Exception {
        JsonNode page = data(perform(get(POSTS + "?topic=BABY_CARE&limit=" + index), reader));
        return page.at("/next_cursor").stringValue();
    }

    private String approvedPost(String owner, String content) throws Exception {
        return approvedPost(owner, content, "OTHER");
    }

    private String approvedPost(String owner, String content, String topic) throws Exception {
        String id = data(perform(post(POSTS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", content, "topic", topic))), owner)
                .andExpect(status().isCreated())).at("/id").stringValue();
        perform(post(ADMIN + "/posts/" + id + "/approve"), admin).andExpect(status().isOk());
        return id;
    }

    private String createPost(String owner, String content, List<String> imageIds) throws Exception {
        return data(perform(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("content", content, "topic", "PREGNANCY", "image_ids", imageIds))), owner)
                .andExpect(status().isCreated())).at("/id").stringValue();
    }

    private List<String> feedIds(String viewer, String query) throws Exception {
        JsonNode page = data(perform(get(POSTS + "?limit=20" + query), viewer).andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        page.at("/items").forEach(item -> ids.add(item.at("/id").stringValue()));
        page.at("/pinned").forEach(item -> ids.add(item.at("/id").stringValue()));
        return ids;
    }

    private List<String> notificationTypes(String userId) throws Exception {
        JsonNode page = data(perform(get(NOTIFICATIONS + "?limit=50"), userId).andExpect(status().isOk()));
        List<String> types = new ArrayList<>();
        page.at("/items").forEach(item -> types.add(item.at("/type").stringValue()));
        return types;
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String userId) throws Exception {
        return mvc.perform(request.with(token(userId)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).at("/data");
    }

    private RequestPostProcessor token(String userId) {
        return tokenWithRoles(userId, userId.equals(admin) ? List.of("USER", "ADMIN") : List.of("USER"));
    }

    private RequestPostProcessor tokenWithRoles(String userId, List<String> roles) {
        List<GrantedAuthority> authorities = roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
        return jwt().jwt(t -> t.subject(userId).claim("roles", roles)).authorities(authorities);
    }

    private String user(UserStatus status, UserRole... roles) {
        UserEntity row = new UserEntity();
        row.setPhone("+8477" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
        row.setDisplayName("Mẹ " + CommunityClock.now().getNano());
        row.setStatus(status);
        row.setRoles(Set.of(roles));
        return users.saveAndFlush(row).getId();
    }
}
