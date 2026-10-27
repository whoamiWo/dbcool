package com.nocobase.governance;

import com.nocobase.integration.datasource.ExternalDataSourceRepository;
import com.nocobase.meta.CollectionRepository;
import com.nocobase.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** B7: 数据治理服务单测。 */
@ExtendWith(MockitoExtension.class)
class DataGovernanceServiceTest {

    @Mock
    private RetentionPolicyRepository policyRepo;

    @Mock
    private DataQualityRuleRepository ruleRepo;

    @Mock
    private CollectionRepository collectionRepo;

    @Mock
    private ExternalDataSourceRepository externalDataSourceRepo;

    private DataGovernanceService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant_test");
        service = new DataGovernanceService(policyRepo, ruleRepo, collectionRepo, externalDataSourceRepo);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createPolicy_defaultsApplied() {
        when(policyRepo.save(any(RetentionPolicyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RetentionPolicyEntity p = service.createPolicy("users", null, null, true);
        assertEquals("users", p.getCollectionName());
        assertEquals("ARCHIVE", p.getAction());
        assertEquals(365, p.getRetentionDays());
        assertEquals(true, p.getEnabled());
        assertEquals("tenant_test", p.getTenantId());
        assertNotNull(p.getId());
    }

    @Test
    void createPolicy_blankCollection_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy("  ", "ARCHIVE", 30, true));
    }

    @Test
    void createRule_invalidType_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.createRule("users", "email", "", Map.of(), "WARN", true));
    }

    @Test
    void createRule_defaultsApplied() {
        when(ruleRepo.save(any(DataQualityRuleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity r = service.createRule(
                "users", "email", "regex", Map.of("pattern", ".+@.+")
                        .toString().split("") == null ? Map.of() : Map.of("pattern", ".+@.+" + ""),
                null, false);
        assertEquals("REGEX", r.getRuleType());
        assertEquals("WARN", r.getSeverity());
        assertEquals(false, r.getEnabled());
        assertEquals("tenant_test", r.getTenantId());
    }

    @Test
    void lineage_nativeCollection_noDatasource() {
        com.nocobase.meta.CollectionMetaEntity cm = new com.nocobase.meta.CollectionMetaEntity();
        cm.setName("users");
        cm.setTenantId("tenant_test");
        cm.setReadOnly(false);
        when(collectionRepo.findByNameAndTenantId("users", "tenant_test"))
                .thenReturn(java.util.Optional.of(cm));

        Map<String, Object> lineage = service.lineage("users");
        assertEquals("native", lineage.get("source"));
        assertEquals(false, lineage.get("external"));
        assertEquals(false, lineage.get("readOnly"));
    }

    @Test
    void lineage_unknownCollection() {
        when(collectionRepo.findByNameAndTenantId("ghost", "tenant_test"))
                .thenReturn(java.util.Optional.empty());

        Map<String, Object> lineage = service.lineage("ghost");
        assertEquals("unknown", lineage.get("source"));
        assertEquals(false, lineage.get("external"));
    }
}
