package vn.nutrimom.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.repository.CalendarConsultationQueryRepository;
import vn.nutrimom.calendar.repository.CalendarMedicalRecordQueryRepository;
import vn.nutrimom.calendar.repository.CalendarReminderOccurrenceRepository;
import vn.nutrimom.calendar.repository.CalendarReminderRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.CalendarProperties;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.service.FamilySharingResolver;
import vn.nutrimom.family.service.FamilySharingResolver.SharedContext;
import vn.nutrimom.user.repository.UserPreferenceRepository;

/**
 * Lịch chia sẻ là chỗ duy nhất một tài khoản đọc dữ liệu của tài khoản khác, nên hai thứ phải được
 * khoá bằng test: truy vấn đi theo id của mẹ chứ không phải người xem, và hồ sơ y tế không có
 * đường nào lọt ra.
 */
@ExtendWith(MockitoExtension.class)
class SharedCalendarServiceTest {

    private static final String OWNER_ID = "mom-1";
    private static final String VIEWER_ID = "dad-1";
    private static final LocalDate FROM = LocalDate.parse("2026-10-01");
    private static final LocalDate TO = LocalDate.parse("2026-10-07");

    @Mock private CalendarMedicalRecordQueryRepository medicalRecords;
    @Mock private CalendarConsultationQueryRepository consultations;
    @Mock private CalendarReminderRepository reminders;
    @Mock private CalendarReminderOccurrenceRepository occurrences;
    @Mock private UserPreferenceRepository preferences;
    @Mock private FamilySharingResolver sharing;
    @Mock private UserRepository users;

    private SharedCalendarService service;

    @BeforeEach
    void setUp() {
        CalendarProperties properties = new CalendarProperties();
        CalendarZone zones = new CalendarZone(preferences, properties);
        CalendarQueryService calendar = new CalendarQueryService(medicalRecords, consultations,
                reminders, occurrences, zones, properties,
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        service = new SharedCalendarService(sharing, calendar, zones, users);
    }

    /**
     * Bẫy chính của bản cũ: {@code collect} coi "không lọc gì" là "lấy tất cả". Nếu giữ nếp đó thì
     * một người xem chỉ được cấp nhắc nhở chỉ cần gửi {@code types=MEDICAL_RECORD} là giao thành
     * rỗng rồi lại mở toang cả ba nguồn.
     */
    @Test
    void askingOnlyForMedicalRecordsReturnsNothingInsteadOfEverything() {
        grantScopes(EnumSet.of(FamilyScope.SHARED_CALENDAR));
        stubCalendarEmpty();

        var response = service.events(VIEWER_ID, FROM, TO, "Asia/Ho_Chi_Minh",
                Set.of(CalendarSource.MEDICAL_RECORD));

        assertThat(response.events()).isEmpty();
        verifyNoInteractions(medicalRecords);
    }

    /** Hồ sơ y tế không lọt ra kể cả khi thành viên được cấp thêm scope MEDICAL_RECORDS. */
    @Test
    void medicalRecordsScopeStillDoesNotOpenMedicalRecordsOnTheCalendar() {
        grantScopes(EnumSet.of(FamilyScope.SHARED_CALENDAR, FamilyScope.MEDICAL_RECORDS));
        stubCalendarEmpty();

        var response = service.events(VIEWER_ID, FROM, TO, "Asia/Ho_Chi_Minh", null);

        assertThat(response.allowedSources())
                .containsExactlyInAnyOrder(CalendarSource.CONSULTATION, CalendarSource.REMINDER);
        verifyNoInteractions(medicalRecords);
    }

    /**
     * Cả ba repository đều nhận {@code String} ở vị trí đầu nên trình biên dịch không chặn được
     * việc truyền nhầm id người xem vào chỗ của chủ sở hữu.
     */
    @Test
    void queriesTheOwnerIdNotTheViewerId() {
        grantScopes(EnumSet.of(FamilyScope.SHARED_CALENDAR));
        stubCalendarEmpty();

        service.events(VIEWER_ID, FROM, TO, "Asia/Ho_Chi_Minh", null);

        ArgumentCaptor<String> queried = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(reminders)
                .findWindow(queried.capture(), any(), any(), any());
        assertThat(queried.getValue()).isEqualTo(OWNER_ID);
    }

    @Test
    void missingSharedCalendarScopeIsRejected() {
        when(sharing.requireScope(VIEWER_ID, FamilyScope.SHARED_CALENDAR))
                .thenThrow(new BusinessException(ErrorCode.SHARING_SCOPE_REQUIRED, "nope"));

        assertThatThrownBy(() -> service.events(VIEWER_ID, FROM, TO, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.SHARING_SCOPE_REQUIRED.code()));
    }

    @Test
    void resolveSourcesNeverFallsBackToEverythingWhenTheIntersectionIsEmpty() {
        Set<CalendarSource> allowed =
                Set.of(CalendarSource.REMINDER, CalendarSource.CONSULTATION);

        assertThat(CalendarQueryService.resolveSources(
                Set.of(CalendarSource.MEDICAL_RECORD), allowed)).isEmpty();
        assertThat(CalendarQueryService.resolveSources(null, allowed))
                .containsExactlyInAnyOrderElementsOf(allowed);
        assertThat(CalendarQueryService.resolveSources(Set.of(), allowed))
                .containsExactlyInAnyOrderElementsOf(allowed);
        assertThat(CalendarQueryService.resolveSources(
                Set.of(CalendarSource.REMINDER, CalendarSource.MEDICAL_RECORD), allowed))
                .containsExactly(CalendarSource.REMINDER);
    }

    private void grantScopes(Set<FamilyScope> scopes) {
        FamilyGroupEntity group = new FamilyGroupEntity();
        group.setOwnerUserId(OWNER_ID);
        FamilyMemberEntity member = new FamilyMemberEntity();
        member.setFamilyGroupId("group-1");
        member.setUserId(VIEWER_ID);
        member.setScopes(scopes);
        when(sharing.requireScope(VIEWER_ID, FamilyScope.SHARED_CALENDAR))
                .thenReturn(new SharedContext(member, group));
        UserEntity owner = new UserEntity();
        owner.setDisplayName("Mai");
        lenient().when(users.findById(OWNER_ID)).thenReturn(Optional.of(owner));
    }

    private void stubCalendarEmpty() {
        lenient().when(reminders.findWindow(eq(OWNER_ID), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(consultations.findConfirmed(eq(OWNER_ID), any(), any(), any()))
                .thenReturn(List.of());
    }
}
