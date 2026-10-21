package com.agenticpilot.source;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {

    List<DataSource> findAllByOrderByName();
}
