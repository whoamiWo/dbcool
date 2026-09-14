package com.nocobase.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * FormService 单元测试(Week 22 抬红线).
 */
class FormServiceTest {

    private FormRepository repo;
    private FormService service;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TENANT = "tenant_default";

    @BeforeEach
    void setUp() {
        repo = mock(FormRepository.class);
        service = new FormService(repo, mapper);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /* === create === */

    @Test
    void create_setsAllFields() {
        UUID createdBy = UUID.randomUUID();
        var f = service.create("customer", "客户表单", "用于录入客户",
                "[[{\"type\":\"input\"}]]", "{\"name\":{\"required\":true}}",
                TENANT, createdBy);
        assertThat(f.getId()).isNotNull();
        assertThat(f.getCollectionName()).isEqualTo("customer");
        assertThat(f.getTitle()).isEqualTo("客户表单");
        assertThat(f.getDescription()).isEqualTo("用于录入客户");
        assertThat(f.getLayoutJson()).isEqualTo("[[{\"type\":\"input\"}]]");
        assertThat(f.getRulesJson()).isEqualTo("{\"name\":{\"required\":true}}");
        assertThat(f.getTenantId()).isEqualTo(TENANT);
        assertThat(f.getCreatedAt()).isNotNull();
        assertThat(f.getCreatedBy()).isEqualTo(createdBy);
    }

    @Test
    void create_nullLayout_defaultsToEmpty() {
        var f = service.create("customer", "t", "d", null, null, TENANT, UUID.randomUUID());
        assertThat(f.getLayoutJson()).isEqualTo("[]");
        assertThat(f.getRulesJson()).isEqualTo("{}");
    }

    @Test
    void create_blankLayout_defaultsToEmpty() {
        var f = service.create("customer", "t", "d", "", "  ", TENANT, UUID.randomUUID());
        assertThat(f.getLayoutJson()).isEqualTo("[]");
        assertThat(f.getRulesJson()).isEqualTo("{}");
    }

    /* === get === */

    @Test
    void get_returnsForm() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(makeForm(id)));
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
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(makeForm(id)));
        var f = service.update(id, "新标题", "新描述",
                "[[]]", "{}", TENANT);
        assertThat(f.getTitle()).isEqualTo("新标题");
        assertThat(f.getDescription()).isEqualTo("新描述");
        assertThat(f.getLayoutJson()).isEqualTo("[[]]");
        assertThat(f.getRulesJson()).isEqualTo("{}");
        assertThat(f.getUpdatedAt()).isNotNull();
    }

    @Test
    void update_partialFields_keepsUntouched() {
        UUID id = UUID.randomUUID();
        FormEntity orig = makeForm(id);
        orig.setTitle("orig_title");
        orig.setLayoutJson("[1]");
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(orig));
        var f = service.update(id, "new_title", null, null, null, TENANT);
        assertThat(f.getTitle()).isEqualTo("new_title");
        assertThat(f.getDescription()).isEqualTo(orig.getDescription());
        assertThat(f.getLayoutJson()).isEqualTo("[1]");
    }

    @Test
    void update_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(id, "t", "d", null, null, TENANT))
                .isInstanceOf(ResponseStatusException.class);
    }

    /* === list === */

    @Test
    void listByCollection_delegates() {
        var rows = List.of(makeForm(UUID.randomUUID()), makeForm(UUID.randomUUID()));
        when(repo.findByCollectionNameAndTenantId("customer", TENANT)).thenReturn(rows);
        assertThat(service.listByCollection("customer", TENANT)).hasSize(2);
    }

    @Test
    void listAll_delegates() {
        var rows = List.of(makeForm(UUID.randomUUID()));
        when(repo.findByTenantId(TENANT)).thenReturn(rows);
        assertThat(service.listAll(TENANT)).hasSize(1);
    }

    /* === delete === */

    @Test
    void delete_existing_removes() {
        UUID id = UUID.randomUUID();
        FormEntity f = makeForm(id);
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(f));
        service.delete(id, TENANT);
        org.mockito.Mockito.verify(repo).delete(f);
    }

    @Test
    void delete_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(id, TENANT))
                .isInstanceOf(ResponseStatusException.class);
    }

    /* === parseLayout / parseRules === */

    @Test
    void parseLayout_returnsListOfMaps() {
        FormEntity f = makeForm(UUID.randomUUID());
        f.setLayoutJson("[{\"type\":\"input\",\"name\":\"f1\"},{\"type\":\"select\"}]");
        var layout = service.parseLayout(f);
        assertThat(layout).hasSize(2);
        assertThat(layout.get(0).get("type")).isEqualTo("input");
        assertThat(layout.get(0).get("name")).isEqualTo("f1");
        assertThat(layout.get(1).get("type")).isEqualTo("select");
    }

    @Test
    void parseRules_returnsMap() {
        FormEntity f = makeForm(UUID.randomUUID());
        f.setRulesJson("{\"f1\":{\"required\":true,\"min\":0},\"f2\":{\"max\":100}}");
        Map<String, Object> rules = service.parseRules(f);
        assertThat(rules).containsKey("f1").containsKey("f2");
        @SuppressWarnings("unchecked")
        Map<String, Object> f1 = (Map<String, Object>) rules.get("f1");
        assertThat(f1.get("required")).isEqualTo(true);
    }

    @Test
    void parseLayout_invalidJson_throwsRuntime() {
        FormEntity f = makeForm(UUID.randomUUID());
        f.setLayoutJson("{not valid");
        assertThatThrownBy(() -> service.parseLayout(f))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("layout");
    }

    @Test
    void parseRules_invalidJson_throwsRuntime() {
        FormEntity f = makeForm(UUID.randomUUID());
        f.setRulesJson("{not valid");
        assertThatThrownBy(() -> service.parseRules(f))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("rules");
    }

    private FormEntity makeForm(UUID id) {
        FormEntity f = new FormEntity();
        f.setId(id);
        f.setCollectionName("customer");
        f.setTitle("test form");
        f.setDescription("desc");
        f.setLayoutJson("[]");
        f.setRulesJson("{}");
        f.setTenantId(TENANT);
        return f;
    }
}