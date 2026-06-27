package com.greytest.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.RelevantAnnotation;

public interface RelevantAnnotationRepository extends JpaRepository<RelevantAnnotation, Long> {
    List<RelevantAnnotation> findByClassId(Long classId);

    List<RelevantAnnotation> findByMethodId(Long methodId);

    List<RelevantAnnotation> findByClassIdIn(Collection<Long> classIds);

    List<RelevantAnnotation> findByMethodIdIn(Collection<Long> methodIds);
}
