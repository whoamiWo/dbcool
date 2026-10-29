package com.nocobase.billing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 计费逾期定时作业：必须真正调用服务，且异常不得吞掉（要打日志并继续调度）。 */
class BillingOverdueJobTest {

    @Test
    @DisplayName("scanOverdue 调用订阅与账单两路扫描")
    void scanOverdue_callsBothScans() {
        BillingService service = mock(BillingService.class);
        BillingOverdueJob job = new BillingOverdueJob(service);

        job.scanOverdue();

        verify(service).scanOverdueSubscriptions();
        verify(service).scanOverdueInvoices();
    }

    @Test
    @DisplayName("扫描抛异常 → 记录日志，不向调度线程冒泡")
    void scanOverdue_swallowsException() {
        BillingService service = mock(BillingService.class);
        doThrow(new IllegalStateException("db down")).when(service).scanOverdueSubscriptions();
        BillingOverdueJob job = new BillingOverdueJob(service);

        assertThatCode(job::scanOverdue).doesNotThrowAnyException();
    }
}
