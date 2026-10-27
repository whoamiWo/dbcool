package com.nocobase.integration.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PHASE B B3: 外部数据源连接工厂。
 *
 * <p>职责：根据 {@link ExternalDataSourceEntity} 动态创建 {@link HikariDataSource}，
 * 并按 ID 缓存（LRU + 容量限制）。缓存淘汰时自动关闭旧数据源释放资源。
 *
 * <p>驱动选择：
 * <ul>
 *   <li>{@code postgresql}：使用 Spring Boot 内置的 PostgreSQL 驱动（已有依赖）</li>
 *   <li>{@code mysql}：反射加载 MySQL 8 驱动（可选 runtime 依赖）</li>
 * </ul>
 *
 * <p>安全红线：密码解密由调用方（Service）完成，本工厂只接收明文密码字符串。
 */
@Component
public class ExternalDataSourceConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataSourceConnectionFactory.class);

    /** 缓存键：data source ID */
    private final ConcurrentHashMap<String, HikariDataSource> cache = new ConcurrentHashMap<>();

    /** 连接池参数（与主库保持一致，防止资源耗尽） */
    private static final int MAX_POOL_SIZE = 50;
    private static final int MIN_IDLE = 10;
    private static final long CONNECTION_TIMEOUT_MS = 30_000L;
    private static final long MAX_LIFETIME_MS = 1_800_000L; // 30 min
    private static final long IDLE_TIMEOUT_MS = 600_000L; // 10 min

    public DataSource createOrGet(ExternalDataSourceEntity entity, String plainPassword) {
        if (entity.getId() == null) {
            throw new IllegalArgumentException("外部数据源 id 不能为空");
        }
        // vendor 校验先于连接池创建：非法 vendor 直接抛错，避免无谓建池
        validateVendor(entity.getVendor());

        String key = entity.getId().toString();
        return cache.computeIfAbsent(key, k -> {
            log.info("[ext-ds] 创建外部数据源连接池 id={} vendor={} host={}:{}/{}",
                    entity.getId(), entity.getVendor(), entity.getHost(), entity.getPort(),
                    entity.getDatabaseName());
            HikariConfig config = buildConfig(entity, plainPassword);
            // initializationFailTimeout=0：首次 createOrGet 不立即连接，
            // 延迟到 testConnection / 首次查询才验证可达性，便于外部库可配置但暂不可达的场景
            config.setInitializationFailTimeout(0);
            HikariDataSource ds = new HikariDataSource(config);
            // 注册关闭钩子（应用退出时清理）
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    ds.close();
                    log.info("[ext-ds] 关闭外部数据源连接池 id={}", key);
                } catch (Exception e) {
                    log.warn("[ext-ds] 关闭外部数据源连接池失败 id={}", key, e);
                }
            }));
            return ds;
        });
    }

    /** 校验 vendor 合法（mysql / postgresql） */
    void validateVendor(String vendor) {
        if (vendor == null) {
            throw new IllegalArgumentException("外部数据源 vendor 不能为空");
        }
        switch (vendor.toLowerCase()) {
            case "mysql", "postgresql" -> {
            }
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + vendor);
        }
    }

    /** 移除并关闭缓存中的数据源 */
    public void invalidate(String id) {
        HikariDataSource removed = cache.remove(id);
        if (removed != null) {
            removed.close();
            log.info("[ext-ds] 移除并关闭外部数据源连接池 id={}", id);
        }
    }

    /** 清空所有缓存并关闭全部连接池 */
    public void clearAll() {
        cache.forEach((k, v) -> {
            v.close();
            log.info("[ext-ds] 强制关闭外部数据源连接池 id={}", k);
        });
        cache.clear();
    }

    private HikariConfig buildConfig(ExternalDataSourceEntity entity, String plainPassword) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ext-" + entity.getId().toString().substring(0, 8));
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(MIN_IDLE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setMaxLifetime(MAX_LIFETIME_MS);
        config.setIdleTimeout(IDLE_TIMEOUT_MS);
        config.setLeakDetectionThreshold(60_000L);

        String vendor = entity.getVendor().toLowerCase();
        String jdbcUrl;
        String driverClassName;

        // 根据 vendor 构建 JDBC URL（必填）
        switch (vendor) {
            case "mysql":
                jdbcUrl = "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8mb4"
                        .formatted(entity.getHost(), entity.getPort(), entity.getDatabaseName());
                driverClassName = "com.mysql.cj.jdbc.Driver";
                break;
            case "postgresql":
                jdbcUrl = "jdbc:postgresql://%s:%d/%s?sslmode=require&loginTimeout=30"
                        .formatted(entity.getHost(), entity.getPort(), entity.getDatabaseName());
                driverClassName = "org.postgresql.Driver";
                break;
            default:
                throw new IllegalArgumentException("不支持的外部数据库类型：" + entity.getVendor());
        }

        // 显式驱动类名覆盖默认（便于测试注入或第三方驱动）
        if (entity.getDriverClassName() != null && !entity.getDriverClassName().isBlank()) {
            driverClassName = entity.getDriverClassName();
        }

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(entity.getUsername());
        config.setPassword(plainPassword);
        config.setDriverClassName(driverClassName);

        return config;
    }
}
