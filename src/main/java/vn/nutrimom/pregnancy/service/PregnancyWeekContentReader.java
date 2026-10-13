package vn.nutrimom.pregnancy.service;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;
import vn.nutrimom.pregnancy.repository.PregnancyWeekContentRepository;

/** Isolates the optional weekly read from the dashboard's transaction. */
@Service
public class PregnancyWeekContentReader {
    private final PregnancyWeekContentRepository contents;

    public PregnancyWeekContentReader(PregnancyWeekContentRepository contents) {
        this.contents = contents;
    }

    /**
     * Query failures finish rolling back here before the dashboard applies its fallback.
     * The returned entity contains only basic columns; no lazy associations are accessed
     * after this read transaction closes.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PregnancyWeekContentEntity> findReviewedWeek(int week) {
        return contents.findById(week)
                .filter(content -> "REVIEWED".equalsIgnoreCase(content.getReviewStatus()));
    }
}
