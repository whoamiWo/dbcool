package com.nocobase.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Wiki Block 服务 — 管理 Block 树（增删移/排序）。
 *
 * <p>页面内容由整块 TEXT 改为 Block 树存储，支持 Notion 式层级结构。
 */
@Service
public class WikiBlockService {

    private final WikiBlockRepository blockRepository;
    private final WikiPageRepository pageRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WikiBlockService(WikiBlockRepository blockRepository, WikiPageRepository pageRepository) {
        this.blockRepository = blockRepository;
        this.pageRepository = pageRepository;
    }

    /** R4: 安全地将 content 转换为合法的 JSON 字符串。 */
    private String toJsonString(Object content) {
        if (content == null) {
            return "{}";
        }
        // 如果是 String，直接包裹成 {"text": "..."}
        if (content instanceof String str) {
            try {
                // 先尝试解析：如果已经是合法 JSON，直接返回
                Object parsed = objectMapper.readValue(str, Object.class);
                return objectMapper.writeValueAsString(parsed);
            } catch (JsonProcessingException e) {
                // 不是合法 JSON，包装成 {"text": "..."}
                try {
                    return objectMapper.writeValueAsString(Map.of("text", str));
                } catch (JsonProcessingException ex) {
                    return "{\"error\": \"serialization failed\"}";
                }
            }
        }
        // Map/List 等其他类型，直接序列化为 JSON
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * 为页面批量创建 Block 树（替换旧 content TEXT）。
     */
    @Transactional
    public List<WikiBlockEntity> createBlocksForPage(UUID pageId, List<Map<String, Object>> blocks,
                                                     String tenantId, UUID createdBy) {
        WikiPageEntity page = pageRepository.findById(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在"));
        if (!page.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
        }
        // 先删除旧 Block
        blockRepository.deleteByPageId(pageId);
        // 批量创建新 Block
        List<WikiBlockEntity> result = new ArrayList<>();
        int sortOrder = 0;
        for (Map<String, Object> b : blocks) {
            WikiBlockEntity entity = new WikiBlockEntity();
            entity.setId(UUID.randomUUID());
            entity.setPageId(pageId);
            entity.setParentId(null);
            entity.setType((String) b.getOrDefault("type", "paragraph"));
            entity.setContentJson(toJsonString(b.get("content")));
            entity.setSortOrder(sortOrder++);
            entity.setTenantId(tenantId);
            entity.setCreatedBy(createdBy);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            result.add(blockRepository.save(entity));
        }
        return result;
    }

    /**
     * 获取页面的 Block 树（按 sort_order 排序）。
     */
    public List<WikiBlockEntity> getBlocksByPageId(UUID pageId) {
        return blockRepository.findByPageIdOrderBySortOrderAsc(pageId);
    }

    /**
     * 移动 Block（换父/换排序）。
     */
    @Transactional
    public WikiBlockEntity moveBlock(UUID blockId, UUID newParentId, Integer newSortOrder,
                                      String tenantId) {
        WikiBlockEntity block = blockRepository.findById(blockId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Block 不存在"));
        if (!block.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
        }
        if (newParentId != null) {
            block.setParentId(newParentId);
        }
        if (newSortOrder != null) {
            block.setSortOrder(newSortOrder);
        }
        block.setUpdatedAt(Instant.now());
        return blockRepository.save(block);
    }

    /**
     * 删除页面的所有 Block。
     */
    @Transactional
    public void deleteBlocksByPageId(UUID pageId, String tenantId) {
        WikiPageEntity page = pageRepository.findById(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在"));
        if (!page.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
        }
        blockRepository.deleteByPageId(pageId);
    }

    /**
     * 重新排序 Block。
     */
    @Transactional
    public List<WikiBlockEntity> reorderBlocks(UUID pageId, List<UUID> blockIds, String tenantId) {
        WikiPageEntity page = pageRepository.findById(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在"));
        if (!page.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
        }
        List<WikiBlockEntity> result = new ArrayList<>();
        int sortOrder = 0;
        for (UUID blockId : blockIds) {
            WikiBlockEntity block = blockRepository.findById(blockId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Block 不存在: " + blockId));
            if (!block.getTenantId().equals(tenantId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
            }
            block.setSortOrder(sortOrder++);
            block.setUpdatedAt(Instant.now());
            result.add(blockRepository.save(block));
        }
        return result;
    }

    /**
     * PHASE 58 P0-3：批量 upsert Block（块编辑器自动保存专用）。
     *
     * 策略：delete-then-insert（与 createBlocksForPage 一致），单事务。
     * 页面不存在 → 404；租户不匹配 → 403；blocks 为空 → 400。
     */
    @Transactional
    public List<WikiBlockEntity> batchUpsertBlocks(UUID pageId, List<Map<String, Object>> blocks,
                                                    String tenantId, UUID createdBy) {
        if (pageId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pageId 不能为空");
        }
        if (blocks == null || blocks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "blocks 不能为空");
        }
        WikiPageEntity page = pageRepository.findById(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在"));
        if (!page.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
        }
        // 单事务内先删除旧 Block，再批量写入新 Block（顺序即列表顺序）
        blockRepository.deleteByPageId(pageId);
        List<WikiBlockEntity> result = new ArrayList<>();
        int sortOrder = 0;
        for (Map<String, Object> b : blocks) {
            WikiBlockEntity entity = new WikiBlockEntity();
            entity.setId(UUID.randomUUID());
            entity.setPageId(pageId);
            entity.setParentId(null);
            entity.setType((String) b.getOrDefault("type", "paragraph"));
            Object content = b.get("content");
            entity.setContentJson(toJsonString(content));
            entity.setSortOrder(sortOrder++);
            entity.setTenantId(tenantId);
            entity.setCreatedBy(createdBy);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            result.add(blockRepository.save(entity));
        }
        return result;
    }
}