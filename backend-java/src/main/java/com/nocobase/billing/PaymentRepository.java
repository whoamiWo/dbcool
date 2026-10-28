package com.nocobase.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 支付记录仓储 (Payment Repository).
 */
@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, String> {

    List<PaymentEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<PaymentEntity> findByInvoiceId(String invoiceId);

    List<PaymentEntity> findBySubscriptionId(String subscriptionId);
}