package com.nocobase.org;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 组织架构服务（E1）。
 *
 * <p>核心约定：
 * <ul>
 *   <li>{@code path} 物化路径用 <b>部门 id</b> 拼接，改部门名不会触发子树重写。</li>
 *   <li>外部来源（钉钉/企微/飞书）只能通过 {@link #upsertExternal} 幂等写入，
 *       不允许本地编辑（{@link #assertLocalEditable}）。</li>
 *   <li>移动节点时必须重算整棵子树的 path（否则子树查询断裂）。</li>
 * </ul>
 */
@Service
public class OrgUnitService {

    private static final Logger log = LoggerFactory.getLogger(OrgUnitService.class);

    private final OrgUnitRepository repository;
    private final UserRepository userRepository;

    public OrgUnitService(OrgUnitRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    // ============================================================
    //  查询
    // ============================================================

    public List<OrgUnitEntity> list(String tenantId) {
        return repository.findByTenantIdAndDeletedFalseOrderBySortAscNameAsc(tenantId);
    }

    public OrgUnitEntity get(UUID id, String tenantId) {
        return repository.findByIdAndTenantIdAndDeletedFalse(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在: " + id));
    }

    /** 返回部门树（嵌套 maps，供前端直接渲染）。 */
    public List<Map<String, Object>> tree(String tenantId) {
        List<OrgUnitEntity> all = list(tenantId);
        Map<UUID, List<OrgUnitEntity>> byParent = new HashMap<>();
        List<OrgUnitEntity> roots = new ArrayList<>();
        for (OrgUnitEntity u : all) {
            if (u.getParentId() == null) roots.add(u);
            else byParent.computeIfAbsent(u.getParentId(), k -> new ArrayList<>()).add(u);
        }
        Comparator<OrgUnitEntity> cmp = Comparator
                .comparingInt(OrgUnitEntity::getSort).thenComparing(OrgUnitEntity::getName);
        roots.sort(cmp);
        return roots.stream().map(r -> toNode(r, byParent, cmp)).toList();
    }

    private Map<String, Object> toNode(OrgUnitEntity u, Map<UUID, List<OrgUnitEntity>> byParent,
                                       Comparator<OrgUnitEntity> cmp) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", u.getId().toString());
        node.put("name", u.getName());
        node.put("code", u.getCode());
        node.put("parentId", u.getParentId() == null ? null : u.getParentId().toString());
        node.put("source", u.getSource());
        node.put("sort", u.getSort());
        node.put("editable", u.isLocal());
        List<OrgUnitEntity> children = new ArrayList<>(byParent.getOrDefault(u.getId(), List.of()));
        children.sort(cmp);
        node.put("children", children.stream().map(c -> toNode(c, byParent, cmp)).toList());
        return node;
    }

    /** 部门成员（含关键字过滤，手机号是否返回由调用方按角色决定 —— 决策 D7）。 */
    public List<UserEntity> members(String tenantId, UUID unitId, String keyword, boolean includeDescendants) {
        List<UserEntity> candidates;
        if (unitId == null) {
            candidates = userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        } else {
            OrgUnitEntity unit = get(unitId, tenantId);
            if (includeDescendants) {
                List<UUID> ids = repository
                        .findByTenantIdAndPathStartingWithAndDeletedFalse(tenantId, unit.getPath())
                        .stream().map(OrgUnitEntity::getId).toList();
                candidates = userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                        .filter(u -> u.getDepartmentId() != null && ids.contains(u.getDepartmentId()))
                        .toList();
            } else {
                candidates = userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                        .filter(u -> unitId.equals(u.getDepartmentId()))
                        .toList();
            }
        }
        if (keyword == null || keyword.isBlank()) return candidates;
        String kw = keyword.trim().toLowerCase();
        return candidates.stream()
                .filter(u -> contains(u.getDisplayName(), kw)
                        || contains(u.getUsername(), kw)
                        || contains(u.getEmail(), kw)
                        || contains(u.getEmployeeNo(), kw))
                .toList();
    }

    private static boolean contains(String v, String kw) {
        return v != null && v.toLowerCase().contains(kw);
    }

    // ============================================================
    //  写入（外部同步幂等）
    // ============================================================

    /**
     * 外部平台部门幂等 upsert。
     *
     * @param parentExternalId 上级部门的外部 id；根部门传 null
     */
    @Transactional
    public OrgUnitEntity upsertExternal(String tenantId, String source, String externalId,
                                        String name, String parentExternalId, int sort) {
        UUID parentId = null;
        if (parentExternalId != null && !parentExternalId.isBlank()) {
            parentId = repository
                    .findByTenantIdAndSourceAndExternalIdAndDeletedFalse(tenantId, source, parentExternalId)
                    .map(OrgUnitEntity::getId).orElse(null);
        }
        OrgUnitEntity unit = repository
                .findByTenantIdAndSourceAndExternalIdAndDeletedFalse(tenantId, source, externalId)
                .orElse(null);
        boolean created = false;
        if (unit == null) {
            unit = new OrgUnitEntity();
            unit.setId(UUID.randomUUID());
            unit.setTenantId(tenantId);
            unit.setSource(source);
            unit.setExternalId(externalId);
            unit.setCreatedAt(Instant.now());
            created = true;
        }
        unit.setName(name == null || name.isBlank() ? externalId : name);
        unit.setSort(sort);
        boolean parentChanged = !java.util.Objects.equals(unit.getParentId(), parentId);
        unit.setParentId(parentId);
        unit.setUpdatedAt(Instant.now());
        if (created || parentChanged) {
            unit.setPath(computePath(unit, parentId));
        }
        OrgUnitEntity saved = repository.save(unit);
        if (parentChanged && !created) {
            rewriteSubtreePaths(saved);
        }
        return saved;
    }

    /**
     * 本地新建部门。父节点必须是 LOCAL 子树（外部来源子树只读）。
     */
    @Transactional
    public OrgUnitEntity createLocal(String tenantId, UUID parentId, String name, String code) {
        if (parentId != null) {
            assertLocalEditable(get(parentId, tenantId));
        }
        OrgUnitEntity unit = new OrgUnitEntity();
        unit.setId(UUID.randomUUID());
        unit.setTenantId(tenantId);
        unit.setParentId(parentId);
        unit.setName(name);
        unit.setCode(code);
        unit.setSource(OrgUnitEntity.SOURCE_LOCAL);
        unit.setSort(0);
        unit.setPath(computePath(unit, parentId));
        unit.setCreatedAt(Instant.now());
        unit.setUpdatedAt(Instant.now());
        return repository.save(unit);
    }

    /** 本地改名（外部来源部门禁止）。 */
    @Transactional
    public OrgUnitEntity rename(UUID id, String tenantId, String name) {
        OrgUnitEntity unit = get(id, tenantId);
        assertLocalEditable(unit);
        unit.setName(name);
        unit.setUpdatedAt(Instant.now());
        return repository.save(unit);
    }

    /**
     * 移动节点（仅 LOCAL）。会重算自身及所有后代的 path。
     */
    @Transactional
    public OrgUnitEntity move(UUID id, String tenantId, UUID newParentId) {
        OrgUnitEntity unit = get(id, tenantId);
        assertLocalEditable(unit);
        if (newParentId != null) {
            OrgUnitEntity parent = get(newParentId, tenantId);
            assertLocalEditable(parent);
            if (parent.getPath().startsWith(unit.getPath())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能把部门移动到自己的子树下");
            }
        }
        unit.setParentId(newParentId);
        unit.setPath(computePath(unit, newParentId));
        unit.setUpdatedAt(Instant.now());
        OrgUnitEntity saved = repository.save(unit);
        rewriteSubtreePaths(saved);
        return saved;
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        OrgUnitEntity unit = get(id, tenantId);
        assertLocalEditable(unit);
        // 软删：保留 users.department_id 的引用可读性
        unit.setDeleted(true);
        unit.setUpdatedAt(Instant.now());
        repository.save(unit);
    }

    /** 把用户调整到某部门（仅 LOCAL 子树，管理员操作）。 */
    @Transactional
    public void moveUser(UUID userId, String tenantId, UUID unitId) {
        UserEntity user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        OrgUnitEntity unit = get(unitId, tenantId);
        assertLocalEditable(unit);
        user.setDepartmentId(unitId);
        userRepository.save(user);
    }

    /** 写部门归属（外部同步专用，不校验 LOCAL）。 */
    @Transactional
    public void assignUserDepartment(UUID userId, UUID unitId) {
        userRepository.findById(userId).ifPresent(u -> {
            u.setDepartmentId(unitId);
            userRepository.save(u);
        });
    }

    private void assertLocalEditable(OrgUnitEntity unit) {
        if (!unit.isLocal()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "该部门由外部平台维护（" + unit.getSource() + "），请在源系统修改");
        }
    }

    private String computePath(OrgUnitEntity unit, UUID parentId) {
        if (parentId == null) {
            return "/" + unit.getId() + "/";
        }
        OrgUnitEntity parent = repository.findById(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级部门不存在"));
        return ensureTrailingSlash(parent.getPath()) + unit.getId() + "/";
    }

    private static String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    /** 节点 path 变化后，把整棵后代的 path 前缀替换掉。 */
    private void rewriteSubtreePaths(OrgUnitEntity unit) {
        List<OrgUnitEntity> descendants = repository
                .findByTenantIdAndPathStartingWithAndDeletedFalse(unit.getTenantId(), unit.getPath());
        for (OrgUnitEntity d : descendants) {
            if (d.getId().equals(unit.getId())) continue;
            String newPath = unit.getPath() + d.getPath().substring(unit.getPath().length());
            d.setPath(newPath);
            d.setUpdatedAt(Instant.now());
            repository.save(d);
        }
        log.debug("[org] 子树路径已重算 unit={} descendants={}", unit.getId(), descendants.size() - 1);
    }
}
