package com.nocobase.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 订阅实体 (Billing Subscription).
 * 
 * <p>记录租户对特定套餐的订阅状态，包括有效期、扣除周期等。</p>
 */
@Entity
@Table(name = "billing_subscription")
public class SubscriptionEntity {

    public enum Status {
        ACTIVE, PAST_DUE, CANCELED, EXPIRED, TRIALING
    }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "plan_tier_id", nullable = false, length = 64)
    private String planTierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.ACTIVE;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "current_period_start", nullable = false)
    private LocalDate currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private LocalDate currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private Boolean cancelAtPeriodEnd = false;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "payment_method", length = 32)
    private String paymentMethod;

    @Column(name = "last_payment_amount", precision = 10, scale = 2)
    private BigDecimal lastPaymentAmount;

    @Column(name = "last_payment_at")
    private Instant lastPaymentAt;

    @Column(name = "next_billing_date")
    private LocalDate nextBillingDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getPlanTierId() { return planTierId; }
    public void setPlanTierId(String planTierId) { this.planTierId = planTierId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(LocalDate currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }

    public LocalDate getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(LocalDate currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public Boolean getCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(Boolean cancelAtPeriodEnd) { this.cancelAtPeriodEnd = cancelAtPeriodEnd; }

    public Instant getCanceledAt() { return canceledAt; }
    public void setCanceledAt(Instant canceledAt) { this.canceledAt = canceledAt; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getLastPaymentAmount() { return lastPaymentAmount; }
    public void setLastPaymentAmount(BigDecimal lastPaymentAmount) { this.lastPaymentAmount = lastPaymentAmount; }

    public Instant getLastPaymentAt() { return lastPaymentAt; }
    public void setLastPaymentAt(Instant lastPaymentAt) { this.lastPaymentAt = lastPaymentAt; }

    public LocalDate getNextBillingDate() { return nextBillingDate; }
    public void setNextBillingDate(LocalDate nextBillingDate) { this.nextBillingDate = nextBillingDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}