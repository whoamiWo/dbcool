package com.nocobase.view;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public ViewService(ViewRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
}
