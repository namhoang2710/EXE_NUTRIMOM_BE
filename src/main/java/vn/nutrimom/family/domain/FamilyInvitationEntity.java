package vn.nutrimom.family.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "family_invitations", schema = "app")
public class FamilyInvitationEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "family_group_id", nullable = false, length = 36)
    private String familyGroupId;

    @Column(name = "invited_phone", length = 20)
    private String invitedPhone;

    @Column(name = "invited_email", length = 255)
    private String invitedEmail;

    @Column(name = "token_hash", nullable = false, length = 64,
            columnDefinition = "char(64)")
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, length = 30)
    private FamilyRelationship relationship;

    @Convert(converter = FamilyScopeSetConverter.class)
    @Column(name = "scopes", nullable = false, columnDefinition = "nvarchar(max)")
    private Set<FamilyScope> scopes = EnumSet.noneOf(FamilyScope.class);

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public String getId() { return id; }
    public String getFamilyGroupId() { return familyGroupId; }
    public void setFamilyGroupId(String value) { familyGroupId = value; }
    public String getInvitedPhone() { return invitedPhone; }
    public void setInvitedPhone(String value) { invitedPhone = value; }
    public String getInvitedEmail() { return invitedEmail; }
    public void setInvitedEmail(String value) { invitedEmail = value; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String value) { tokenHash = value; }
    public FamilyRelationship getRelationship() { return relationship; }
    public void setRelationship(FamilyRelationship value) { relationship = value; }
    public Set<FamilyScope> getScopes() {
        return scopes.isEmpty() ? EnumSet.noneOf(FamilyScope.class) : EnumSet.copyOf(scopes);
    }
    public void setScopes(Set<FamilyScope> values) {
        scopes = values == null || values.isEmpty()
                ? EnumSet.noneOf(FamilyScope.class)
                : EnumSet.copyOf(values);
    }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime value) { expiresAt = value; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime value) { acceptedAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
