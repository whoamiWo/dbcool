package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.AclPolicyEntity;
import com.nocobase.auth.AclPolicyRepository;
import com.nocobase.auth.RoleEntity;
import com.nocobase.auth.RoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * CollectionService T1: 新建集合自动播种 ACL 的单测。
 */
class CollectionServiceAclSeedTest {

    @Mock
    private CollectionRepository collectionRepository;

    @Mock
    private DynamicTableManager tableManager;

    @Mock
    private AsyncMigrationService migrationService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AclPolicyRepository aclPolicyRepository;

    private CollectionService service;

@BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new CollectionService(
                collectionRepository, tableManager, migrationService, objectMapper,
                null, null, null, roleRepository, aclPolicyRepository);

        when(objectMapper.writeValueAsString(any())).thenReturn("[]");
    }

    @Test
    void create_collectionCreated_adminAclSeeded() {
        // Given
        String collectionName = "test_acl_seed";
        String tenantId = "tenant_001";
        UUID adminRoleId = UUID.randomUUID();

        RoleEntity adminRole = new RoleEntity();
        adminRole.setId(adminRoleId);
        adminRole.setName("admin");
        adminRole.setTenantId(tenantId);

        when(roleRepository.findByNameAndTenantId("admin", tenantId))
                .thenReturn(Optional.of(adminRole));
        when(aclPolicyRepository.findByRoleIdAndTenantId(adminRoleId, tenantId))
                .thenReturn(List.of()); // 初始无策略
        when(collectionRepository.existsByName(collectionName)).thenReturn(false);
        when(collectionRepository.save(any(CollectionMetaEntity.class)))
                .thenAnswer(i -> i.getArgument(0));

        // When
        service.create(collectionName, "Test", "Desc", null, tenantId, UUID.randomUUID());

        // Then
        verify(aclPolicyRepository, org.mockito.Mockito.times(4)).save(any(AclPolicyEntity.class));
    }

    @Test
    void create_existingPolicies_notDuplicate() {
        // Given
        String collectionName = "test_no_dup";
        String tenantId = "tenant_002";
        UUID adminRoleId = UUID.randomUUID();

        RoleEntity adminRole = new RoleEntity();
        adminRole.setId(adminRoleId);
        adminRole.setName("admin");
        adminRole.setTenantId(tenantId);

        AclPolicyEntity existingRead = new AclPolicyEntity();
        existingRead.setId(UUID.randomUUID());
        existingRead.setRoleId(adminRoleId);
        existingRead.setType(AclPolicyEntity.Type.ACTION);
        existingRead.setSubject(collectionName);
        existingRead.setAction(AclPolicyEntity.Action.READ);
        existingRead.setConfigJson("{}");
        existingRead.setTenantId(tenantId);

        when(roleRepository.findByNameAndTenantId("admin", tenantId))
                .thenReturn(Optional.of(adminRole));
        when(aclPolicyRepository.findByRoleIdAndTenantId(adminRoleId, tenantId))
                .thenReturn(List.of(existingRead)); // 已有 READ
        when(collectionRepository.existsByName(collectionName)).thenReturn(false);
        when(collectionRepository.save(any(CollectionMetaEntity.class)))
                .thenAnswer(i -> i.getArgument(0));

        // When
        service.create(collectionName, "Test", "Desc", null, tenantId, UUID.randomUUID());

        // Then: 应只创建 CREATE/UPDATE/DELETE 三个，跳过 READ
        verify(aclPolicyRepository, org.mockito.Mockito.times(3)).save(any(AclPolicyEntity.class));
    }

    @Test
    void create_noAdminRole_skipSeeding_noError() {
        // Given
        String collectionName = "test_no_admin";
        String tenantId = "tenant_003";

        when(roleRepository.findByNameAndTenantId("admin", tenantId))
                .thenReturn(Optional.empty());
        when(collectionRepository.existsByName(collectionName)).thenReturn(false);
        when(collectionRepository.save(any(CollectionMetaEntity.class)))
                .thenAnswer(i -> i.getArgument(0));

        // When
        service.create(collectionName, "Test", "Desc", null, tenantId, UUID.randomUUID());

        // Then: 不抛异常，只是跳过播种
        verify(aclPolicyRepository, org.mockito.Mockito.never()).save(any(AclPolicyEntity.class));
    }

    @Test
    void create_nullRepositories_skipSeeding_noError() throws Exception {
        // Given
        CollectionService serviceWithoutDeps = new CollectionService(
                collectionRepository, tableManager, migrationService, objectMapper);

        String collectionName = "test_null_deps";
        when(collectionRepository.existsByName(collectionName)).thenReturn(false);
        when(collectionRepository.save(any(CollectionMetaEntity.class)))
                .thenAnswer(i -> i.getArgument(0));

        // When
        serviceWithoutDeps.create(collectionName, "Test", "Desc", null, "t1", UUID.randomUUID());

        // Then: 不抛异常
    }
}
