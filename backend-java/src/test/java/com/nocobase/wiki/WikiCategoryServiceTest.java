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
class WikiCategoryServiceTest {

    @Autowired
    private WikiCategoryService categoryService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private WikiCategoryRepository categoryRepository;

    private final String tenantId = "test-tenant";
    private final UUID userId = UUID.randomUUID();
    private UUID kbId;

    @BeforeEach
    void cleanup() {
        categoryRepository.deleteAll();
        kbId = kbService.create("Test KB", "Desc", "test-kb", "icon", userId, tenantId).getId();
    }

    @Test
    void createAndTree() {
        WikiCategoryEntity c1 = categoryService.create(kbId, null, "Category 1", "cat1", tenantId);
        WikiCategoryEntity c2 = categoryService.create(kbId, c1.getId(), "Subcategory", "subcat", tenantId);
        WikiCategoryEntity c3 = categoryService.create(kbId, null, "Category 2", "cat2", tenantId);

        List<WikiCategoryEntity> tree = categoryService.tree(kbId);
        assertEquals(3, tree.size());

        // 验证层级关系
        WikiCategoryEntity foundC1 = tree.stream().filter(c -> "cat1".equals(c.getSlug())).findFirst().orElse(null);
        assertNotNull(foundC1);
        assertNull(foundC1.getParentId());

        WikiCategoryEntity foundSub = tree.stream().filter(c -> "subcat".equals(c.getSlug())).findFirst().orElse(null);
        assertNotNull(foundSub);
        assertEquals(c1.getId(), foundSub.getParentId());
    }

    @Test
    void createDuplicateSlugThrows() {
        categoryService.create(kbId, null, "Cat 1", "cat1", tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> categoryService.create(kbId, null, "Cat 2", "cat1", tenantId));
    }

    @Test
    void update() {
        WikiCategoryEntity c = categoryService.create(kbId, null, "Original", "orig", tenantId);
        WikiCategoryEntity updated = categoryService.update(c.getId(), "Updated", "updated", null, tenantId);
        assertEquals("Updated", updated.getName());
        assertEquals("updated", updated.getSlug());
    }

    @Test
    void delete() {
        WikiCategoryEntity c = categoryService.create(kbId, null, "To Delete", "del", tenantId);
        categoryService.delete(c.getId(), tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> categoryService.get(c.getId()));
    }
}