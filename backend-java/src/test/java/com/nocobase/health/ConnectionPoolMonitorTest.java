package com.nocobase.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 连接池监控单元测试(R09).
 *
 * 验证 Hikari 池指标快照、健康判定与降级行为(非 Hikari 数据源)。
 */
class ConnectionPoolMonitorTest {

    private ConnectionPoolMonitor monitor;

    @Test
    void snapshot_hikari_providesStats() {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariPoolMXBean bean = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(bean);
        when(bean.getActiveConnections()).thenReturn(10);
        when(bean.getIdleConnections()).thenReturn(5);
        when(bean.getTotalConnections()).thenReturn(15);
        when(ds.getMaximumPoolSize()).thenReturn(20);
        when(bean.getThreadsAwaitingConnection()).thenReturn(0);

        monitor = new ConnectionPoolMonitor(ds, 0.85);
        Map<String, Object> snap = monitor.snapshot();

        assertThat(snap.get("available")).isEqualTo(true);
        assertThat(snap.get("active")).isEqualTo(10);
        assertThat(snap.get("idle")).isEqualTo(5);
        assertThat(snap.get("total")).isEqualTo(15);
        assertThat(snap.get("max")).isEqualTo(20);
        assertThat(snap.get("threadsAwaitingConnection")).isEqualTo(0);
        assertThat(snap.get("utilizationPct")).isEqualTo(50L);
    }

    @Test
    void isHealthy_true_whenNoWaitAndBelowMax() {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariPoolMXBean bean = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(bean);
        when(bean.getActiveConnections()).thenReturn(10);
        when(ds.getMaximumPoolSize()).thenReturn(20);
        when(bean.getThreadsAwaitingConnection()).thenReturn(0);

        monitor = new ConnectionPoolMonitor(ds, 0.85);
        assertThat(monitor.isHealthy()).isTrue();
    }

    @Test
    void isHealthy_false_whenThreadsWaiting() {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariPoolMXBean bean = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(bean);
        when(bean.getActiveConnections()).thenReturn(19);
        when(ds.getMaximumPoolSize()).thenReturn(20);
        when(bean.getThreadsAwaitingConnection()).thenReturn(3);

        monitor = new ConnectionPoolMonitor(ds, 0.85);
        assertThat(monitor.isHealthy()).isFalse();
    }

    @Test
    void snapshot_nonHikari_returnsUnavailable() {
        DataSource ds = mock(DataSource.class);
        monitor = new ConnectionPoolMonitor(ds, 0.85);
        Map<String, Object> snap = monitor.snapshot();

        assertThat(snap.get("available")).isEqualTo(false);
        assertThat(snap.get("reason")).isEqualTo("non-hikari-datasource");
    }

    @Test
    void isHealthy_nonHikari_isAlwaysTrue() {
        DataSource ds = mock(DataSource.class);
        monitor = new ConnectionPoolMonitor(ds, 0.85);
        assertThat(monitor.isHealthy()).isTrue();
    }
}
