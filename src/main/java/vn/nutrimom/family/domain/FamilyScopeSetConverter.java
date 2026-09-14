package vn.nutrimom.family.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Converter
public class FamilyScopeSetConverter implements AttributeConverter<Set<FamilyScope>, String> {
    @Override
    public String convertToDatabaseColumn(Set<FamilyScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "";
        }
        return scopes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    @Override
    public Set<FamilyScope> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) {
            return EnumSet.noneOf(FamilyScope.class);
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(FamilyScope::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FamilyScope.class)));
    }
}
