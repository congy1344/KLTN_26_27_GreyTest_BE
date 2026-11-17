package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.greytest.entity.TierPackage;

/**
 * Repository thao tác với bảng tier_package trong cơ sở dữ liệu.
 */
@Repository
public interface TierPackageRepository extends JpaRepository<TierPackage, String> {
    List<TierPackage> findByActiveTrue();
}

