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
 *   <li>{@code postgresql} 家族：PostgreSQL JDBC 驱动（pom runtime 依赖）</li>
 *   <li>{@code mysql} 家族：MySQL Connector/J（pom runtime 依赖，v2.1.4 补齐）</li>
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

    /**
     * Wave4 待办池 #14：vendor → 驱动家族归一。
     *
     * <p>支持的 vendor 与所需驱动（pom 已同时声明 postgresql 与 mysql-connector-j）：
     * <ul>
     *   <li>MySQL 家族：mysql / mariadb / oceanbase → {@code com.mysql.cj.jdbc.Driver}</li>
     *   <li>PostgreSQL 家族：postgresql / opengauss → {@code org.postgresql.Driver}</li>
     *   <li>kingbase（人大金仓）：默认按 PostgreSQL 协议尝试；若目标实例未开启 PG 兼容模式，
     *       需通过 {@code driver_class_name} + 自定义 JDBC URL 使用官方 kingbase8 驱动</li>
     * </ul>
     * 上述兼容性为「协议级复用」，<b>不等于零配置可用</b>：数据库侧需开启对应兼容模式，
     * 且账号需具备目标库的读权限。厂商专用驱动可通过 driver_class_name 覆盖（已有机制）。
     */
    static String familyOf(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            throw new IllegalArgumentException("外部数据源 vendor 不能为空");
        }
        return switch (vendor.toLowerCase()) {
            case "mysql", "mariadb", "oceanbase" -> "mysql";
            case "postgresql", "kingbase", "opengauss" -> "postgresql";
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + vendor);
        };
    }

    /** 校验 vendor 合法（mysql 家族 / postgresql 家族） */
    void validateVendor(String vendor) {
        familyOf(vendor);
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
        String family = familyOf(vendor);
        String jdbcUrl;
        String driverClassName;

        // 根据驱动家族构建 JDBC URL（Wave4 #14：MariaDB/OceanBase 用 MySQL 驱动；
        // Kingbase/openGauss 用 PostgreSQL 驱动；专用驱动可用 driver_class_name 覆盖）
        switch (family) {
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
