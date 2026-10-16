package vn.nutrimom.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Aggregate observations only. Writes use atomic SQL; no customer identifiers are retained. */
@Entity
@Table(name="admin_customer_metrics",schema="app")
public class AdminCustomerMetricEntity {
    @Id @Column(name="snapshot_hour",nullable=false) private OffsetDateTime snapshotHour;
    @Column(name="captured_at",nullable=false) private OffsetDateTime capturedAt;
    @Column(name="total_customers",nullable=false) private long totalCustomers;
    @Column(name="free_users",nullable=false) private long freeUsers;
    @Column(name="plan_99k_users",nullable=false) private long plan99kUsers;
    @Column(name="plan_399k_users",nullable=false) private long plan399kUsers;
    protected AdminCustomerMetricEntity() {}
}
