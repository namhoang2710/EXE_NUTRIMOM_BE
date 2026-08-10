package vn.nutrimom.auth.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.nutrimom.auth.domain.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, String> {
    Optional<UserEntity> findByPhone(String phone);
    boolean existsByPhone(String phone);
}
