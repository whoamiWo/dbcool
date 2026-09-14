package com.nocobase.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TenantContext ThreadLocal 单元测试(Week 41 D6 Step G1).
 *
 * <p>覆盖:
 * <ul>
 *   <li>set / get / clear 基础行为</li>
 *   <li>currentTenantId() 未设置时返默认值(兼容 Week 1-40 单租户)</li>
 *   <li>requireTenantId() 未设置时抛 IllegalStateException</li>
 *   <li>set(null / blank) 抛 IllegalArgumentException</li>
 *   <li>clear 后 ThreadLocal 真的清零(防泄漏)</li>
 *   <li>DEFAULT_TENANT 常量稳定</li>
 * </ul>
 */
class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void currentTenantId_unset_returnsDefault() {
        assertThat(TenantContext.currentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT);
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    void set_then_currentTenantId_returnsValue() {
        TenantContext.set("tenant_acme");
        assertThat(TenantContext.currentTenantId()).isEqualTo("tenant_acme");
        assertThat(TenantContext.isSet()).isTrue();
    }

    @Test
    void set_thenClear_currentTenantId_returnsDefault() {
        TenantContext.set("tenant_acme");
        TenantContext.clear();
        // 必须真的清零,不能只设 null(防 ThreadLocal 泄漏)
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(TenantContext.currentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT);
    }

    @Test
    void requireTenantId_unset_throws() {
        // 必须已设置场景(请求路径中应有 JWT 过滤)
        assertThatThrownBy(() -> TenantContext.requireTenantId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantContext 未设置");
    }

    @Test
    void requireTenantId_set_returnsValue() {
        TenantContext.set("tenant_globex");
        assertThat(TenantContext.requireTenantId()).isEqualTo("tenant_globex");
    }

    @Test
    void set_null_throws() {
        assertThatThrownBy(() -> TenantContext.set(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void set_blank_throws() {
        assertThatThrownBy(() -> TenantContext.set("   "))
                               .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.set(""))
                               .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void threadIsolation_setInOneThread_doesNotLeakToAnother() throws InterruptedException {
        TenantContext.set("tenant_main");

        String[] otherThreadResult = new String[1];
        Thread other = new Thread(() -> otherThreadResult[0] = TenantContext.currentTenantId());
        other.start();
        other.join();

        // 主线程仍是原值
        assertThat(TenantContext.currentTenantId()).isEqualTo("tenant_main");
        // 其他线程看到默认值(ThreadLocal 隔离)
        assertThat(otherThreadResult[0]).isEqualTo(TenantContext.DEFAULT_TENANT);
    }

    @Test
    void defaultTenant_constantStable() {
        // DEFAULT_TENANT 必须与现有 schema/数据兼容,不能改名
        assertThat(TenantContext.DEFAULT_TENANT).isEqualTo("tenant_default");
    }
}
