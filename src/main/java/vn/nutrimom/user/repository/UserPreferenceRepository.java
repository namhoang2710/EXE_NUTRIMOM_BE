package vn.nutrimom.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.user.domain.UserPreferenceEntity;

public interface UserPreferenceRepository extends JpaRepository<UserPreferenceEntity, String> { }
