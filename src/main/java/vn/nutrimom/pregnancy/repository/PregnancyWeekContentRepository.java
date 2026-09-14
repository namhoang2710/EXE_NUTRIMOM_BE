package vn.nutrimom.pregnancy.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;

public interface PregnancyWeekContentRepository
        extends JpaRepository<PregnancyWeekContentEntity, Integer> { }
