package vn.nutrimom.consultation.service;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.service.AdminOperationsService;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AdminConsultationResponse;
import vn.nutrimom.consultation.dto.RequestDtos.SlotInfo;

/** Keeps the existing completed-consultation response contract while sharing bounded read queries. */
@Service
public class AdminConsultationService {
    private final AdminOperationsService operations;

    public AdminConsultationService(AdminOperationsService operations) {
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminConsultationResponse> list(String q, int page, int pageSize) {
        return list(q, page, pageSize, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminConsultationResponse> list(String q, int page, int pageSize,
            LocalDate fromDate, LocalDate toDate, String specialty, String expertUserId, Boolean reviewed) {
        var result = operations.history(page, pageSize, q, specialty, expertUserId, fromDate, toDate, reviewed);
        var items = result.items().stream().map(row -> new AdminConsultationResponse(
                row.id(), row.userId(), row.userDisplayName(), row.expertUserId(), row.expertName(),
                Specialty.valueOf(row.specialty()), AssignmentType.valueOf(row.assignmentType()),
                ConsultationStatus.COMPLETED,
                row.slot() == null ? null : new SlotInfo(row.slot().id(), row.slot().slotDate(),
                        row.slot().startTime(), row.slot().endTime()),
                row.completedAt(), row.createdAt(), row.review())).toList();
        return new PageResponse<>(items, result.page(), result.pageSize(), result.totalItems(), result.totalPages());
    }
}