package vn.nutrimom.admin.realtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Mapping also gives isolated H2 tests the additive revision table; PG triggers own its writes. */
@Entity
@Table(name="admin_realtime_revisions",schema="app")
public class AdminRealtimeRevisionEntity {
    @Id @Column(name="scope_key",length=80,nullable=false) private String scopeKey;
    @Column(nullable=false) private long revision;
    protected AdminRealtimeRevisionEntity() { }
}
