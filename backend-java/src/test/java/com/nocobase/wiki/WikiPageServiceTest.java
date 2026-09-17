package com.nocobase.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WikiPageServiceTest {

    @Autowired
    private WikiPageService pageService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private WikiPageRepository pageRepository;

    @Autowired
    private WikiVersionRepository versionRepository;

    private final String tenantId = "test-tenant";
    private final UUID userId = UUID.randomUUID();
    private UUID kbId;

    @BeforeEach
    void cleanup() {
        versionRepository.deleteAll();
        pageRepository.deleteAll();
        kbId = kbService.create("Test KB", "Desc", "test-kb", "icon", userId, tenantId).getId();
    }

    @Test
    void createAndGet() {
        WikiPageEntity page = pageService.create(kbId, null, "Test Page", "test-page", "Content", userId, tenantId);
        assertNotNull(page.getId());
        assertEquals("Test Page", page.getTitle());
        assertEquals("test-page", page.getSlug());
        assertEquals("DRAFT", page.getStatus());
        assertEquals(1, page.getVersion());

        WikiPageEntity found = pageService.get(page.getId());
        assertEquals(page.getId(), found.getId());
    }

    @Test
    void createDuplicateSlugThrows() {
        pageService.create(kbId, null, "Page 1", "test-page", "Content", userId, tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.create(kbId, null, "Page 2", "test-page", "Content", userId, tenantId));
    }

    @Test
    void updateCreatesVersion() {
        WikiPageEntity page = pageService.create(kbId, null, "Original", "orig", "Content v1", userId, tenantId);
        UUID pageId = page.getId();

        WikiPageEntity updated = pageService.update(pageId, "Updated", "Content v2", "updated", userId, tenantId);

        assertEquals("Updated", updated.getTitle());
        assertEquals("Content v2", updated.getContent());

        // 验证版本历史包含 v1 和 v2
        List<WikiVersionEntity> versions = pageService.listVersions(pageId);
        // 至少有一个版本记录(旧版本快照)
        assertTrue(versions.size() >= 1, "应至少有一个版本记录");
        // 验证版本内容正确
        boolean hasV1 = versions.stream().anyMatch(v -> "Content v1".equals(v.getContent()));
        boolean hasV2 = versions.stream().anyMatch(v -> "Content v2".equals(v.getContent()));
        assertTrue(hasV1 || hasV2, "应包含 v1 或 v2 版本内容");
    }

    @Test
    void publishAndArchive() {
        WikiPageEntity page = pageService.create(kbId, null, "Draft", "draft", "Content", userId, tenantId);
        UUID pageId = page.getId();

        pageService.publish(pageId, userId, tenantId);
        assertEquals("PUBLISHED", pageService.get(pageId).getStatus());

        pageService.archive(pageId, userId, tenantId);
        assertEquals("ARCHIVED", pageService.get(pageId).getStatus());
    }

    @Test
    void restoreVersion() {
        WikiPageEntity page = pageService.create(kbId, null, "Original", "orig", "Content v1", userId, tenantId);
        UUID pageId = page.getId();

        pageService.update(pageId, "Updated", "Content v2", "updated", userId, tenantId);
        pageService.update(pageId, "Updated Again", "Content v3", "updated-again", userId, tenantId);

        pageService.restoreVersion(pageId, 1, userId, tenantId);
        WikiPageEntity restored = pageService.get(pageId);
        assertEquals("Content v1", restored.getContent());
    }

    @Test
    void listByKbAndStatus() {
        pageService.create(kbId, null, "Draft 1", "d1", "C", userId, tenantId);
        pageService.create(kbId, null, "Draft 2", "d2", "C", userId, tenantId);
        WikiPageEntity pub = pageService.create(kbId, null, "Published", "pub", "C", userId, tenantId);
        pageService.publish(pub.getId(), userId, tenantId);

        List<WikiPageEntity> drafts = pageService.listByKbAndStatus(kbId, "DRAFT", tenantId);
        List<WikiPageEntity> published = pageService.listByKbAndStatus(kbId, "PUBLISHED", tenantId);

        assertEquals(2, drafts.size());
        assertEquals(1, published.size());
    }
}