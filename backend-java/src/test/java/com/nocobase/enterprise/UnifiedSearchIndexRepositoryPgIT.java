package com.nocobase.enterprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nocobase.im.MessageService;
import com.nocobase.search.UnifiedSearchIndexEntity;
import com.nocobase.search.UnifiedSearchIndexRepository;
import com.nocobase.search.UnifiedSearchService;
import com.nocobase.wiki.WikiPageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A0-4：真实 JPA repository + 真实 PG 的索引 upsert / content_tsv 验收。
 *
 * <p>{@code EnterpriseSuitePgIT} 用 mock repository 桥接 SQL；本测试补齐真实
 * {@link UnifiedSearchIndexRepository}（Spring Data JPA）+ 真实 {@link UnifiedSearchService}，
 * 证明生产写入链路本身（先查后写 upsert）不会触发唯一键，且触发器生成的 tsvector 可检索。
 */
@Tag("pg")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UnifiedSearchIndexRepositoryPgIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TENANT = "tenant_pg_repo";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    private UnifiedSearchIndexRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        jdbc.execute("DROP TABLE IF EXISTS unified_search_index");
        jdbc.execute("""
                CREATE TABLE unified_search_index (
                    id UUID PRIMARY KEY,
                    entity_type VARCHAR(64) NOT NULL,
                    entity_id VARCHAR(256) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    title VARCHAR(512),
                    content TEXT,
                    content_tsv tsvector,
                    metadata JSONB DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    updated_at TIMESTAMPTZ DEFAULT NOW(),
                    UNIQUE(entity_type, entity_id, tenant_id)
                )""");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION update_search_vector() RETURNS TRIGGER AS $$
                BEGIN
                    NEW.content_tsv := to_tsvector('simple',
                        COALESCE(NEW.title, '') || ' ' || COALESCE(NEW.content, ''));
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbc.execute("""
                CREATE TRIGGER trg_search_vector BEFORE INSERT OR UPDATE
                ON unified_search_index FOR EACH ROW
                EXECUTE FUNCTION update_search_vector()""");
    }

    private UnifiedSearchService service() {
        return new UnifiedSearchService(
                mock(MessageService.class),
                mock(WikiPageService.class),
                mock(com.nocobase.meta.CollectionService.class),
                mock(com.nocobase.automation.AutomationRuleService.class),
                mock(com.nocobase.project.ProjectService.class),
                repository,
                mock(com.nocobase.im.ImChannelMemberRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.nocobase.auth.AclEnforcer.class),
                mock(com.nocobase.acl.RowAclService.class),
                mock(com.nocobase.auth.RoleRepository.class),
                mock(com.nocobase.wiki.WikiPermissionService.class),
                mock(com.nocobase.approval.ApprovalRequestRepository.class),
                mock(com.nocobase.approval.ApprovalCcRepository.class),
                mock(com.nocobase.workflow.WorkflowTaskRepository.class),
                mock(com.nocobase.approval.ApprovalVisibilityService.class));
    }

    @Test
    @DisplayName("真实 repository：indexEntity 两次 upsert，不唯一键冲突，tsvector 可查")
    void realRepository_upsertAndSearchable() {
        UnifiedSearchService service = service();
        String id = java.util.UUID.randomUUID().toString();

        service.indexEntity("wiki", id, TENANT, "年度目标", "季度预算与采购计划",
                Map.of("kind", "wiki"));
        List<UnifiedSearchIndexEntity> first =
                repository.searchByKeywordAndType("wiki", "年度目标", TENANT, 10);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getTitle()).isEqualTo("年度目标");

        // 同 (type,id,tenant) 第二次写入：生产 upsert 不得触发唯一键
        service.indexEntity("wiki", id, TENANT, "新标题", "预算更新", Map.of());
        List<UnifiedSearchIndexEntity> second =
                repository.searchByKeywordAndType("wiki", "新标题", TENANT, 10);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getTitle()).isEqualTo("新标题");
        assertThat(repository.count()).isEqualTo(1);
    }
}
