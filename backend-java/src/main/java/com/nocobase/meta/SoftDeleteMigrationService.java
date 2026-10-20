package com.nocobase.meta;

import com.nocobase.tenant.TenantContext;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

/**
 * 存量物理表软删除列迁移(PHASE125)。
 *
 * <p><b>为什么需要本服务</b>:Collection 的业务记录存放在<strong>动态表</strong>
 * {@code <schema>.data_<collection>}(见 {@code DynamicTableManager.physicalTableName}),
 * 表名由运行时 collection 决定,而非固定表 —— 因此无法通过单条 Flyway SQL 为
 * "所有租户 schema 下已存在的表"统一加列(schema 与表名都是动态的)。
 *
 * <p>新建表自 PHASE125 起在 {@code DynamicTableManager.createTable} 的 DDL 中已直接
 * 包含 {@code deleted_at} / {@code deleted_by};本服务负责把<strong>升级前已存在</strong>
 * 的存量表补齐这两列,使旧数据也能享受回收站能力。
 *
 * <p><b>迁移语义(安全)</b>:
 * <ul>
 *   <li>仅 {@code ADD COLUMN IF NOT EXISTS},补列后既有行 {@code deleted_at} 为 NULL,
 *       即"未删除" —— 存量数据不受任何影响,不会出现数据丢失或被误判为已删</li>
 *   <li>幂等,可重复执行;启动失败仅 warn,不阻断应用启动</li>
 * </ul>
 *
 * <p><b>多租户遍历</b>:schema 隔离下每个租户的 {@code data_*} 表位于各自 schema
 * (默认租户 {@code tenant_default} → {@code public},其余 → tenantId,见
 * {@link TenantContext#currentSchema()})。故必须"租户 × collection"双重循环,
 * 并在每次切换后用 {@code TenantContext.set(...)} / {@code clear()} 包裹,
 * 确保 {@code physicalTableName} 解析到正确的 schema(ThreadLocal 必须 finally 清理)。
 *
 * <p>启动时机:实现 {@link CommandLineRunner},与既有 {@code TenantSeedRunner} 同为
 * 容器启动后执行一次的迁移型 runner。
 */
@Service
public class SoftDeleteMigrationService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SoftDeleteMigrationService.class);

    private final TenantRepository tenantRepository;
    private final CollectionRepository collectionRepository;
    private final DynamicTableManager tableManager;

    public SoftDeleteMigrationService(TenantRepository tenantRepository,
                                      CollectionRepository collectionRepository,
                                      DynamicTableManager tableManager) {
        this.tenantRepository = tenantRepository;
        this.collectionRepository = collectionRepository;
        this.tableManager = tableManager;
    }

    @Override
    public void run(String... args) {
        int migrated = 0;
        int failed = 0;

        for (TenantEntity tenant : tenantRepository.findAll()) {
            String tenantId = tenant.getId();
            // set(tenantId) 后 currentSchema() 才能推导到正确 schema(默认租户 → public)
            TenantContext.set(tenantId);
            try {
                for (CollectionMetaEntity meta : collectionRepository.findByTenantId(tenantId)) {
                    String name = meta.getName();
                    try {
                        tableManager.ensureDeletedAtColumn(name);
                        migrated++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("[softdelete-migration] 补列失败 tenant={} collection={} — {}",
                                tenantId, name, e.getMessage());
                    }
                }
            } finally {
                TenantContext.clear(); // 必须清理,防线程复用泄漏
            }
        }

        log.info("[softdelete-migration] 存量软删除列迁移完成:成功 {} 张表,失败 {} 张表", migrated, failed);
    }
}
