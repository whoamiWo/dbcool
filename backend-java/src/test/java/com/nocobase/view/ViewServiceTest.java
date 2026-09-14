package com.nocobase.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * ViewService 单元测试(Week 21 抬红线).
 */
class ViewServiceTest {

    private ViewRepository repo;
    private ViewService service;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TENANT = "tenant_default";

    @BeforeEach
    void setUp() {
        repo = mock(ViewRepository.class);
        service = new ViewService(repo, mapper);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /* === create === */

    @Test
    void create_setsAllFields() {
        UUID createdBy = UUID.randomUUID();
        var v = service.create("customer", "my_view", "我的视图",
                ViewEntity.Type.TABLE, "{\"pageSize\":20}", "[]", TENANT, createdBy);
        assertThat(v.getId()).isNotNull();
        assertThat(v.getCollectionName()).isEqualTo("customer");
        assertThat(v.getName()).isEqualTo("my_view");
        assertThat(v.getTitle()).isEqualTo("我的视图");
        assertThat(v.getType()).isEqualTo(ViewEntity.Type.TABLE);
        assertThat(v.getConfigJson()).isEqualTo("{\"pageSize\":20}");
        assertThat(v.getSharedWithJson()).isEqualTo("[]");
        assertThat(v.getTenantId()).isEqualTo(TENANT);
        assertThat(v.getCreatedAt()).isNotNull();
        assertThat(v.getCreatedBy()).isEqualTo(createdBy);
    }

    @Test
    void create_nullConfigJson_defaultsToEmptyObject() {
        var v = service.create("customer", "v", "T",
                ViewEntity.Type.TABLE, null, null, TENANT, UUID.randomUUID());
        assertThat(v.getConfigJson()).isEqualTo("{}");
        assertThat(v.getSharedWithJson()).isEqualTo("[]");
    }

    @Test
    void create_blankConfigJson_defaultsToEmptyObject() {
        var v = service.create("customer", "v", "T",
                ViewEntity.Type.TABLE, "", "  ", TENANT, UUID.randomUUID());
        assertThat(v.getConfigJson()).isEqualTo("{}");
        assertThat(v.getSharedWithJson()).isEqualTo("[]");
    }

    /* === get === */

    @Test
    void get_returnsView() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(makeView(id)));
        assertThat(service.get(id, TENANT).getId()).isEqualTo(id);
    }

    @Test
    void get_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /* === update === */

    @Test
    void update_modifiesAllFields() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(makeView(id)));
        var v = service.update(id, "new_name", "新标题",
                "{\"k\":1}", "[\"u1\"]", TENANT);
        assertThat(v.getName()).isEqualTo("new_name");
        assertThat(v.getTitle()).isEqualTo("新标题");
        assertThat(v.getConfigJson()).isEqualTo("{\"k\":1}");
        assertThat(v.getSharedWithJson()).isEqualTo("[\"u1\"]");
        assertThat(v.getUpdatedAt()).isNotNull();
    }

    @Test
    void update_partialFields_keepsUntouched() {
        UUID id = UUID.randomUUID();
        ViewEntity orig = makeView(id);
        orig.setName("orig_name");
        orig.setTitle("orig_title");
        orig.setConfigJson("{\"k\":1}");
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(orig));
        var v = service.update(id, "new_name", null, null, null, TENANT);
        assertThat(v.getName()).isEqualTo("new_name");
        assertThat(v.getTitle()).isEqualTo("orig_title");
        assertThat(v.getConfigJson()).isEqualTo("{\"k\":1}");
    }

    @Test
    void update_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(id, "n", "t", null, null, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /* === list === */

    @Test
    void listByCollection_delegatesToRepo() {
        var rows = List.of(makeView(UUID.randomUUID()), makeView(UUID.randomUUID()));
        when(repo.findByCollectionNameAndTenantIdOrderByCreatedAtDesc("customer", TENANT))
                .thenReturn(rows);
        assertThat(service.listByCollection("customer", TENANT)).hasSize(2);
    }

    @Test
    void listAll_delegatesToRepo() {
        var rows = List.of(makeView(UUID.randomUUID()));
        when(repo.findByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(rows);
        assertThat(service.listAll(TENANT)).hasSize(1);
    }

    /* === delete === */

    @Test
    void delete_existing_removes() {
        UUID id = UUID.randomUUID();
        ViewEntity v = makeView(id);
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(v));
        service.delete(id, TENANT);
        org.mockito.Mockito.verify(repo).delete(v);
    }

    @Test
    void delete_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(id, TENANT))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ViewEntity makeView(UUID id) {
        ViewEntity v = new ViewEntity();
        v.setId(id);
        v.setCollectionName("customer");
        v.setName("v");
        v.setTitle("T");
        v.setType(ViewEntity.Type.TABLE);
        v.setConfigJson("{}");
        v.setSharedWithJson("[]");
        v.setTenantId(TENANT);
        return v;
    }
}