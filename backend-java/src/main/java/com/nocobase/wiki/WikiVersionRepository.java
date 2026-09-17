package com.nocobase.wiki;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * 版本历史 Repository。
 */
@Repository
public interface WikiVersionRepository extends JpaRepository<WikiVersionEntity, UUID> {

    List<WikiVersionEntity> findByWikiPageIdOrderByVersionDesc(UUID wikiPageId);

    Optional<WikiVersionEntity> findByWikiPageIdAndVersion(UUID wikiPageId, Integer version);

    boolean existsByWikiPageIdAndVersion(UUID wikiPageId, Integer version);
}