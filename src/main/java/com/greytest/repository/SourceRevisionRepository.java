package com.greytest.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.greytest.entity.SourceRevision;

@Repository
public interface SourceRevisionRepository extends JpaRepository<SourceRevision, Long> {
    List<SourceRevision> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    Optional<SourceRevision> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);
}
