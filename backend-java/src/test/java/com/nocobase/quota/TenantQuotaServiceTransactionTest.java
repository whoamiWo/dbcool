package com.nocobase.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;

/** H12：定时 flush 在调用方没有事务时，也必须能真实更新数据库。 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class TenantQuotaServiceTransactionTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeFixture() {
        jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", "tenant_h12");
    }

    @Test
    void flushWithoutCallerTransactionUpdatesDatabaseWithoutWarning(CapturedOutput output) {
        TenantEntity tenant = new TenantEntity("tenant_h12", "H12 Tenant", "tenant-h12");
        tenant.setApiCallUsage(7L);
        tenantRepository.saveAndFlush(tenant);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(valueOperations.increment(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1L)))
                .thenReturn(1L);
        when(redisTemplate.expire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        TenantQuotaService service = new TenantQuotaService(
                tenantRepository, mock(AuditService.class), redisTemplate);
        service.checkAndConsumeApiQuota("tenant_h12");

        // 已显式结束 @DataJpaTest 默认事务，复现生产 @Scheduled 的非事务调用场景。
        service.flushPendingIncrementsToDB();

        Long usage = jdbcTemplate.queryForObject(
                "SELECT api_call_usage FROM tenant WHERE id = 'tenant_h12'", Long.class);
        assertThat(usage).isEqualTo(8L);
        assertThat(output).doesNotContain("[quota] 刷回 DB 失败");
    }
}
