package com.greytest.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.AuthUser;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long> {
    Optional<AuthUser> findByEmailIgnoreCase(String email);
}
