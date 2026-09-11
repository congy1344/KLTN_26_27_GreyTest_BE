package com.greytest.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import com.greytest.entity.SourceUpdate;
import com.greytest.entity.enums.SourceUpdateStatus;

@Repository
public interface SourceUpdateRepository extends JpaRepository<SourceUpdate, Long> {
    List<SourceUpdate> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    Optional<SourceUpdate> findFirstByProjectIdAndStatusNotIn(Long projectId, List<SourceUpdateStatus> terminalStatuses);
    Optional<SourceUpdate> findByIdAndProjectId(Long id, Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from SourceUpdate u where u.id = :id and u.projectId = :projectId")
    Optional<SourceUpdate> findByIdAndProjectIdForUpdate(
            @Param("id") Long id, @Param("projectId") Long projectId);
}
