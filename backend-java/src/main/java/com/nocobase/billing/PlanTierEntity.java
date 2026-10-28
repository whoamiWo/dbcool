package com.nocobase.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 计费套餐层级实体 (Billing Plan Tier).
 * 
 * <p>定义不同层级的套餐及其配额限制和功能。</p>
 */
@Entity
@Table(name = "billing_plan_tier")
public class PlanTierEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 64)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price_monthly", precision = 10, scale = 2, nullable = false)
    private BigDecimal priceMonthly;

    @Column(name = "price_yearly", precision = 10, scale = 2, nullable = false)
    private BigDecimal priceYearly;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "CNY";

    @Column(name = "api_call_limit", nullable = false)
    private Long apiCallLimit = 0L;

    @Column(name = "storage_limit", nullable = false)
    private Long storageLimit = 0L;

    @Column(name = "seats_limit", nullable = false)
    private Long seatsLimit = 0L;

    @Column(name = "features", columnDefinition = "JSONB")
    private String features;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPriceMonthly() { return priceMonthly; }
    public void setPriceMonthly(BigDecimal priceMonthly) { this.priceMonthly = priceMonthly; }

    public BigDecimal getPriceYearly() { return priceYearly; }
    public void setPriceYearly(BigDecimal priceYearly) { this.priceYearly = priceYearly; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Long getApiCallLimit() { return apiCallLimit; }
    public void setApiCallLimit(Long apiCallLimit) { this.apiCallLimit = apiCallLimit; }

    public Long getStorageLimit() { return storageLimit; }
    public void setStorageLimit(Long storageLimit) { this.storageLimit = storageLimit; }

    public Long getSeatsLimit() { return seatsLimit; }
    public void setSeatsLimit(Long seatsLimit) { this.seatsLimit = seatsLimit; }

    public String getFeatures() { return features; }
    public void setFeatures(String features) { this.features = features; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
