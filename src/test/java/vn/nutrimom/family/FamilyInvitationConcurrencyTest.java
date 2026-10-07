package vn.nutrimom.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.domain.FamilyInvitationStatus;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyRelationship;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.dto.AcceptFamilyInvitationRequest;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.family.service.FamilyInvitationService;
import vn.nutrimom.family.service.InvitationTokenService;

/**
 * Chủ nhóm thu hồi đúng lúc người được mời bấm chấp nhận.
 *
 * <p>Cố ý <strong>không</strong> đánh dấu {@code @Transactional} ở lớp test: mỗi luồng phải chạy
 * trong transaction riêng và commit thật thì mới dựng được tranh chấp.</p>
 *
 * <p>Khác {@code ConsultationConcurrencyTest} — vốn để unique index phân xử — ở đây
 * <strong>không có ràng buộc DB nào đỡ lưng</strong>: {@code FamilyInvitationEntity} không có
 * {@code @Version}, nên khoá bi quan trên cùng một dòng là cơ chế duy nhất. Trước khi sửa,
 * {@code revoke} đọc bằng {@code findById} không khoá nên cả hai thao tác cùng thành công.</p>
 *
 * <p>Lưu ý thật thà về độ trung thực: bộ test chạy trên H2 ở chế độ tương thích PostgreSQL. H2 có
 * khoá dòng và {@code SELECT FOR UPDATE} thật, nhưng lock timeout và cách phát hiện deadlock khác
 * Postgres. Bài này kiểm đúng ngữ nghĩa khoá của H2, không phải của Postgres.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class FamilyInvitationConcurrencyTest {

    @Autowired FamilyInvitationService invitationService;
    @Autowired InvitationTokenService tokens;
    @Autowired UserRepository users;
    @Autowired FamilyGroupRepository groups;
    @Autowired FamilyInvitationRepository invitations;
    @Autowired FamilyMemberRepository members;

    private String ownerId;
    private String inviteeId;
    private String groupId;
    private String invitationId;
    private String rawToken;

    @BeforeEach
    void setUp() {
        ownerId = createUser("0988000001", "Race Mom", null);
        inviteeId = createUser("0988000002", "Race Dad", null);

        FamilyGroupEntity group = new FamilyGroupEntity();
        group.setOwnerUserId(ownerId);
        group.setPregnancyId(UUID.randomUUID().toString());
        group.setStatus(FamilyGroupStatus.ACTIVE);
        groupId = groups.saveAndFlush(group).getId();

        InvitationTokenService.TokenMaterial material = tokens.generate();
        rawToken = material.rawToken();
        FamilyInvitationEntity invitation = new FamilyInvitationEntity();
        invitation.setFamilyGroupId(groupId);
        invitation.setInvitedPhone("0988000002");
        invitation.setTokenHash(material.tokenHash());
        invitation.setRelationship(FamilyRelationship.PARTNER);
        invitation.setScopes(EnumSet.of(FamilyScope.SHARED_CALENDAR));
        invitation.setStatus(FamilyInvitationStatus.PENDING);
        invitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(48));
        invitationId = invitations.saveAndFlush(invitation).getId();
    }

    @AfterEach
    void tearDown() {
        members.findByFamilyGroupIdAndUserIdAndStatus(groupId, inviteeId, FamilyMemberStatus.ACTIVE)
                .ifPresent(members::delete);
        invitations.deleteById(invitationId);
        groups.deleteById(groupId);
        users.deleteAllById(List.of(ownerId, inviteeId));
    }

    /**
     * Một trong hai thắng, và trạng thái cuối trong DB phải khớp với bên thắng.
     *
     * <p>Khẳng định thứ hai mới là cái bắt được lỗi cũ: không khoá thì cả hai cùng trả về thành
     * công, dòng lời mời mang trạng thái của người ghi sau, và thành viên vẫn được tạo kể cả khi
     * lời mời ghi là {@code REVOKED}.</p>
     */
    @Test
    void acceptingAndRevokingTheSameInvitationCannotBothSucceed() throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> accepting = pool.submit(attempt(gate, () -> invitationService.accept(
                    inviteeId, new AcceptFamilyInvitationRequest(rawToken))));
            Future<Object> revoking = pool.submit(attempt(gate, () -> {
                invitationService.revoke(ownerId, invitationId);
                return "revoked";
            }));

            Object acceptOutcome = accepting.get(20, TimeUnit.SECONDS);
            Object revokeOutcome = revoking.get(20, TimeUnit.SECONDS);

            boolean accepted = !(acceptOutcome instanceof Throwable);
            boolean revoked = !(revokeOutcome instanceof Throwable);
            assertThat(accepted && revoked)
                    .as("không thể vừa chấp nhận được vừa thu hồi được cùng một lời mời")
                    .isFalse();

            FamilyInvitationStatus finalStatus = invitations.findById(invitationId)
                    .orElseThrow().getStatus();
            boolean hasMembership = members.findByFamilyGroupIdAndUserIdAndStatus(
                    groupId, inviteeId, FamilyMemberStatus.ACTIVE).isPresent();
            if (accepted) {
                assertThat(finalStatus)
                        .as("accept thắng thì lời mời phải dừng ở ACCEPTED")
                        .isEqualTo(FamilyInvitationStatus.ACCEPTED);
                assertThat(hasMembership)
                        .as("accept thắng thì thành viên phải tồn tại")
                        .isTrue();
            } else {
                assertThat(finalStatus)
                        .as("revoke thắng thì lời mời phải dừng ở REVOKED")
                        .isEqualTo(FamilyInvitationStatus.REVOKED);
                assertThat(hasMembership)
                        .as("revoke thắng thì không được có thành viên nào được tạo")
                        .isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Hai thiết bị cùng bấm chấp nhận trên một lời mời — thông báo in-app và link email dẫn tới
     * cùng một dòng, nên đây không phải tình huống giả định.
     *
     * <p>Hai lối vào khoá <em>cùng một dòng</em> bằng hai đường tra khác nhau
     * ({@code findByIdForUpdate} và {@code findByTokenHashForUpdate}), nên phép kiểm
     * {@code acceptedAt != null} bên trong khoá mới là thứ phân xử.</p>
     */
    @Test
    void acceptingTwiceAtOnceCreatesOnlyOneMembership() throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> byId = pool.submit(attempt(gate,
                    () -> invitationService.acceptById(inviteeId, invitationId)));
            Future<Object> byToken = pool.submit(attempt(gate, () -> invitationService.accept(
                    inviteeId, new AcceptFamilyInvitationRequest(rawToken))));

            List<Object> outcomes = List.of(byId.get(20, TimeUnit.SECONDS),
                    byToken.get(20, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(o -> !(o instanceof Throwable)).count())
                    .as("đúng một lần chấp nhận thành công")
                    .isEqualTo(1);
            assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast).map(BusinessException::getCode))
                    .as("lần còn lại bị từ chối bằng INVITATION_ALREADY_USED, không phải lỗi hệ thống")
                    .containsExactly(ErrorCode.INVITATION_ALREADY_USED.code());
            assertThat(members.findByFamilyGroupIdAndUserIdAndStatus(
                    groupId, inviteeId, FamilyMemberStatus.ACTIVE))
                    .as("chỉ một thành viên được tạo")
                    .isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Đợi mọi luồng cùng tới vạch xuất phát rồi mới chạy, để tranh chấp là thật. */
    private static Callable<Object> attempt(CyclicBarrier gate, ThrowingSupplier body) {
        return () -> {
            gate.await(20, TimeUnit.SECONDS);
            try {
                return body.get();
            } catch (Throwable failure) {
                return failure;
            }
        };
    }

    private String createUser(String phone, String displayName, String email) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
