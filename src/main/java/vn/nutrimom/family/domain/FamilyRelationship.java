package vn.nutrimom.family.domain;

public enum FamilyRelationship {
    PARTNER,
    SPOUSE,
    PARENT,
    SIBLING,
    RELATIVE,
    FRIEND,
    OTHER;

    public FamilyMembershipRole membershipRole() {
        return this == PARTNER || this == SPOUSE
                ? FamilyMembershipRole.PARTNER
                : FamilyMembershipRole.FAMILY_MEMBER;
    }
}
