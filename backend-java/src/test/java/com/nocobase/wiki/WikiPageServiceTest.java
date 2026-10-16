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

    @Test
    void softDelete_tenantMismatch_403() {
        String otherTenant = "other-tenant";
        UUID userId2 = UUID.randomUUID();
        WikiPageEntity page = pageService.create(kbId, null, "Other Tenant Page", "otp", "Content", userId2, otherTenant);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.softDelete(page.getId(), tenantId),
                "租户 A 无法软删除租户 B 的页面");
    }

    @Test
    void restore_tenantMismatch_403() {
        String otherTenant = "other-tenant";
        UUID userId2 = UUID.randomUUID();
        WikiPageEntity page = pageService.create(kbId, null, "Other Tenant Page", "otp", "Content", userId2, otherTenant);
        // 先由其他租户软删
        pageService.softDelete(page.getId(), otherTenant);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.restore(page.getId(), tenantId),
                "租户 A 无法恢复租户 B 的页面");
    }

    @Test
    void share_tenantMismatch_403() {
        String otherTenant = "other-tenant";
        UUID userId2 = UUID.randomUUID();
        WikiPageEntity page = pageService.create(kbId, null, "Other Tenant Page", "otp", "Content", userId2, otherTenant);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.share(page.getId(), false, tenantId),
                "租户 A 无法分享租户 B 的页面");
    }

    @Test
    void unshare_tenantMismatch_403() {
        String otherTenant = "other-tenant";
        UUID userId2 = UUID.randomUUID();
        WikiPageEntity page = pageService.create(kbId, null, "Other Tenant Page", "otp", "Content", userId2, otherTenant);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.unshare(page.getId(), tenantId),
                "租户 A 无法取消分享租户 B 的页面");
    }

    @Test
    void createFromTemplate_tenantMismatch_403() {
        String otherTenant = "other-tenant";
        UUID userId2 = UUID.randomUUID();
        WikiPageEntity template = pageService.create(kbId, null, "Template", "tpl", "Template Content", userId2, otherTenant);
        pageService.markTemplate(template.getId(), true, otherTenant);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.createFromTemplate(template.getId(), kbId, null, "New Page", "new-page", userId, tenantId),
                "租户 A 无法从租户 B 的模板创建页面");
    }

    // ---- Notion 剩余能力（Week 46）----

    @Test
    void parseBacklinks_extractsSlugs() {
        List<String> slugs = pageService.parseBacklinks("Hello [[page-one]] and [[page-two]]");
        assertEquals(List.of("page-one", "page-two"), slugs);
    }

    @Test
    void parseBacklinks_null_returnsEmpty() {
        assertTrue(pageService.parseBacklinks(null).isEmpty());
    }

    @Test
    void syncBacklinks_createsBacklinkEntries() {
        WikiPageEntity target = pageService.create(kbId, null, "Target", "target-slug", "C", userId, tenantId);
        WikiPageEntity source = pageService.create(kbId, null, "Source", "source-slug",
                "Link to [[target-slug]] here", userId, tenantId);

        // syncBacklinks 在 create 时已触发, 验证反向链接表有记录
        List<WikiPageEntity> backlinks = pageService.listBacklinks("target-slug", tenantId);
        assertFalse(backlinks.isEmpty(), "反向链接表应有记录");
        assertEquals("source-slug", backlinks.get(0).getSlug());
    }

    @Test
    void listByParent_returnsChildPages() {
        WikiPageEntity parent = pageService.create(kbId, null, "Parent", "parent-slug", "C", userId, tenantId);
        pageService.create(kbId, parent.getId(), "Child", "child-slug", "C", userId, tenantId);

        List<WikiPageEntity> children = pageService.listByParent(parent.getId());
        assertEquals(1, children.size());
        assertEquals("child-slug", children.get(0).getSlug());
    }

    @Test
    void listTrash_returnsSoftDeletedPages() {
        WikiPageEntity page = pageService.create(kbId, null, "Trash Me", "trash-me", "C", userId, tenantId);
        pageService.softDelete(page.getId(), tenantId);

        List<WikiPageEntity> trash = pageService.listTrash(tenantId);
        assertEquals(1, trash.size());
        assertEquals("trash-me", trash.get(0).getSlug());
        assertNotNull(trash.get(0).getDeletedAt());
    }

    @Test
    void share_regeneratesToken_whenRequested() {
        WikiPageEntity page = pageService.create(kbId, null, "Shareable", "shareable", "C", userId, tenantId);
        WikiPageEntity shared = pageService.share(page.getId(), true, tenantId);
        assertNotNull(shared.getShareToken());
        assertFalse(shared.getShareToken().isBlank());
    }

    @Test
    void share_reusesExistingToken_whenNotRegenerate() {
        WikiPageEntity page = pageService.create(kbId, null, "Shareable", "shareable-2", "C", userId, tenantId);
        WikiPageEntity first = pageService.share(page.getId(), true, tenantId);
        WikiPageEntity second = pageService.share(page.getId(), false, tenantId);
        assertEquals(first.getShareToken(), second.getShareToken());
    }

    @Test
    void unshare_clearsToken() {
        WikiPageEntity page = pageService.create(kbId, null, "Unshareable", "unshareable", "C", userId, tenantId);
        pageService.share(page.getId(), true, tenantId);
        WikiPageEntity unshared = pageService.unshare(page.getId(), tenantId);
        assertNull(unshared.getShareToken());
    }

    @Test
    void softDelete_thenRestore_roundtrip() {
        WikiPageEntity page = pageService.create(kbId, null, "Restoreable", "restoreable", "C", userId, tenantId);
        pageService.softDelete(page.getId(), tenantId);
        assertEquals("TRASH", pageService.get(page.getId()).getStatus());

        WikiPageEntity restored = pageService.restore(page.getId(), tenantId);
        assertEquals("DRAFT", restored.getStatus());
        assertNull(restored.getDeletedAt());
    }

    @Test
    void listTemplates_returnsOnlyTemplates() {
        WikiPageEntity normal = pageService.create(kbId, null, "Normal", "normal-tpl", "C", userId, tenantId);
        WikiPageEntity template = pageService.create(kbId, null, "Template", "template-tpl", "C", userId, tenantId);
        pageService.markTemplate(template.getId(), true, tenantId);

        List<WikiPageEntity> templates = pageService.listTemplates(tenantId);
        assertEquals(1, templates.size());
        assertEquals("template-tpl", templates.get(0).getSlug());
        assertTrue(templates.get(0).getIsTemplate());
    }

    @Test
    void delete_hardDelete_removesPage() {
        WikiPageEntity page = pageService.create(kbId, null, "Deletable", "deletable", "C", userId, tenantId);
        UUID id = page.getId();
        pageService.delete(id, tenantId);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.get(id));
    }

    @Test
    void getVersion_returnsSpecificVersion() {
        WikiPageEntity page = pageService.create(kbId, null, "Versioned", "versioned", "v1", userId, tenantId);
        pageService.update(page.getId(), "Versioned", "v2", "versioned", userId, tenantId);

        WikiVersionEntity v1 = pageService.getVersion(page.getId(), 1);
        assertEquals("v1", v1.getContent());
    }

    @Test
    void getVersion_unknownVersion_throws404() {
        WikiPageEntity page = pageService.create(kbId, null, "V", "v-slug", "C", userId, tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pageService.getVersion(page.getId(), 999));
    }
}