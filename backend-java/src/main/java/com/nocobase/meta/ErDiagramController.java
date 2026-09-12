package com.nocobase.meta;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ER Diagram 数据 API(Week 14.5 P3-5).
 *
 * <p>GET /api/admin/er-diagram — 返回所有 collections + 它们之间的 belongsTo/hasMany 关系。
 * 前端用此数据画 ER 图。
 */
@RestController
@Tag(name = "ER Diagram", description = "Collection 关系图数据")
@RequestMapping("/api/admin/er-diagram")
public class ErDiagramController {

    private final CollectionService collectionService;

    public ErDiagramController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @GetMapping
    public Map<String, Object> diagram(@AuthenticationPrincipal AuthenticatedUser user) {
        List<CollectionMetaEntity> collections = collectionService.list(user.tenantId());

        // 1. 节点
        List<Map<String, Object>> nodes = new ArrayList<>();
        // name -> index in nodes(用于 edges source/target 引用)
        Map<String, Integer> nameToIndex = new LinkedHashMap<>();

        for (CollectionMetaEntity c : collections) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", c.getName());
            node.put("title", c.getTitle() != null ? c.getTitle() : c.getName());
            List<FieldDef> fields = collectionService.parseFields(c);
            node.put("field_count", fields.size());
            node.put("field_types", fields.stream().map(FieldDef::type).toList());
            // 仅用于前端预览:列字段名(限 8 个)
            List<String> fieldNames = fields.stream().map(FieldDef::name).limit(8).toList();
            node.put("fields_preview", fieldNames);
            nodes.add(node);
            nameToIndex.put(c.getName(), nodes.size() - 1);
        }

        // 2. 边 — 遍历每个 collection 的 belongsTo/hasMany 字段
        List<Map<String, Object>> edges = new ArrayList<>();
        for (CollectionMetaEntity c : collections) {
            List<FieldDef> fields = collectionService.parseFields(c);
            for (FieldDef f : fields) {
                if (!"belongsTo".equals(f.type()) && !"hasMany".equals(f.type())) continue;
                String target = null;
                if (f.options() != null) {
                    Object t = f.options().get("target") != null
                            ? f.options().get("target")
                            : f.options().get("collection");
                    if (t != null) target = t.toString();
                }
                if (target == null || !nameToIndex.containsKey(target)) continue;
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("source", c.getName());
                edge.put("target", target);
                edge.put("field", f.name());
                edge.put("type", f.type());
                edges.add(edge);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodes);
        data.put("edges", edges);
        data.put("stats", Map.of(
                "collections", nodes.size(),
                "relationships", edges.size()));
        return Map.of("code", 0, "message", "success", "data", data);
    }
}
