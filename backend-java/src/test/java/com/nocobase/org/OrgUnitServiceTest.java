package com.nocobase.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** E1：组织树（物化路径、外部只读、移动重算、通讯录成员）。 */
class OrgUnitServiceTest {

    private OrgUnitRepository repository;
    private UserRepository userRepository;
    private OrgUnitService service;

    private static final String TENANT = "t1";
    private final UUID rootId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(OrgUnitRepository.class);
        userRepository = mock(UserRepository.class);
        service = new OrgUnitService(repository, userRepository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private OrgUnitEntity unit(UUID id, UUID parent, String name, String path, String source) {
        OrgUnitEntity u = new OrgUnitEntity();
        u.setId(id);
        u.setTenantId(TENANT);
        u.setParentId(parent);
        u.setName(name);
        u.setPath(path);
        u.setSource(source);
        return u;
    }

    @Test
    @DisplayName("tree：父子嵌套 + 排序")
    void tree_buildsNestedStructure() {
        OrgUnitEntity root = unit(rootId, null, "总部", "/" + rootId + "/", "LOCAL");
        OrgUnitEntity child = unit(childId, rootId, "研发", "/" + rootId + "/" + childId + "/", "LOCAL");
        when(repository.findByTenantIdAndDeletedFalseOrderBySortAscNameAsc(TENANT))
                .thenReturn(List.of(root, child));

        List<Map<String, Object>> tree = service.tree(TENANT);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0)).containsEntry("id", rootId.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) tree.get(0).get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0)).containsEntry("name", "研发").containsEntry("editable", true);
    }

    @Test
    @DisplayName("createLocal：path = 父 path + 自身 id")
    void createLocal_computesPath() {
        OrgUnitEntity parent = unit(rootId, null, "总部", "/" + rootId + "/", "LOCAL");
        when(repository.findByIdAndTenantIdAndDeletedFalse(rootId, TENANT)).thenReturn(Optional.of(parent));
        when(repository.findById(rootId)).thenReturn(Optional.of(parent));

        OrgUnitEntity created = service.createLocal(TENANT, rootId, "研发", "RD");

        assertThat(created.getPath()).isEqualTo("/" + rootId + "/" + created.getId() + "/");
        assertThat(created.getSource()).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("外部来源子树禁止本地编辑 → 403")
    void createLocal_underExternalParent_forbidden() {
        OrgUnitEntity external = unit(rootId, null, "钉钉部门", "/" + rootId + "/", "DINGTALK");
        when(repository.findByIdAndTenantIdAndDeletedFalse(rootId, TENANT))
                .thenReturn(Optional.of(external));

        assertThatThrownBy(() -> service.createLocal(TENANT, rootId, "子部门", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("upsertExternal：按 external_id 幂等更新，不新建")
    void upsertExternal_updatesExisting() {
        OrgUnitEntity existing = unit(childId, null, "旧名", "/" + childId + "/", "DINGTALK");
        existing.setExternalId("100");
        when(repository.findByTenantIdAndSourceAndExternalIdAndDeletedFalse(TENANT, "DINGTALK", "100"))
                .thenReturn(Optional.of(existing));

        OrgUnitEntity result = service.upsertExternal(TENANT, "DINGTALK", "100", "新名", null, 2);

        assertThat(result.getId()).isEqualTo(childId);
        assertThat(result.getName()).isEqualTo("新名");
        assertThat(result.getSort()).isEqualTo(2);
    }

    @Test
    @DisplayName("upsertExternal：首次创建写入 source/externalId")
    void upsertExternal_createsNew() {
        when(repository.findByTenantIdAndSourceAndExternalIdAndDeletedFalse(TENANT, "DINGTALK", "200"))
                .thenReturn(Optional.empty());

        OrgUnitEntity result = service.upsertExternal(TENANT, "DINGTALK", "200", "新部门", null, 0);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getExternalId()).isEqualTo("200");
        assertThat(result.getPath()).isEqualTo("/" + result.getId() + "/");
    }

    @Test
    @DisplayName("move：不能移动到自己的子树下")
    void move_intoOwnSubtree_rejected() {
        OrgUnitEntity root = unit(rootId, null, "总部", "/" + rootId + "/", "LOCAL");
        OrgUnitEntity child = unit(childId, rootId, "研发", "/" + rootId + "/" + childId + "/", "LOCAL");
        when(repository.findByIdAndTenantIdAndDeletedFalse(rootId, TENANT)).thenReturn(Optional.of(root));
        when(repository.findByIdAndTenantIdAndDeletedFalse(childId, TENANT)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.move(rootId, TENANT, childId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("子树");
    }

    @Test
    @DisplayName("delete：软删（保留历史引用）")
    void delete_softDeletes() {
        OrgUnitEntity u = unit(rootId, null, "总部", "/" + rootId + "/", "LOCAL");
        when(repository.findByIdAndTenantIdAndDeletedFalse(rootId, TENANT)).thenReturn(Optional.of(u));

        service.delete(rootId, TENANT);

        assertThat(u.isDeleted()).isTrue();
        verify(repository).save(u);
    }

    @Test
    @DisplayName("assignUserDepartment：同步写入部门归属")
    void assignUserDepartment_updatesUser() {
        UserEntity user = new UserEntity();
        user.setId(childId);
        user.setTenantId(TENANT);
        when(userRepository.findById(childId)).thenReturn(Optional.of(user));

        service.assignUserDepartment(childId, rootId);

        assertThat(user.getDepartmentId()).isEqualTo(rootId);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("members：关键字过滤 + 子树范围")
    void members_filtersByKeywordAndSubtree() {
        OrgUnitEntity root = unit(rootId, null, "总部", "/" + rootId + "/", "LOCAL");
        when(repository.findByIdAndTenantIdAndDeletedFalse(rootId, TENANT)).thenReturn(Optional.of(root));
        when(repository.findByTenantIdAndPathStartingWithAndDeletedFalse(TENANT, "/" + rootId + "/"))
                .thenReturn(List.of(root));

        UserEntity alice = new UserEntity();
        alice.setId(UUID.randomUUID());
        alice.setUsername("alice");
        alice.setDisplayName("张三");
        alice.setDepartmentId(rootId);
        UserEntity outside = new UserEntity();
        outside.setId(UUID.randomUUID());
        outside.setUsername("bob");
        outside.setDisplayName("李四");
        outside.setDepartmentId(otherId);
        when(userRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                .thenReturn(List.of(alice, outside));

        List<UserEntity> hits = service.members(TENANT, rootId, "张", true);
        assertThat(hits).containsExactly(alice);
    }

    @Test
    @DisplayName("get：不存在 → 404")
    void get_notFound() {
        when(repository.findByIdAndTenantIdAndDeletedFalse(any(), eq(TENANT)))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
