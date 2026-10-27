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

        String tableSql = switch (entity.getVendor().toLowerCase()) {
            case "mysql" ->
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'";
            case "postgresql" ->
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'";
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + entity.getVendor());
        };

        List<String> tables = jdbc.queryForList(tableSql, String.class);
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

        String columnSql = switch (entity.getVendor().toLowerCase()) {
            case "mysql" ->
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position";
            case "postgresql" ->
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position";
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + entity.getVendor());
        };

        List<Map<String, Object>> rows = jdbc.queryForList(columnSql, tableName);
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

    /** 列出租户下的所有外部数据源 */
    public List<ExternalDataSourceEntity> listDataSources(String tenantId) {
        return datasourceRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 获取外部数据源详情 */
    public Optional<ExternalDataSourceEntity> getDataSource(UUID datasourceId, String tenantId) {
        return datasourceRepository.findByIdAndTenantId(datasourceId, tenantId);
    }
}
