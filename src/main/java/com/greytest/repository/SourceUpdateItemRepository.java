package com.greytest.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.enums.SourceUpdateTargetType;

@Repository
public interface SourceUpdateItemRepository extends JpaRepository<SourceUpdateItem, Long> {
    List<SourceUpdateItem> findBySourceUpdateIdOrderByTargetTypeAscIdAsc(Long sourceUpdateId);
    List<SourceUpdateItem> findBySourceUpdateIdAndTargetType(Long sourceUpdateId, SourceUpdateTargetType targetType);
    Optional<SourceUpdateItem> findBySourceUpdateIdAndTargetTypeAndTargetId(Long sourceUpdateId, SourceUpdateTargetType targetType, Long targetId);
    Optional<SourceUpdateItem> findBySourceUpdateIdAndTargetKey(Long sourceUpdateId, String targetKey);
    void deleteBySourceUpdateId(Long sourceUpdateId);
}
