package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InverseRelationManagerTest {

    private CollectionRepository repo;
    private InverseRelationManager mgr;
    private ObjectMapper mapper = new ObjectMapper();

    private CollectionMetaEntity makeMeta(String name, String tenant, List<FieldDef> fields) {
        CollectionMetaEntity m = new CollectionMetaEntity();
        m.setId(UUID.randomUUID());
        m.setName(name);
        m.setTenantId(tenant);
        try {
            m.setFieldsJson(mapper.writeValueAsString(fields));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    private FieldDef textField(String name) {
        return new FieldDef(name, "text", false, name, java.util.Map.of());
    }

    private FieldDef belongsToField(String name, String target) {
        return new FieldDef(name, "belongsTo", false, name,
                java.util.Map.of("target", target));
    }

    @BeforeEach
    void setUp() {
        repo = mock(CollectionRepository.class);
        mgr = new InverseRelationManager(repo, mapper);
    }

    @Test
    void handleAddedField_notBelongsTo_returnsNull() {
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        FieldDef text = textField("title");
        assertThat(mgr.handleAddedField(src, text)).isNull();
    }

    @Test
    void handleAddedField_missingTarget_skipped() {
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        FieldDef bt = new FieldDef("author", "belongsTo", false, "Author", null);
        assertThat(mgr.handleAddedField(src, bt)).isNull();
    }

    @Test
    void handleAddedField_selfRef_skipped() {
        CollectionMetaEntity src = makeMeta("users", "t1", List.of());
        FieldDef self = belongsToField("manager", "users");
        assertThat(mgr.handleAddedField(src, self)).isNull();
    }

    @Test
    void handleAddedField_crossTenant_skipped() {
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        FieldDef bt = belongsToField("author", "users");
        when(repo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.empty());
        assertThat(mgr.handleAddedField(src, bt)).isNull();
    }

    @Test
    void handleAddedField_createsInverseFieldInTarget() {
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        CollectionMetaEntity tgt = makeMeta("users", "t1", List.of(textField("name")));
        when(repo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(tgt));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FieldDef bt = belongsToField("author", "users");
        String inverseName = mgr.handleAddedField(src, bt);

        assertThat(inverseName).isEqualTo("posts_author");
        // target 已更新 — 包含 posts_author
        List<FieldDef> updated = parseFields(tgt);
        assertThat(updated).extracting(FieldDef::name).contains("posts_author");
        FieldDef inv = updated.stream().filter(f -> f.name().equals("posts_author")).findFirst().get();
        assertThat(inv.type()).isEqualTo("hasMany");
        assertThat(inv.options()).containsEntry("target", "posts");
        assertThat(inv.options()).containsEntry("_inverseOf", "author");
        assertThat(inv.options()).containsEntry("_inverseSource", "posts");
        assertThat(inv.options()).containsEntry("_autoManaged", true);
    }

    @Test
    void handleAddedField_idempotent_skipsDuplicate() {
        FieldDef existing = new FieldDef("posts_author", "hasMany", false, null,
                java.util.Map.of("target", "posts", "_inverseOf", "author",
                        "_inverseSource", "posts", "_autoManaged", true));
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        CollectionMetaEntity tgt = makeMeta("users", "t1", List.of(existing));
        when(repo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(tgt));

        FieldDef bt = belongsToField("author", "users");
        String result = mgr.handleAddedField(src, bt);

        assertThat(result).isEqualTo("posts_author");
        // 没新增重复
        assertThat(parseFields(tgt)).hasSize(1);
    }

    @Test
    void handleAddedField_savesTarget() {
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        CollectionMetaEntity tgt = makeMeta("users", "t1", List.of());
        when(repo.findByNameAndTenantId("users", "t1")).thenReturn(Optional.of(tgt));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FieldDef bt = belongsToField("author", "users");
        mgr.handleAddedField(src, bt);

        // save 应被调用至少 1 次(target)
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void handleRemovedField_noInverse_returnsFalse() {
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        when(repo.findByTenantId("t1")).thenReturn(List.of());
        boolean result = mgr.handleRemovedField(src, "author");
        assertThat(result).isFalse();
    }

    @Test
    void handleRemovedField_removesInverseFromTarget() {
        // target.users 有 posts_author(标记 _inverseOf=author, _inverseSource=posts)
        FieldDef inverse = new FieldDef("posts_author", "hasMany", false, null,
                java.util.Map.of("target", "posts", "_inverseOf", "author",
                        "_inverseSource", "posts"));
        CollectionMetaEntity usersMeta = makeMeta("users", "t1", List.of(inverse, textField("name")));
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        when(repo.findByTenantId("t1")).thenReturn(List.of(usersMeta));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean removed = mgr.handleRemovedField(src, "author");

        assertThat(removed).isTrue();
        // posts_author 应被删,name 保留
        List<FieldDef> afterFields = parseFields(usersMeta);
        assertThat(afterFields).extracting(FieldDef::name).doesNotContain("posts_author");
        assertThat(afterFields).extracting(FieldDef::name).contains("name");
    }

    @Test
    void handleRemovedField_onlyRemovesExactMatch() {
        // target 有两个 hasMany,但只有 posts_author 标记 _inverseOf=author
        FieldDef keep = new FieldDef("comments_author", "hasMany", false, null,
                java.util.Map.of("target", "comments", "_inverseOf", "reviewer",
                        "_inverseSource", "comments"));
        FieldDef remove = new FieldDef("posts_author", "hasMany", false, null,
                java.util.Map.of("target", "posts", "_inverseOf", "author",
                        "_inverseSource", "posts"));
        CollectionMetaEntity usersMeta = makeMeta("users", "t1", List.of(keep, remove));
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        when(repo.findByTenantId("t1")).thenReturn(List.of(usersMeta));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mgr.handleRemovedField(src, "author");

        List<FieldDef> after = parseFields(usersMeta);
        assertThat(after).extracting(FieldDef::name).contains("comments_author");
        assertThat(after).extracting(FieldDef::name).doesNotContain("posts_author");
    }

    @Test
    void handleRemovedField_skipsOtherTenantCollections() {
        FieldDef inverse = new FieldDef("posts_author", "hasMany", false, null,
                java.util.Map.of("target", "posts", "_inverseOf", "author",
                        "_inverseSource", "posts"));
        CollectionMetaEntity otherTenantMeta = makeMeta("users", "t2", List.of(inverse));
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        // 用错误的 tenantId 查询 — 应返回空
        when(repo.findByTenantId("t1")).thenReturn(List.of());
        when(repo.findByTenantId("t2")).thenReturn(List.of(otherTenantMeta));

        boolean removed = mgr.handleRemovedField(src, "author");

        assertThat(removed).isFalse();
        // 跨租户 collection 不应被修改
        assertThat(parseFields(otherTenantMeta)).hasSize(1);
    }

    @Test
    void handleRemovedField_inverseFieldNameMismatch_skipped() {
        // 实际命名与约定不符 — 不会误删
        FieldDef wrongName = new FieldDef("weird_name", "hasMany", false, null,
                java.util.Map.of("target", "posts", "_inverseOf", "author",
                        "_inverseSource", "posts"));
        CollectionMetaEntity usersMeta = makeMeta("users", "t1", List.of(wrongName));
        CollectionMetaEntity src = makeMeta("posts", "t1", List.of());
        when(repo.findByTenantId("t1")).thenReturn(List.of(usersMeta));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean removed = mgr.handleRemovedField(src, "author");
        assertThat(removed).isFalse();
    }

    private List<FieldDef> parseFields(CollectionMetaEntity m) {
        try {
            return mapper.readValue(m.getFieldsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<FieldDef>>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
