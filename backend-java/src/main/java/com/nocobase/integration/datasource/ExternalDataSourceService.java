package com.nocobase.integration.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.CredentialCryptoService;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionRepository;
import com.nocobase.meta.FieldDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PHASE B B3: 外部数据源服务。
 *
 * <p>核心能力：
 * <ol>
 *   <li>连接测试（验证凭证有效性）</li>
 *   <li>读取外部库表结构（information_schema.columns）</li>
 *   <li>创建虚拟 Collection（只读，platform 内不可写）</li>
 * </ol>
 *
 * <p>安全红线：明文密码只在内存流转，解密后立即销毁（不留日志）。
 */
@Service
@Transactional(readOnly = true)
public class ExternalDataSourceService {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataSourceService.class);

    private final ExternalDataSourceRepository datasourceRepository;
    private final ExternalDataSourceConnectionFactory connectionFactory;
    private final CredentialCryptoService cryptoService;
    private final CollectionRepository collectionRepository;
    private final ObjectMapper objectMapper;

    public ExternalDataSourceService(
            ExternalDataSourceRepository datasourceRepository,
            ExternalDataSourceConnectionFactory connectionFactory,
            CredentialCryptoService cryptoService,
            CollectionRepository collectionRepository,
            ObjectMapper objectMapper) {
        this.datasourceRepository = datasourceRepository;
        this.connectionFactory = connectionFactory;
        this.cryptoService = cryptoService;
        this.collectionRepository = collectionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建外部数据源（**密码加密存储**）。
     *
     * <p>修复前本 Service 只有 {@code decrypt} 调用（:66/:98/:123 解密），
     * 全仓 {@code cryptoService.encrypt()} 唯一调用点在无关的
     * {@code TenantCredentialService:46/59} → "密码加密存储"无生产路径，
     * 数据源只能靠手工插密文入库（B3 假完成）。现补此方法并在 Controller 暴露。
     *
     * @param password 明文密码（方法内加密后仅存密文，不明文落库/返回）
     */
    @Transactional
    public ExternalDataSourceEntity createDataSource(String tenantId, String name, String vendor,
                                                     String host, Integer port, String databaseName,
                                                     String username, String password,
                                                     String driverClassName, UUID createdBy) {
        return createDataSource(tenantId, name, vendor, host, port, databaseName,
                username, password, driverClassName, createdBy, null, null, null, null);
    }

    /** 创建外部数据源（支持同步配置，创建时可一并设置） */
    public ExternalDataSourceEntity createDataSource(String tenantId, String name, String vendor,
                                                     String host, Integer port, String databaseName,
                                                     String username, String password,
                                                     String driverClassName, UUID createdBy,
                                                     Boolean syncEnabled, String syncMode,
                                                     String syncCron, String incrementalKey) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户上下文缺失，拒绝创建外部数据源");
        }
        // vendor 先校验（connectionFactory.validateVendor 为包级，供复用）
        connectionFactory.validateVendor(vendor);

        if (datasourceRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new IllegalArgumentException("同名数据源已存在：" + name);
        }

        Instant now = Instant.now();
        ExternalDataSourceEntity e = new ExternalDataSourceEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenantId);
        e.setName(name);
        e.setVendor(vendor.toLowerCase());
        e.setHost(host);
        e.setPort(port);
        e.setDatabaseName(databaseName);
        e.setUsername(username);
        e.setDriverClassName(driverClassName);
        // 关键修复：密码加密落库（AES-GCM，密钥由 integration.credential.master-key 注入）
        e.setPasswordEnc(cryptoService.encrypt(password));
        e.setStatus("ACTIVE");
        e.setCreatedAt(now);
        // updatedAt 列 nullable=false 且无自动维护，必须显式赋值
        e.setUpdatedAt(now);
        e.setCreatedBy(createdBy);

        // 同步配置（可选）
        if (Boolean.TRUE.equals(syncEnabled)) {
            e.setSyncEnabled(true);
            e.setSyncMode(syncMode != null ? syncMode.toUpperCase() : "MANUAL");
            e.setSyncCron(syncCron);
            e.setIncrementalKey(incrementalKey);
        } else {
            e.setSyncEnabled(false);
            e.setSyncMode("MANUAL");
        }

        ExternalDataSourceEntity saved = datasourceRepository.save(e);
        log.info("[ext-ds] 创建数据源 id={} tenant={} name={} vendor={} syncEnabled={}", saved.getId(), tenantId, name, vendor, e.getSyncEnabled());
        return saved;
    }

    /** 测试外部数据源连接（解密密码 → 建立连接 → 执行 SELECT 1 → 关闭连接） */
    public boolean testConnection(UUID datasourceId, String tenantId) {
        ExternalDataSourceEntity entity = datasourceRepository.findByIdAndTenantId(datasourceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("外部数据源不存在"));

        String plainPassword;
        try {
            plainPassword = cryptoService.decrypt(entity.getPasswordEnc());
        } catch (Exception e) {
            log.error("[ext-ds] 密码解密失败 id={}", datasourceId, e);
            return false;
        }

        DataSource ds = null;
        JdbcTemplate jdbc = null;
        try {
            ds = connectionFactory.createOrGet(entity, plainPassword);
            jdbc = new JdbcTemplate(ds);
            // 简单连通性测试
            jdbc.queryForObject("SELECT 1", Integer.class);
            log.info("[ext-ds] 连接测试成功 id={}", datasourceId);
            return true;
        } catch (Exception e) {
            log.warn("[ext-ds] 连接测试失败 id={} err={}", datasourceId, e.getMessage());
            return false;
        } finally {
            // 不关闭 DataSource（缓存复用），仅清理局部变量
            if (jdbc != null) {
                jdbc = null;
            }
            // 注意：plainPassword 变量在方法结束后自动 GC，不显式清零（Java 无法保证）
        }
    }

    /** 列出外部数据源下的所有表（information_schema.tables） */
    public List<String> listTables(UUID datasourceId, String tenantId) {
        ExternalDataSourceEntity entity = datasourceRepository.findByIdAndTenantId(datasourceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("外部数据源不存在"));

        String plainPassword = cryptoService.decrypt(entity.getPasswordEnc());
        DataSource ds = connectionFactory.createOrGet(entity, plainPassword);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 修复前 MySQL 分支硬编码 table_schema='public'（MySQL 实际库名非 public）
        // → 读表恒返回空。现统一按实体配置的 databaseName 参数化。
        String schema = entity.getDatabaseName();

        String tableSql = switch (entity.getVendor().toLowerCase()) {
            case "mysql" ->
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'";
            case "postgresql" ->
                "SELECT tablename FROM pg_tables WHERE schemaname = ?";
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + entity.getVendor());
        };

        List<String> tables = jdbc.queryForList(tableSql, String.class, schema);
        log.info("[ext-ds] 列出表 id={} count={}", datasourceId, tables.size());
        return tables;
    }

    /** 读取外部表的列结构（用于创建虚拟 Collection） */
    public List<FieldDef> readTableColumns(UUID datasourceId, String tenantId, String tableName) {
        ExternalDataSourceEntity entity = datasourceRepository.findByIdAndTenantId(datasourceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("外部数据源不存在"));

        // vendor 校验先于解密：非法 vendor 直接抛错，不白解一次密码
        connectionFactory.validateVendor(entity.getVendor());

        String plainPassword = cryptoService.decrypt(entity.getPasswordEnc());
        DataSource ds = connectionFactory.createOrGet(entity, plainPassword);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 修复前 mysql/pg 两个分支均硬编码 table_schema='public'，
        // MySQL 实际库名非 public → 读列恒返回空。现按 databaseName 参数化。
        String schema = entity.getDatabaseName();

        String columnSql = switch (entity.getVendor().toLowerCase()) {
            case "mysql", "postgresql" ->
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + entity.getVendor());
        };

        List<Map<String, Object>> rows = jdbc.queryForList(columnSql, schema, tableName);
        List<FieldDef> fields = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String columnName = (String) row.get("column_name");
            String dataType = (String) row.get("data_type");
            String isNullable = (String) row.get("is_nullable");

            String fieldType = mapDataType(dataType);
            boolean required = !"YES".equals(isNullable);

            fields.add(new FieldDef(columnName, fieldType, required, columnName, null));
        }

        log.info("[ext-ds] 读取表结构 id={} table={} columns={}", datasourceId, tableName, fields.size());
        return fields;
    }

    /** 创建虚拟 Collection（外部表映射，只读） */
    @Transactional
    public CollectionMetaEntity createVirtualCollection(
            UUID datasourceId, String tenantId, String tableName, String collectionName,
            String title, String description, UUID createdBy) {

        ExternalDataSourceEntity entity = datasourceRepository.findByIdAndTenantId(datasourceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("外部数据源不存在"));

        // 读取外部表结构
        List<FieldDef> fields = readTableColumns(datasourceId, tenantId, tableName);

        // 检查 Collection 是否已存在
        if (collectionRepository.existsByName(collectionName)) {
            throw new IllegalArgumentException("Collection 已存在：" + collectionName);
        }

        // 创建 Collection 元数据（标记为外部映射、只读）
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setId(UUID.randomUUID());
        meta.setName(collectionName);
        meta.setTitle(title);
        meta.setDescription(description);
        try {
            meta.setFieldsJson(objectMapper.writeValueAsString(fields));
        } catch (Exception e) {
            throw new RuntimeException("字段序列化失败", e);
        }
        meta.setTenantId(tenantId);
        meta.setCreatedAt(Instant.now());
        meta.setCreatedBy(createdBy);
        meta.setDatasourceId(datasourceId);
        meta.setExternalTableName(tableName);
        meta.setReadOnly(true);

        CollectionMetaEntity saved = collectionRepository.save(meta);

        // 更新外部数据源的 last_synced_at
        entity.setLastSyncedAt(Instant.now());
        datasourceRepository.save(entity);

        log.info("[ext-ds] 创建虚拟 Collection id={} name={} table={} datasource={}",
                saved.getId(), collectionName, tableName, datasourceId);
        return saved;
    }

    /**
     * 同步外部数据源（B3 返工）。
     *
     * <p>被 {@code ExternalDataSourceSyncJob} 定时调用：按 {@code syncMode} 执行
     * 全量或增量同步，刷新已映射虚拟 Collection 的结构快照，并推进
     * {@code lastSyncedAt} 与增量水位 {@code lastSyncCursor}。
     *
     * <p><b>增量语义</b>：若配置了 {@code incrementalKey}，读取外部表该列的最大值
     * 作为新水位；与旧水位一致则跳过重刷。FULL 模式无条件重读结构。
     *
     * @return 同步是否实际执行了结构刷新
     */
    @Transactional
    public boolean syncDatasource(UUID datasourceId, String tenantId) {
        ExternalDataSourceEntity entity = datasourceRepository.findByIdAndTenantId(datasourceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("外部数据源不存在"));

        String mode = entity.getSyncMode() == null ? "MANUAL" : entity.getSyncMode();
        boolean incremental = "INCREMENTAL".equalsIgnoreCase(mode);
        String incKey = entity.getIncrementalKey();

        // 增量模式：先探测新水位；无水位字段或无变化则跳过
        if (incremental) {
            if (incKey == null || incKey.isBlank()) {
                log.warn("[ext-ds-sync] INCREMENTAL 但未配置 incremental_key，降级为 FULL id={}", datasourceId);
            } else {
                String newWatermark = probeWatermark(entity, incKey);
                if (newWatermark.equals(entity.getLastSyncCursor())) {
                    log.debug("[ext-ds-sync] 无增量变化 id={} watermark={}", datasourceId, newWatermark);
                    entity.setLastSyncedAt(Instant.now());
                    datasourceRepository.save(entity);
                    return false;
                }
                entity.setLastSyncCursor(newWatermark);
            }
        }

        // 刷新该数据源已映射的虚拟 Collection 的结构快照
        refreshVirtualCollections(datasourceId, tenantId);

        entity.setLastSyncedAt(Instant.now());
        datasourceRepository.save(entity);
        log.info("[ext-ds-sync] 同步完成 id={} mode={} tenant={}", datasourceId, mode, tenantId);
        return true;
    }

    /** 探测外部表增量水位字段的最大值；读不到返回空串。 */
    private String probeWatermark(ExternalDataSourceEntity entity, String incKey) {
        try {
            String plainPassword = cryptoService.decrypt(entity.getPasswordEnc());
            DataSource ds = connectionFactory.createOrGet(entity, plainPassword);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            // 取该数据源第一张映射表的列；简化为 information_schema 探测最大值
            List<CollectionMetaEntity> metas =
                    collectionRepository.findByDatasourceId(entity.getId());
            if (metas == null || metas.isEmpty()) {
                return "";
            }
            String table = metas.get(0).getExternalTableName();
            String schema = entity.getDatabaseName();
            String sql = switch (entity.getVendor().toLowerCase()) {
                case "mysql", "postgresql" ->
                        "SELECT COALESCE(MAX(" + incKey + ")::text, '') FROM "
                                + quotedIdent(entity.getVendor(), schema) + "." + quotedIdent(entity.getVendor(), table);
                default -> throw new IllegalArgumentException("不支持的数据库类型：" + entity.getVendor());
            };
            List<String> result = jdbc.queryForList(sql, String.class);
            return result == null || result.isEmpty() ? "" : result.get(0);
        } catch (Exception e) {
            log.warn("[ext-ds-sync] 探测增量水位失败 id={} err={}", entity.getId(), e.getMessage());
            return "";
        }
    }

    /** 刷新虚拟 Collection 的结构快照（列定义），保留既有 readOnly 标记。 */
    private void refreshVirtualCollections(UUID datasourceId, String tenantId) {
        List<CollectionMetaEntity> metas = collectionRepository.findByDatasourceId(datasourceId);
        if (metas == null) return;
        for (CollectionMetaEntity meta : metas) {
            if (meta.getExternalTableName() == null) continue;
            List<FieldDef> fields = readTableColumns(datasourceId, tenantId, meta.getExternalTableName());
            try {
                meta.setFieldsJson(objectMapper.writeValueAsString(fields));
                collectionRepository.save(meta);
            } catch (Exception e) {
                log.warn("[ext-ds-sync] 刷新结构失败 collection={} err={}", meta.getName(), e.getMessage());
            }
        }
    }

    private static String quotedIdent(String vendor, String ident) {
        return "mysql".equalsIgnoreCase(vendor) ? "`" + ident + "`" : "\"" + ident + "\"";
    }

    /** 数据类型映射（外部 DB → 平台 FieldDef 类型） */
    private String mapDataType(String dbType) {
        String lower = dbType.toLowerCase();
        return switch (lower) {
            case "varchar", "char", "text", "uuid", "json", "jsonb" -> "text";
            case "int", "bigint", "smallint", "decimal", "numeric", "double", "float" -> "number";
            case "boolean", "bool" -> "boolean";
            case "date" -> "date";
            case "timestamp", "timestamptz", "datetime", "time" -> "datetime";
            default -> "text"; // 未知类型降级为 text
        };
    }

    /** 删除外部数据源（同时清理缓存连接池） */
    @Transactional
    public void deleteDataSource(UUID datasourceId, String tenantId) {
        ExternalDataSourceEntity entity = datasourceRepository.findByIdAndTenantId(datasourceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("外部数据源不存在"));

        // 清理连接池缓存
        connectionFactory.invalidate(datasourceId.toString());

        // 删除数据源记录
        datasourceRepository.delete(entity);
        log.info("[ext-ds] 删除外部数据源 id={}", datasourceId);
    }

    /** 更新同步配置 */
    @Transactional
    public ExternalDataSourceEntity updateSyncConfig(
            UUID datasourceId, String tenantId, Boolean syncEnabled,
            String syncMode, String syncCron, String incrementalKey) {
        ExternalDataSourceEntity entity = datasourceRepository.findByIdAndTenantId(datasourceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("外部数据源不存在"));

        entity.setSyncEnabled(syncEnabled);
        if (syncMode != null) entity.setSyncMode(syncMode);
        entity.setSyncCron(syncCron);
        entity.setIncrementalKey(incrementalKey);
        entity.setUpdatedAt(java.time.Instant.now());

        datasourceRepository.save(entity);
        log.info("[ext-ds] 更新同步配置 id={} enabled={} mode={} cron={} incrementalKey={}",
                datasourceId, syncEnabled, syncMode, syncCron, incrementalKey);
        return entity;
    }

    /** 列出租户下的所有外部数据源 */
    public List<ExternalDataSourceEntity> listDataSources(String tenantId) {
        return datasourceRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 获取外部数据源详情 */
    public Optional<ExternalDataSourceEntity> getDataSource(UUID datasourceId, String tenantId) {
        return datasourceRepository.findByIdAndTenantId(datasourceId, tenantId);
    }
}
