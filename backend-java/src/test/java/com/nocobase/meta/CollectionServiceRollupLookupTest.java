package com.nocobase.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PHASE105 补充:覆盖 CollectionService.rollup / lookup 字段求值链路
 * (applyRollupAndLookup / resolveRelatedRecords / resolveTargetCollection)。
 *
 * <p>背景:该链路此前仅靠 fieldsJson 含 rollup/lookup 字段的部分用例间接覆盖，
 * 大量分支(无 targetCollection 回退、关联记录加载失败回退、_inverseSource 优先级)未被测试。
 */
public class CollectionServiceRollupLookupTest {

    private CollectionRepository repository;
    private DynamicTableManager tableManager;
    private AsyncMigrationService migrationService;
    private CollectionService service;

    private static final String TENANT_ID = "tenant_default";
    private static final String COLLECTION = "orders";

    private CollectionMetaEntity meta;

    @BeforeEach
    void setUp() {
        repository = mock(CollectionRepository.class);
        tableManager = mock(DynamicTableManager.class);
        migrationService = mock(AsyncMigrationService.class);
        service = new CollectionService(repository, tableManager, migrationService, new ObjectMapper());

        meta = new CollectionMetaEntity();
        meta.setId(UUID.randomUUID());
        meta.setName(COLLECTION);
        meta.setTitle("订单");
        meta.setTenantId(TENANT_ID);
        when(repository.findByNameAndTenantId(COLLECTION, TENANT_ID)).thenReturn(Optional.of(meta));
        when(repository.findByName(COLLECTION)).thenReturn(Optional.of(meta));
        when(repository.save(any(CollectionMetaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Mockito.doNothing().when(tableManager).createTable(anyString());
        Mockito.doNothing().when(tableManager).insertRecord(anyString(), anyString(), anyString());
        Mockito.doNothing().when(tableManager).addPhysicalColumn(anyString(), anyString(), anyString());
        when(tableManager.updateRecord(anyString(), anyString(), anyString())).thenReturn(1);
        when(tableManager.deleteRecord(anyString(), anyString())).thenReturn(1);
        when(tableManager.listRecords(anyString(), anyInt(), any(), anyList()))
                .thenReturn(List.of());
    }

    private void fieldsJson(String json) {
        meta.setFieldsJson(json);
    }

    /** 主记录:order.1 含 items 关联(id 数组,hasMany)指向 order_items 表。*/
    private void givenOrderRecord(String itemIdJson) {
        when(tableManager.getRecord("orders", "1"))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"1\",\"amount\":100,\"items\":[{\"id\":\"r1\"},{\"id\":\"r2\"}]}}"));
        when(tableManager.getRecord("order_items", "r1"))
                .thenReturn(Optional.of(itemIdJson));
        when(tableManager.getRecord("order_items", "r2"))
                .thenReturn(Optional.empty());
    }

    @Test
    void rollup_sum_withInverseSource_loadsFullRecords() {
        // items 字段带 _inverseSource=order_items;rollup 字段汇总关联记录的 price
        fieldsJson("["
                + "{\"name\":\"amount\",\"type\":\"number\"},"
                + "{\"name\":\"items\",\"type\":\"hasMany\",\"required\":false,\"label\":\"明细\",\"options\":{\"_inverseSource\":\"order_items\"}},"
                + "{\"name\":\"totalPrice\",\"type\":\"rollup\",\"required\":false,\"label\":\"总额\",\"options\":{\"relationField\":\"items\",\"targetField\":\"price\",\"agg\":\"SUM\"}}"
                + "]");
        givenOrderRecord("{\"data\":{\"id\":\"r1\",\"price\":30}}");
        when(tableManager.getRecord("order_items", "r2"))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"r2\",\"price\":50}}"));

        Map<String, Object> record = service.getRecord(COLLECTION, "1", TENANT_ID);

        // RollupEngine.aggregate 返回 [{value=80.0}]（SUM=30+50）
        assertThat(rollupValue(record, "totalPrice")).isEqualTo(80.0);
    }

    @Test
    void rollup_count_withoutTargetCollection_fallsBackToIdTitle() {
        // 无 _inverseSource/targetCollection → resolveRelatedRecords 退化 id/title,COUNT 仍可用
        fieldsJson("["
                + "{\"name\":\"items\",\"type\":\"hasMany\",\"required\":false,\"label\":\"明细\",\"options\":{}},"
                + "{\"name\":\"itemCount\",\"type\":\"rollup\",\"required\":false,\"label\":\"数量\",\"options\":{\"relationField\":\"items\",\"targetField\":\"price\",\"agg\":\"COUNT\"}}"
                + "]");
        givenOrderRecord("{\"data\":{\"id\":\"r1\",\"price\":30}}");

        Map<String, Object> record = service.getRecord(COLLECTION, "1", TENANT_ID);

        // COUNT 不依赖字段值,即使记录缺失也能计数
        assertThat(rollupValue(record, "itemCount")).isEqualTo(2L);
    }

    @Test
    void rollup_missingRelatedRecord_contributesZeroButNotFail() {
        // r2 在物理表不存在 → resolveRelatedRecords 回退 Map.of("id", id),SUM 只累计命中的
        fieldsJson("["
                + "{\"name\":\"items\",\"type\":\"hasMany\",\"required\":false,\"label\":\"明细\",\"options\":{\"targetCollection\":\"order_items\"}},"
                + "{\"name\":\"totalPrice\",\"type\":\"rollup\",\"required\":false,\"label\":\"总额\",\"options\":{\"relationField\":\"items\",\"targetField\":\"price\",\"agg\":\"SUM\"}}"
                + "]");
        givenOrderRecord("{\"data\":{\"id\":\"r1\",\"price\":30}}");
        when(tableManager.getRecord("order_items", "r2")).thenReturn(Optional.empty());

        Map<String, Object> record = service.getRecord(COLLECTION, "1", TENANT_ID);

        assertThat(rollupValue(record, "totalPrice")).isEqualTo(30.0);
    }

    @Test
    void lookup_picksTargetFieldFromFirstRelatedRecord() {
        fieldsJson("["
                + "{\"name\":\"items\",\"type\":\"hasMany\",\"required\":false,\"label\":\"明细\",\"options\":{\"targetCollection\":\"order_items\"}},"
                + "{\"name\":\"firstItemName\",\"type\":\"lookup\",\"required\":false,\"label\":\"首条明细名\",\"options\":{\"relationField\":\"items\",\"targetField\":\"name\"}}"
                + "]");
        givenOrderRecord("{\"data\":{\"id\":\"r1\",\"name\":\"商品A\"}}");
        when(tableManager.getRecord("order_items", "r2"))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"r2\",\"name\":\"商品B\"}}"));

        Map<String, Object> record = service.getRecord(COLLECTION, "1", TENANT_ID);

        assertThat(record.get("firstItemName")).isEqualTo("商品A");
    }

    @Test
    void rollup_relatedRawIsNull_skipsField() {
        // items 为 null → applyRollupAndLookup 直接跳过,不写入 totalPrice
        fieldsJson("["
                + "{\"name\":\"items\",\"type\":\"hasMany\",\"required\":false,\"label\":\"明细\",\"options\":{\"targetCollection\":\"order_items\"}},"
                + "{\"name\":\"totalPrice\",\"type\":\"rollup\",\"required\":false,\"label\":\"总额\",\"options\":{\"relationField\":\"items\",\"targetField\":\"price\",\"agg\":\"SUM\"}}"
                + "]");
        when(tableManager.getRecord("orders", "1"))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"1\",\"amount\":100}}"));

        Map<String, Object> record = service.getRecord(COLLECTION, "1", TENANT_ID);

        assertThat(record).doesNotContainKey("totalPrice");
    }

    @Test
    void rollup_badJsonOnRelatedRecord_fallsBackToIdOnly() {
        // 关联记录 JSON 损坏 → catch 分支,result.add(Map.of("id", id)),SUM 取不到数→0
        fieldsJson("["
                + "{\"name\":\"items\",\"type\":\"hasMany\",\"required\":false,\"label\":\"明细\",\"options\":{\"targetCollection\":\"order_items\"}},"
                + "{\"name\":\"totalPrice\",\"type\":\"rollup\",\"required\":false,\"label\":\"总额\",\"options\":{\"relationField\":\"items\",\"targetField\":\"price\",\"agg\":\"SUM\"}}"
                + "]");
        when(tableManager.getRecord("orders", "1"))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"1\",\"items\":[{\"id\":\"r1\"}]}}"));
        when(tableManager.getRecord("order_items", "r1"))
                .thenReturn(Optional.of("not-json{{"));

        Map<String, Object> record = service.getRecord(COLLECTION, "1", TENANT_ID);

        assertThat(rollupValue(record, "totalPrice")).isEqualTo(0.0);
    }

    @Test
    void rollup_belongsToSingleMap_resolvesId() {
        // belongsTo 场景:relatedRaw 是单个 Map {id: r1}
        fieldsJson("["
                + "{\"name\":\"item\",\"type\":\"belongsTo\",\"required\":false,\"label\":\"明细\",\"options\":{\"targetCollection\":\"order_items\"}},"
                + "{\"name\":\"itemPrice\",\"type\":\"rollup\",\"required\":false,\"label\":\"单价\",\"options\":{\"relationField\":\"item\",\"targetField\":\"price\",\"agg\":\"MAX\"}}"
                + "]");
        when(tableManager.getRecord("orders", "1"))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"1\",\"item\":{\"id\":\"r1\"}}}"));
        when(tableManager.getRecord("order_items", "r1"))
                .thenReturn(Optional.of("{\"data\":{\"id\":\"r1\",\"price\":42}}"));

        Map<String, Object> record = service.getRecord(COLLECTION, "1", TENANT_ID);

        assertThat(rollupValue(record, "itemPrice")).isEqualTo(42.0);
    }

    /**
     * RollupEngine.aggregate 把结果包在 List<Map<String,Object>>（key=value）里，
     * 此处取标量值做断言。lookup 走 record.put 标量，故另用 record.get。
     */
    private static Object rollupValue(Map<String, Object> record, String field) {
        Object v = record.get(field);
        if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            return m.get("value");
        }
        return v;
    }
}
