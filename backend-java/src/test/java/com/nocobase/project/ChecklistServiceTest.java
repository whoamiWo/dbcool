package com.nocobase.project;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checklist 服务单元测试。
 */
@DataJpaTest
@ActiveProfiles("test")
class ChecklistServiceTest {

    @Autowired
    private CardChecklistRepository checklistRepository;

    @Autowired
    private CardChecklistItemRepository checklistItemRepository;

    @Test
    void shouldCreateChecklistAndItems() {
        // Given
        Instant now = Instant.now();
        CardChecklistEntity checklist = new CardChecklistEntity();
        checklist.setId(UUID.randomUUID());
        checklist.setTenantId("test-tenant");
        checklist.setTaskId(UUID.randomUUID());
        checklist.setTitle("Test Checklist");
        checklist.setSortOrder(0);
        checklist.setCreatedAt(now);

        CardChecklistItemEntity item1 = new CardChecklistItemEntity();
        item1.setId(UUID.randomUUID());
        item1.setTenantId("test-tenant");
        item1.setChecklistId(checklist.getId());
        item1.setTaskId(checklist.getTaskId());
        item1.setTitle("Item 1");
        item1.setDone(false);
        item1.setSortOrder(0);
        item1.setCreatedAt(now);

        CardChecklistItemEntity item2 = new CardChecklistItemEntity();
        item2.setId(UUID.randomUUID());
        item2.setTenantId("test-tenant");
        item2.setChecklistId(checklist.getId());
        item2.setTaskId(checklist.getTaskId());
        item2.setTitle("Item 2");
        item2.setDone(true);
        item2.setSortOrder(1);
        item2.setCreatedAt(now);

        // When
        checklistRepository.save(checklist);
        checklistItemRepository.save(item1);
        checklistItemRepository.save(item2);

        // Then
        List<CardChecklistItemEntity> items = 
            checklistItemRepository.findByTenantIdAndChecklistIdOrderBySortOrderAsc(
                "test-tenant", checklist.getId());
        
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getTitle()).isEqualTo("Item 1");
        assertThat(items.get(0).getDone()).isFalse();
        assertThat(items.get(1).getTitle()).isEqualTo("Item 2");
        assertThat(items.get(1).getDone()).isTrue();
    }

    @Test
    void shouldBatchFindChecklistItems() {
        // Given
        Instant now = Instant.now();
        UUID checklistId1 = UUID.randomUUID();
        UUID checklistId2 = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        
        CardChecklistItemEntity item1 = new CardChecklistItemEntity();
        item1.setId(UUID.randomUUID());
        item1.setTenantId("test-tenant");
        item1.setChecklistId(checklistId1);
        item1.setTaskId(taskId);
        item1.setTitle("Item 1");
        item1.setDone(false);
        item1.setSortOrder(0);
        item1.setCreatedAt(now);

        CardChecklistItemEntity item2 = new CardChecklistItemEntity();
        item2.setId(UUID.randomUUID());
        item2.setTenantId("test-tenant");
        item2.setChecklistId(checklistId2);
        item2.setTaskId(taskId);
        item2.setTitle("Item 2");
        item2.setDone(true);
        item2.setSortOrder(0);
        item2.setCreatedAt(now);

        checklistItemRepository.save(item1);
        checklistItemRepository.save(item2);

        // When
        List<CardChecklistItemEntity> items = 
            checklistItemRepository.findByTenantIdAndChecklistIdIn(
                "test-tenant", List.of(checklistId1, checklistId2));

        // Then
        assertThat(items).hasSize(2);
    }
}