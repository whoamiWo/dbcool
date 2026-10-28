package com.nocobase.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 账单仓储 (Invoice Repository).
 */
@Repository
public interface InvoiceRepository extends JpaRepository<InvoiceEntity, String> {

    List<InvoiceEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<InvoiceEntity> findByTenantIdAndStatusIn(
            String tenantId, List<InvoiceEntity.Status> statuses);

    Optional<InvoiceEntity> findByInvoiceNumber(String invoiceNumber);

    /** 扫描指定租户逾期未支付的账单（定时作业按租户遍历，避免无租户条件的全表扫描）。 */
    List<InvoiceEntity> findByTenantIdAndStatusAndDueDateLessThanEqual(
            String tenantId, InvoiceEntity.Status status, LocalDate date);
}