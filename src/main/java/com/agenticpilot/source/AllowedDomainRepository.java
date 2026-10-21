package com.agenticpilot.source;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllowedDomainRepository extends JpaRepository<AllowedDomain, UUID> {

    List<AllowedDomain> findAllByOrderByDomain();

    boolean existsByDomain(String domain);
}
