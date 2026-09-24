package com.nocobase.view;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.meta.CollectionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 视图服务 — Week 9 Epic 3.
 */
@Service
public class ViewService {

    private final ViewRepository repository;
    private final ObjectMapper objectMapper;
    private final com.nocobase.meta.CollectionService collectionService;

    public ViewService(ViewRepository repository, ObjectMapper objectMapper,
                       com.nocobase.meta.CollectionService collectionService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.collectionService = collectionService;
    }

    @Transactional
    public ViewEntity create(
            String collectionName, String name, String title,
            ViewEntity.Type type, String configJson, String sharedWithJson,
            String tenantId, UUID createdBy
    ) {
        ViewEntity view = new ViewEntity();
        view.setId(UUID.randomUUID());
        view.setCollectionName(collectionName);
        view.setName(name);
        view.setTitle(title);
        view.setType(type);
        view.setConfigJson(configJson == null || configJson.isBlank() ? "{}" : configJson);
        view.setSharedWithJson(sharedWithJson == null || sharedWithJson.isBlank() ? "[]" : sharedWithJson);
        view.setTenantId(tenantId);
        view.setCreatedAt(Instant.now());
        view.setCreatedBy(createdBy);
        return repository.save(view);
    }

    @Transactional
    public ViewEntity update(
            UUID id, String name, String title,
            String configJson, String sharedWithJson, String tenantId
    ) {
        ViewEntity view = get(id, tenantId);
        if (name != null) view.setName(name);
        if (title != null) view.setTitle(title);
        if (configJson != null) view.setConfigJson(configJson);
        if (sharedWithJson != null) view.setSharedWithJson(sharedWithJson);
        view.setUpdatedAt(Instant.now());
        return repository.save(view);
    }

    public ViewEntity get(UUID id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "View 不存在"));
    }

    public List<ViewEntity> listByCollection(String collectionName, String tenantId) {
        return repository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(collectionName, tenantId);
    }

    public List<ViewEntity> listAll(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        ViewEntity view = get(id, tenantId);
        repository.delete(view);
    }

    public Map<String, Object> parseConfig(ViewEntity view) {
        try {
            return objectMapper.readValue(view.getConfigJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("config 解析失败", e);
        }
    }

    /**
     * 时间线视图专用查询 — 按开始/结束日期字段排序 + 区间过滤。
     *
     * <p>config 支持字段：
     * <ul>
     *   <li>dateField / startDateField / endDateField：时间字段名</li>
     *   <li>sortDirection：asc / desc（默认 asc）</li>
     *   <li>filterStart / filterEnd：区间过滤（ISO 日期字符串，可选）</li>
     * </ul>
     * 排序表达式转为后端 CollectionService 支持的 `field,-field` 逗号格式。
     */
    public List<Map<String, Object>> listTimelineRecords(
            ViewEntity view,
            int limit,
            String filterStart,
            String filterEnd
    ) {
        Map<String, Object> cfg = parseConfig(view);
        String dateField = (String) cfg.getOrDefault("dateField", "created_at");
        String startDateField = (String) cfg.getOrDefault("startDateField", dateField);
        String endDateField = (String) cfg.getOrDefault("endDateField", dateField);
        String sortDirection = String.valueOf(cfg.getOrDefault("sortDirection", "asc"));

        // 转为后端 sortExpr 格式：逗号分隔，- 前缀表示 DESC
        String sortExpr = "-".equalsIgnoreCase(sortDirection) ? "-" + startDateField : startDateField;

        // 复用 CollectionService 已确认签名（:305 / :315）
        List<Map<String, Object>> records = collectionService.listRecords(
                view.getCollectionName(), view.getTenantId(), limit, sortExpr, null);

        // 区间过滤（应用层，因 DynamicTableManager 无动态日期过滤参数）
        if (filterStart != null && !filterStart.isBlank()) {
            records = records.stream()
                    .filter(r -> {
                        Object v = r.get(startDateField);
                        return v != null && v.toString().compareTo(filterStart) >= 0;
                    })
                    .toList();
        }
        if (filterEnd != null && !filterEnd.isBlank()) {
            records = records.stream()
                    .filter(r -> {
                        Object v = r.get(endDateField);
                        return v != null && v.toString().compareTo(filterEnd) <= 0;
                    })
                    .toList();
        }
        return records;
    }
}
