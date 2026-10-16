package vn.nutrimom.pregnancy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record PregnancyBabyContentResponse(
        List<BigDecimal> lengthCmRange,
        @JsonProperty("weight_g_range") List<Integer> weightGRange,
        String comparisonLabel) {
    /** Retains the earlier Jackson acronym spelling for existing API consumers. */
    @JsonProperty("weight_grange")
    public List<Integer> legacyWeightRange() {
        return weightGRange;
    }
}
