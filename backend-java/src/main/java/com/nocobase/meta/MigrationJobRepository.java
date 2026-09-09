package com.nocobase.meta;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MigrationJobRepository extends JpaRepository<MigrationJobEntity, UUID> {
    List<MigrationJobEntity> findByCollectionNameOrderByCreatedAtDesc(String collectionName);
}
