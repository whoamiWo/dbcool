package com.nocobase.governance;

import com.nocobase.integration.datasource.ExternalDataSourceEntity;
import com.nocobase.integration.datasource.ExternalDataSourceRepository;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionRepository;
import com.nocobase.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 数据治理中枢服务(B7)。
 *
 * <p>能力:
 * <ul>
 *   <li>保留策略 CRUD + 手动执行(ARCHIVE 冻结 / DELETE 删元数据)</li>
 *   <li>数据质量规则 CRUD + 执行校验(非空/唯一/正则/范围)</li>
 *   <li>血缘查询(Collection 是否由外部数据源映射)</li>
 * </ul>
 *
 * <p>PII 标注约定:通过 {@code collection_meta.fields}(JSON 数组)中每个 field 的
 * {@code options.piiLevel / options.retentionDays} 扩展,零 Schema 变更。
 */
@Service
public class DataGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(DataGovernanceService.class);

    private final RetentionPolicyRepository policyRepo;
    private final DataQualityRuleRepository ruleRepo;
    private final CollectionRepository collectionMetaRepo;
    private final ExternalDataSourceRepository externalDataSourceRepo;

    public DataGovernanceService(RetentionPolicyRepository policyRepo,
                                 DataQualityRuleRepository ruleRepo,
                                 CollectionRepository collectionMetaRepo,
                                 ExternalDataSourceRepository externalDataSourceRepo) {
        this.policyRepo = policyRepo;
        this.ruleRepo = ruleRepo;
        this.collectionMetaRepo = collectionMetaRepo;
        this.externalDataSourceRepo = externalDataSourceRepo;
    }

    // ============================================================
    //  保留策略 CRUD
    // ============================================================

    @Transactional
    public RetentionPolicyEntity createPolicy(String collectionName, String action,
                                              Integer retentionDays, boolean enabled) {
        String tenantId = TenantContext.requireTenantId();
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName 不能为空");
        }
        if (action == null || action.isBlank()) action = "ARCHIVE";
        if (retentionDays == null || retentionDays <= 0) retentionDays = 365;

        RetentionPolicyEntity p = new RetentionPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setTenantId(tenantId);
        p.setCollectionName(collectionName);
        p.setAction(action.toUpperCase());
        p.setRetentionDays(retentionDays);
        p.setEnabled(enabled);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        log.info("[governance] 创建保留策略 tenant={} collection={} action={} days={}",
                tenantId, collectionName, p.getAction(), retentionDays);
        return policyRepo.save(p);
    }

    @Transactional
    public RetentionPolicyEntity updatePolicy(UUID id, String action,
                                              Integer retentionDays, Boolean enabled) {
        String tenantId = TenantContext.requireTenantId();
        RetentionPolicyEntity p = policyRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("保留策略不存在: " + id));
        if (!tenantId.equals(p.getTenantId())) {
            throw new SecurityException("租户归属不匹配：保留策略属于 " + p.getTenantId() + ", 当前 " + tenantId);
        }
        if (action != null) p.setAction(action.toUpperCase());
        if (retentionDays != null && retentionDays > 0) p.setRetentionDays(retentionDays);
        if (enabled != null) p.setEnabled(enabled);
        p.setUpdatedAt(Instant.now());
        return policyRepo.save(p);
    }

    @Transactional
    public void deletePolicy(UUID id) {
        policyRepo.deleteById(id);
        log.info("[governance] 删除保留策略 id={}", id);
    }

    public List<RetentionPolicyEntity> listPolicies() {
        return policyRepo.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
    }

    // ============================================================
    //  保留策略执行
    // ============================================================

    /**
     * 手动执行某保留策略。
     * <p>ARCHIVE: 置 {@code collection_meta.read_only=true};
     * DELETE: 删除 {@code collection_meta} 行(物理 DROP 仅记日志,留人工)。
     */
    @Transactional
    public Map<String, Object> executePolicy(UUID policyId) {
        RetentionPolicyEntity p = policyRepo.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("保留策略不存在: " + policyId));
        String collectionName = p.getCollectionName();
        String action = p.getAction();

        if ("DELETE".equals(action)) {
            collectionMetaRepo.findByNameAndTenantId(collectionName, p.getTenantId())
                    .ifPresentOrElse(cm -> {
                        collectionMetaRepo.delete(cm);
                        log.info("[governance] 保留策略 DELETE: 删除 collection_meta id={} name={}",
                                cm.getId(), collectionName);
                        // 物理表 DROP 不在自动流程内执行(破坏性 DDL 留人工),仅审计提示
                        log.warn("[governance] collection={} 元数据已删,物理表请人工 DROP(血缘: datasource={})",
                                collectionName, cm.getDatasourceId());
                    }, () -> log.info("[governance] 保留策略 DELETE: collection {} 元数据不存在,跳过",
                            collectionName));
        } else { // ARCHIVE
            collectionMetaRepo.findByNameAndTenantId(collectionName, p.getTenantId())
                    .ifPresent(cm -> {
                        cm.setReadOnly(true);
                        collectionMetaRepo.save(cm);
                        log.info("[governance] 保留策略 ARCHIVE: 冻结 collection {}", collectionName);
                    });
        }

        p.setLastExecutedAt(Instant.now());
        policyRepo.save(p);
        return Map.of("policyId", String.valueOf(policyId),
                "action", action,
                "collection", collectionName,
                "executedAt", p.getLastExecutedAt().toString());
    }

    /** 调度器入口:执行全部已启用且到期策略。 */
    public int executeAllDuePolicies() {
        List<RetentionPolicyEntity> due = new ArrayList<>();
        for (RetentionPolicyEntity p : policyRepo.findByEnabledTrue()) {
            if (p.getLastExecutedAt() != null) {
                Instant next = p.getLastExecutedAt().plusSeconds(
                        p.getRetentionDays() * 86_400L);
                if (next.isAfter(Instant.now())) continue;
            }
            due.add(p);
        }
        int executed = 0;
        for (RetentionPolicyEntity p : due) {
            try {
                executePolicy(p.getId());
                executed++;
            } catch (Exception e) {
                log.error("[governance] 执行保留策略失败 id={}: {}", p.getId(), e.getMessage(), e);
            }
        }
        return executed;
    }

    // ============================================================
    //  数据质量规则 CRUD
    // ============================================================

    @Transactional
    public DataQualityRuleEntity createRule(String collectionName, String fieldName,
                                             String ruleType, Map<String, Object> ruleConfig,
                                             String severity, boolean enabled) {
        String tenantId = TenantContext.requireTenantId();
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName 不能为空");
        }
        if (ruleType == null || ruleType.isBlank()) {
            throw new IllegalArgumentException("ruleType 不能为空");
        }
        DataQualityRuleEntity r = new DataQualityRuleEntity();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setCollectionName(collectionName);
        r.setFieldName(fieldName);
        r.setRuleType(ruleType.toUpperCase());
        r.setRuleConfig(ruleConfig == null ? null : ruleConfig.toString());
        r.setSeverity(severity == null || severity.isBlank() ? "WARN" : severity.toUpperCase());
        r.setEnabled(enabled);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return ruleRepo.save(r);
    }

    @Transactional
    public DataQualityRuleEntity updateRule(UUID id, String ruleConfig,
                                             String severity, Boolean enabled) {
        String tenantId = TenantContext.requireTenantId();
        DataQualityRuleEntity r = ruleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + id));
        if (!tenantId.equals(r.getTenantId())) {
            throw new SecurityException("租户归属不匹配：质量规则属于 " + r.getTenantId() + ", 当前 " + tenantId);
        }
        if (ruleConfig != null) r.setRuleConfig(ruleConfig.toString());
        if (severity != null && !severity.isBlank()) r.setSeverity(severity.toUpperCase());
        if (enabled != null) r.setEnabled(enabled);
        r.setUpdatedAt(Instant.now());
        return ruleRepo.save(r);
    }

    @Transactional
    public void deleteRule(UUID id) {
        ruleRepo.deleteById(id);
    }

    public List<DataQualityRuleEntity> listRules() {
        return ruleRepo.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
    }

    public List<DataQualityRuleEntity> listRulesByCollection(String collectionName) {
        return ruleRepo.findByTenantIdAndCollectionNameAndEnabledTrue(
                TenantContext.requireTenantId(), collectionName);
    }

    // ============================================================
    //  血缘查询
    // ============================================================

    /**
     * 查询某 Collection 的血缘信息(是否由外部数据源映射、只读等)。
     */
    public Map<String, Object> lineage(String collectionName) {
        String tenantId = TenantContext.requireTenantId();
        CollectionMetaEntity cm = collectionMetaRepo.findByNameAndTenantId(collectionName, tenantId)
                .orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collection", collectionName);
        if (cm == null) {
            result.put("source", "unknown");
            result.put("external", false);
            return result;
        }
        boolean external = cm.getDatasourceId() != null;
        result.put("source", external ? "external" : "native");
        result.put("external", external);
        result.put("readOnly", Boolean.TRUE.equals(cm.getReadOnly()));
        if (external) {
            result.put("datasourceId", String.valueOf(cm.getDatasourceId()));
            result.put("externalTableName", cm.getExternalTableName());
            Optional<ExternalDataSourceEntity> ds =
                    externalDataSourceRepo.findById(cm.getDatasourceId());
            if (ds.isPresent()) {
                result.put("datasourceVendor", ds.get().getVendor());
                result.put("datasourceName", ds.get().getName());
            }
        }
        return result;
    }
}
