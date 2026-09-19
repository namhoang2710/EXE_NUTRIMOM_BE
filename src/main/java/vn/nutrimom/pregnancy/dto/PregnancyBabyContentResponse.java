package vn.nutrimom.pregnancy.dto;

import java.math.BigDecimal;
import java.util.List;

public record PregnancyBabyContentResponse(
        List<BigDecimal> lengthCmRange,
        List<Integer> weightGRange,
        String comparisonLabel) { }
