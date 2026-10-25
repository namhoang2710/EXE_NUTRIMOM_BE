package vn.nutrimom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.assistant.service.AiProviderClient;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.video.LiveKitGateway;

/**
 * Đánh giá web: viết/sửa/xoá của người dùng, trang công khai, và admin trả lời/ẩn.
 *
 * <p>Mỗi test tự tạo người dùng riêng. Trang công khai dùng chung database của cả context, nên các phép
 * kiểm trên danh sách công khai lọc theo id của chính test thay vì đếm tuyệt đối.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebReviewIntegrationTest {
    private static final String REVIEWS = "/api/v1/reviews";
    private static final String ME = "/api/v1/reviews/me";
    private static final String ADMIN = "/api/v1/admin/reviews";

    @MockitoBean AiProviderClient assistantProvider;
    @MockitoBean LiveKitGateway videoProvider;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlySignedInCustomersMayWriteAndEveryoneMayRead() throws Exception {
        mvc.perform(get(REVIEWS)).andExpect(status().isOk());
        mvc.perform(get(REVIEWS + "/summary")).andExpect(status().isOk());
        mvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content(body(5, "Ứng dụng rất hữu ích", null)))
                .andExpect(status().isUnauthorized());

        String admin = user("Quản Trị", UserStatus.ACTIVE, UserRole.USER, UserRole.ADMIN);
        String expert = user("Chuyên Gia", UserStatus.ACTIVE, UserRole.USER, UserRole.EXPERT);
        for (String staff : List.of(admin, expert)) {
            upsert(staff, 5, "Nhân viên tự khen sản phẩm", null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("WEB_REVIEW_NOT_ALLOWED"));
        }
    }

    @Test
    void oneReviewPerPersonWithOptimisticLocking() throws Exception {
        String mom = user("Nguyễn Thị Lan", UserStatus.ACTIVE, UserRole.USER);
        JsonNode created = data(upsert(mom, 4, "Thực đơn gợi ý rất tiện cho mẹ bầu", null)
                .andExpect(status().isCreated()));
        String id = created.at("/id").stringValue();
        assertThat(created.at("/status").stringValue()).isEqualTo("PUBLISHED");
        long version = created.at("/version").longValue();

        // Gửi lại không kèm version: không được lặng lẽ tạo đánh giá thứ hai, cũng không ghi đè mù.
        upsert(mom, 5, "Thêm một đánh giá nữa nhé", null).andExpect(status().isUnprocessableContent());
        upsert(mom, 5, "Sửa với version cũ quá", version + 7).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        JsonNode edited = data(upsert(mom, 5, "Sau một tháng dùng thấy rất hài lòng", version)
                .andExpect(status().isOk()));
        assertThat(edited.at("/id").stringValue()).isEqualTo(id);
        assertThat(edited.at("/rating").intValue()).isEqualTo(5);
        assertThat(edited.at("/edited_at").isMissingNode()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from app.web_reviews where author_id = ?", Long.class, mom))
                .isEqualTo(1L);

        mvc.perform(get(ME).with(token(mom))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        // Đánh giá mới báo cho admin đúng một lần.
        assertThat(jdbc.queryForObject("select count(*) from app.admin_notification_outbox where event_name = ? and source_id = ?",
                Long.class, "ADMIN_REVIEW_NEW", id)).isEqualTo(1L);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        String mom = user("Trần Mai", UserStatus.ACTIVE, UserRole.USER);
        upsert(mom, 0, "Nội dung đủ dài rồi đó", null).andExpect(status().isUnprocessableContent());
        upsert(mom, 6, "Nội dung đủ dài rồi đó", null).andExpect(status().isUnprocessableContent());
        upsert(mom, 4, "Ngắn quá", null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.fields.content").exists());
        upsert(mom, 4, "a".repeat(1001), null).andExpect(status().isUnprocessableContent());
        upsert(mom, 4, "   ​​   ", null).andExpect(status().isUnprocessableContent());
        mvc.perform(get(REVIEWS).param("rating", "9")).andExpect(status().isUnprocessableContent());
        mvc.perform(get(REVIEWS).param("limit", "500")).andExpect(status().isUnprocessableContent());
        mvc.perform(get(ME).with(token(mom))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WEB_REVIEW_NOT_FOUND"));
    }

    @Test
    void thePublicPageHidesWhoWroteItAndPagesNewestFirst() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String mom = user("Phạm Thu Hà", UserStatus.ACTIVE, UserRole.USER);
            ids.add(data(upsert(mom, 3 + i, "Đánh giá số " + i + " <script>alert(1)</script>", null)
                    .andExpect(status().isCreated())).at("/id").stringValue());
        }
        List<JsonNode> mine = publicItems(null).stream().filter(item -> ids.contains(item.at("/id").stringValue())).toList();
        assertThat(mine).extracting(item -> item.at("/id").stringValue()).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        JsonNode first = mine.get(0);
        assertThat(first.at("/author/display_name").stringValue()).isEqualTo("Hà P.");
        assertThat(first.at("/content").stringValue()).isEqualTo("Đánh giá số 2 <script>alert(1)</script>");
        assertThat(first.toString()).doesNotContain("author_id").doesNotContain("phone").doesNotContain("email")
                .doesNotContain("avatar");

        // Phân trang 1 dòng/trang: cursor đi qua hết mà không lặp, không sót.
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            var request = get(REVIEWS).param("limit", "1");
            if (cursor != null) request = request.param("cursor", cursor);
            JsonNode page = data(mvc.perform(request).andExpect(status().isOk()));
            page.at("/items").forEach(item -> paged.add(item.at("/id").stringValue()));
            cursor = page.at("/has_more").booleanValue() ? page.at("/next_cursor").stringValue() : null;
        } while (cursor != null);
        assertThat(paged).doesNotHaveDuplicates().containsAll(ids);

        assertThat(publicItems(5)).allSatisfy(item -> assertThat(item.at("/rating").intValue()).isEqualTo(5));
    }

    @Test
    void theSummaryCountsOnlyVisibleReviewsOfActiveAccounts() throws Exception {
        JsonNode before = data(mvc.perform(get(REVIEWS + "/summary")).andExpect(status().isOk()));
        String visible = user("Lê Vân", UserStatus.ACTIVE, UserRole.USER);
        String willBeLocked = user("Lê Hồng", UserStatus.ACTIVE, UserRole.USER);
        upsert(visible, 2, "Cần thêm nhiều món ăn hơn", null).andExpect(status().isCreated());
        String lockedId = data(upsert(willBeLocked, 2, "Tài khoản này sẽ bị khoá", null)).at("/id").stringValue();
        UserEntity locked = users.findById(willBeLocked).orElseThrow();
        locked.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(locked);

        JsonNode after = data(mvc.perform(get(REVIEWS + "/summary")).andExpect(status().isOk()));
        assertThat(after.at("/total").longValue()).isEqualTo(before.at("/total").longValue() + 1);
        assertThat(after.at("/by_rating/2").longValue()).isEqualTo(before.at("/by_rating/2").longValue() + 1);
        assertThat(after.at("/by_rating").size()).isEqualTo(5);
        assertThat(after.at("/average").isNumber()).isTrue();
        assertThat(publicItems(2)).noneMatch(item -> item.at("/id").stringValue().equals(lockedId));
    }

    @Test
    void adminRepliesPubliclyAndTheAuthorIsNotifiedOnce() throws Exception {
        String mom = user("Đỗ Minh Anh", UserStatus.ACTIVE, UserRole.USER);
        String admin = user("Ban Quản Trị", UserStatus.ACTIVE, UserRole.USER, UserRole.ADMIN);
        JsonNode review = data(upsert(mom, 3, "Giao diện hơi khó dùng lúc đầu", null));
        String id = review.at("/id").stringValue();

        perform(put(ADMIN + "/" + id + "/reply").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Cảm ơn chị", "version", 0))), mom)
                .andExpect(status().isForbidden());
        long pendingBefore = data(perform(get(ADMIN + "/pending-count"), admin)).at("/count").longValue();

        JsonNode replied = data(perform(put(ADMIN + "/" + id + "/reply").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Cảm ơn chị, đội NutriMom sẽ cải thiện ạ.",
                        "version", review.at("/version").longValue()))), admin).andExpect(status().isOk()));
        assertThat(replied.at("/reply/content").stringValue()).startsWith("Cảm ơn chị");
        assertThat(replied.at("/replied_by").stringValue()).isEqualTo(admin);
        assertThat(data(perform(get(ADMIN + "/pending-count"), admin)).at("/count").longValue()).isEqualTo(pendingBefore - 1);

        // Sửa câu trả lời không bắn thêm thông báo.
        perform(put(ADMIN + "/" + id + "/reply").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", "Cảm ơn chị đã góp ý!",
                        "version", replied.at("/version").longValue()))), admin).andExpect(status().isOk());
        assertThat(notificationsFor(mom)).isEqualTo(1L);

        JsonNode publicItem = publicItems(3).stream().filter(item -> item.at("/id").stringValue().equals(id)).findFirst().orElseThrow();
        assertThat(publicItem.at("/reply/content").stringValue()).isEqualTo("Cảm ơn chị đã góp ý!");
        assertThat(publicItem.at("/reply/updated_at").isMissingNode()).isFalse();

        // Người viết sửa sau khi có trả lời: giữ trả lời, admin thấy cờ để xem lại.
        JsonNode mine = data(mvc.perform(get(ME).with(token(mom))));
        upsert(mom, 4, "Dùng quen rồi thì thấy ổn hơn nhiều", mine.at("/version").longValue()).andExpect(status().isOk());
        JsonNode adminView = data(perform(get(ADMIN + "/" + id), admin));
        assertThat(adminView.at("/edited_after_reply").booleanValue()).isTrue();
        assertThat(adminView.at("/reply/content").isMissingNode()).isFalse();
        assertThat(adminView.at("/author/phone_masked").stringValue()).startsWith("***");
    }

    @Test
    void hiddenReviewsLeaveThePublicPageAndCannotBeEditedBack() throws Exception {
        String mom = user("Vũ Thanh Hương", UserStatus.ACTIVE, UserRole.USER);
        String admin = user("Ban Quản Trị", UserStatus.ACTIVE, UserRole.USER, UserRole.ADMIN);
        JsonNode review = data(upsert(mom, 1, "Nội dung quảng cáo spam link lạ", null));
        String id = review.at("/id").stringValue();

        JsonNode hidden = data(perform(post(ADMIN + "/" + id + "/hide").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "Quảng cáo", "version", review.at("/version").longValue()))), admin)
                .andExpect(status().isOk()));
        assertThat(hidden.at("/status").stringValue()).isEqualTo("HIDDEN");
        assertThat(publicItems(1)).noneMatch(item -> item.at("/id").stringValue().equals(id));
        assertThat(notificationsFor(mom)).isEqualTo(1L);

        JsonNode mine = data(mvc.perform(get(ME).with(token(mom))).andExpect(status().isOk()));
        assertThat(mine.at("/status").stringValue()).isEqualTo("HIDDEN");
        assertThat(mine.at("/hidden_reason").stringValue()).isEqualTo("Quảng cáo");
        upsert(mom, 5, "Sửa lại để được hiện lên", mine.at("/version").longValue())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WEB_REVIEW_HIDDEN"));

        perform(post(ADMIN + "/" + id + "/unhide").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", hidden.at("/version").longValue()))), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(publicItems(1)).anyMatch(item -> item.at("/id").stringValue().equals(id));
    }

    @Test
    void deletingFreesTheSlotAndTheAdminStillSeesTheHistory() throws Exception {
        String mom = user("Bùi Ngọc", UserStatus.ACTIVE, UserRole.USER);
        String admin = user("Ban Quản Trị", UserStatus.ACTIVE, UserRole.USER, UserRole.ADMIN);
        String first = data(upsert(mom, 2, "Lần đầu chưa quen ứng dụng", null)).at("/id").stringValue();

        mvc.perform(delete(ME).with(token(mom))).andExpect(status().isNoContent());
        mvc.perform(delete(ME).with(token(mom))).andExpect(status().isNotFound());
        assertThat(publicItems(2)).noneMatch(item -> item.at("/id").stringValue().equals(first));
        String second = data(upsert(mom, 5, "Viết lại sau khi dùng quen", null).andExpect(status().isCreated()))
                .at("/id").stringValue();
        assertThat(second).isNotEqualTo(first);

        JsonNode deleted = data(perform(get(ADMIN + "/" + first), admin).andExpect(status().isOk()));
        assertThat(deleted.at("/status").stringValue()).isEqualTo("DELETED");
        perform(post(ADMIN + "/" + first + "/hide").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "x", "version", deleted.at("/version").longValue()))), admin)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WEB_REVIEW_DELETED"));

        JsonNode filtered = data(perform(get(ADMIN).param("status", "DELETED").param("q", "lần đầu"), admin));
        List<String> found = new ArrayList<>();
        filtered.at("/items").forEach(item -> found.add(item.at("/id").stringValue()));
        assertThat(found).contains(first).doesNotContain(second);
    }

    // ---------------------------------------------------------------------------------------------

    private List<JsonNode> publicItems(Integer rating) throws Exception {
        var request = get(REVIEWS).param("limit", "50");
        if (rating != null) request = request.param("rating", rating.toString());
        List<JsonNode> items = new ArrayList<>();
        data(mvc.perform(request).andExpect(status().isOk())).at("/items").forEach(items::add);
        return items;
    }

    private long notificationsFor(String userId) {
        return jdbc.queryForObject("select count(*) from app.notifications where user_id = ? and source_type = 'WEB_REVIEW'",
                Long.class, userId);
    }

    private ResultActions upsert(String userId, int rating, String content, Long version) throws Exception {
        return mvc.perform(put(ME).with(token(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body(rating, content, version)));
    }

    private String body(int rating, String content, Long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rating", rating);
        body.put("content", content);
        if (version != null) body.put("version", version);
        return json.writeValueAsString(body);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String userId) throws Exception {
        return mvc.perform(request.with(token(userId)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).at("/data");
    }

    private RequestPostProcessor token(String userId) {
        List<String> roles = users.findById(userId).orElseThrow().getRoles().stream().map(Enum::name).toList();
        List<GrantedAuthority> authorities = roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
        return jwt().jwt(t -> t.subject(userId).claim("roles", roles)).authorities(authorities);
    }

    private String user(String displayName, UserStatus status, UserRole... roles) {
        UserEntity row = new UserEntity();
        row.setPhone("+8478" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
        row.setDisplayName(displayName);
        row.setStatus(status);
        row.setRoles(Set.of(roles));
        return users.saveAndFlush(row).getId();
    }
}
