package com.nocobase.health;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据库连接池监控(R09 — 数据库连接耗尽风险的缓解措施之一).
 *
 * <p>读取 HikariCP 池指标并暴露给 health 端点;定时扫描高水位与等待线程,
 * 在连接池被静默耗尽前发出 {@code WARN},便于提前介入。
 *
 * <p>非 Hikari 数据源(如测试用 H2 外的其他实现)优雅降级:
 * {@link #snapshot()} 返回 {@code available=false},{@link #isHealthy()} 返回 {@code true}。
 */
@Component
public class ConnectionPoolMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolMonitor.class);

    /** 活跃连接占比告警阈值(默认 85%)。 */
    private final double warnUtilization;

    private final DataSource dataSource;

    public ConnectionPoolMonitor(
            DataSource dataSource,
            @Value("${app.pool-monitor.warn-utilization:0.85}") double warnUtilization
    ) {
        this.dataSource = dataSource;
        this.warnUtilization = warnUtilization;
    }

    /**
     * 当前连接池快照。
     *
     * @return 含 active/idle/total/max/threadsAwaitingConnection/utilizationPct 的 Map;
     *         非 Hikari 数据源返回 {@code available=false}
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        HikariDataSource hds = hikari();
        if (hds == null) {
            m.put("available", false);
            m.put("reason", "non-hikari-datasource");
            return m;
        }
        HikariPoolMXBean bean = hds.getHikariPoolMXBean();
        if (bean == null) { // 池尚未初始化
            m.put("available", false);
            m.put("reason", "pool-not-initialized");
            return m;
        }
        int active = bean.getActiveConnections();
        int max = hds.getMaximumPoolSize();
        m.put("available", true);
        m.put("active", active);
        m.put("idle", bean.getIdleConnections());
        m.put("total", bean.getTotalConnections());
        m.put("max", max);
        m.put("threadsAwaitingConnection", bean.getThreadsAwaitingConnection());
        m.put("utilizationPct", max > 0 ? Math.round(active * 100.0 / max) : 0);
        return m;
    }

    /**
     * 连接池是否健康。
     *
     * @return 无线程等待连接且活跃连接未触顶 → true;非 Hikari 数据源 → true
     */
    public boolean isHealthy() {
        HikariDataSource hds = hikari();
        if (hds == null) return true;
        HikariPoolMXBean bean = hds.getHikariPoolMXBean();
        if (bean == null) return true;
        int max = hds.getMaximumPoolSize();
        return max <= 0
                || (bean.getActiveConnections() < max && bean.getThreadsAwaitingConnection() == 0);
    }

    /**
     * 定时扫描:高水位或存在等待连接的线程 → WARN。
     * 间隔由 {@code app.pool-monitor.interval-ms} 控制(默认 30s)。
     */
    @Scheduled(fixedDelayString = "${app.pool-monitor.interval-ms:30000}")
    public void scan() {
        HikariDataSource hds = hikari();
        if (hds == null) return;
        HikariPoolMXBean bean = hds.getHikariPoolMXBean();
        if (bean == null) return;
        int max = hds.getMaximumPoolSize();
        if (max <= 0) return;
        double util = bean.getActiveConnections() * 1.0 / max;
        if (util >= warnUtilization) {
            log.warn("[pool] 连接池高水位 {}/{} ({}%), idle={}, 等待线程={}",
                    bean.getActiveConnections(), max, Math.round(util * 100),
                    bean.getIdleConnections(), bean.getThreadsAwaitingConnection());
        }
        if (bean.getThreadsAwaitingConnection() > 0) {
            log.warn("[pool] 有 {} 个线程在等待数据库连接(可能接近耗尽)",
                    bean.getThreadsAwaitingConnection());
        }
    }

    private HikariDataSource hikari() {
        if (dataSource instanceof HikariDataSource hds) {
            return hds;
        }
        return null;
    }
}
