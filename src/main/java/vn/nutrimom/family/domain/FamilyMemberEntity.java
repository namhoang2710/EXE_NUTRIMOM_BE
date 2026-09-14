package vn.nutrimom.family.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "family_members", schema = "app")
public class FamilyMemberEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "family_group_id", nullable = false, length = 36)
    private String familyGroupId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, length = 30)
    private FamilyRelationship relationship;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_role", nullable = false, length = 30)
    private FamilyMembershipRole membershipRole;

    @Convert(converter = FamilyScopeSetConverter.class)
    @Column(name = "scopes", nullable = false, columnDefinition = "nvarchar(max)")
    private Set<FamilyScope> scopes = EnumSet.noneOf(FamilyScope.class);

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FamilyMemberStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getId() { return id; }
    public String getFamilyGroupId() { return familyGroupId; }
    public void setFamilyGroupId(String value) { familyGroupId = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public FamilyRelationship getRelationship() { return relationship; }
    public void setRelationship(FamilyRelationship value) { relationship = value; }
    public FamilyMembershipRole getMembershipRole() { return membershipRole; }
    public void setMembershipRole(FamilyMembershipRole value) { membershipRole = value; }
    public Set<FamilyScope> getScopes() {
        return scopes.isEmpty() ? EnumSet.noneOf(FamilyScope.class) : EnumSet.copyOf(scopes);
    }
    public void setScopes(Set<FamilyScope> values) {
        scopes = values == null || values.isEmpty()
                ? EnumSet.noneOf(FamilyScope.class)
                : EnumSet.copyOf(values);
    }
    public FamilyMemberStatus getStatus() { return status; }
    public void setStatus(FamilyMemberStatus value) { status = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
