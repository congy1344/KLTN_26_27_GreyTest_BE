package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.Endpoint;

public interface EndpointRepository extends JpaRepository<Endpoint, Long> {
    List<Endpoint> findByMethodId(Long methodId);
}
