package com.nocobase.tenant;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 租户服务(Week 41 D6 Step G1).
 *
 * <p>提供 tenant CRUD + 默认租户初始化。Step G2 将扩展:
 * <ul>
 *   <li>新建租户时初始化 schema(执行 DDL 脚本)</li>
 *   <li>删除租户时 drop schema + cascade</li>
 *   <li>切换 active schema 时 SET search_path</li>
 * </ul>
 */
@Service
public class TenantService {

    /** tenant id / schema name 标识符校验:字母数字下划线,1-63 字符。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final TenantRepository repository;

    public TenantService(TenantRepository repository) {
        this.repository = repository;
    }

    /** 列出所有 ACTIVE 租户(管理员用)。 */
    public List<TenantEntity> listActive() {
        return repository.findByStatus(TenantEntity.Status.ACTIVE);
    }

    /** 列出所有租户。 */
    public List<TenantEntity> listAll() {
        return repository.findAll();
    }

    public TenantEntity get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在: " + id));
    }

    public TenantEntity create(String id, String name, String slug) {
        validateId(id);
        if (repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "租户已存在: " + id);
        }
        TenantEntity t = new TenantEntity(id, name, slug);
        return repository.save(t);
    }

    public TenantEntity disable(String id) {
        // 默认租户校验在 get() 之前 — 防止依赖 repository seed 状态
        if (TenantContext.DEFAULT_TENANT.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认租户不可禁用");
        }
        TenantEntity t = get(id);
        t.setStatus(TenantEntity.Status.DISABLED);
        return repository.save(t);
    }

    /** 启动时若 tenant 表为空,自动 seed `tenant_default` (兼容 Week 1-40 单租户数据)。 */
    public void seedDefaultIfEmpty() {
        if (repository.count() > 0) return;
        TenantEntity t = new TenantEntity(
                TenantContext.DEFAULT_TENANT,
                "默认租户",
                "default");
        repository.save(t);
    }

    private void validateId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非法 tenantId: 必须匹配 ^[a-z][a-z0-9_]{0,62}$");
        }
    }
}
