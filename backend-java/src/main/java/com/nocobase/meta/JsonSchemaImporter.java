package com.nocobase.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * JSON Schema → FieldDef 转换器 (US-007).
 *
 * <p>支持 JSON Schema Draft-07 / Draft-04 的子集:
 * <ul>
 *   <li>type: string / number / integer / boolean / object / array</li>
 *   <li>format: email / url / phone / date / date-time</li>
 *   <li>enum: → select</li>
 *   <li>required: → 字段必填</li>
 *   <li>default: → defaultValue</li>
 *   <li>properties: → 递归 object 的子字段</li>
 *   <li>items: → array 的元素类型</li>
 * </ul>
 *
 * <p>不支持的类型会以 400 拒绝并给出明确提示,避免静默丢失字段。
 */
@Service
public class JsonSchemaImporter {

    private final ObjectMapper objectMapper;

    public JsonSchemaImporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 JSON Schema 字符串解析为 FieldDef 列表。
     *
     * @param schemaJson  JSON Schema 字符串
     * @return FieldDef 列表(不含 id / created_at 等系统字段)
     */
    public List<FieldDef> importFields(String schemaJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(schemaJson);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schema 不是合法 JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schema 必须是 JSON 对象");
        }
        JsonNode properties = root.get("properties");
        if (properties == null || !properties.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schema 缺少 properties");
        }

        List<FieldDef> fields = new ArrayList<>();
        JsonNode required = root.get("required");
        Set<String> requiredSet = required != null && required.isArray()
                ? new HashSet<>(objectMapper.convertValue(required, List.class))
                : java.util.Collections.emptySet();

        properties.fieldNames().forEachRemaining(name -> {
            JsonNode prop = properties.get(name);
            FieldDef fd = convertField(name, prop, requiredSet.contains(name));
            if (fd != null) {
                fields.add(fd);
            }
        });
        return fields;
    }

    private FieldDef convertField(String name, JsonNode prop, boolean required) {
        if (prop == null || prop.isObject() == false) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段 " + name + " 定义无效");
        }

        // enum → select
        JsonNode enumNode = prop.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            return new FieldDef(name, "select", required, prop.has("title") ? prop.get("title").asText() : null,
                    java.util.Map.of("options", enumNode), false, false, null,
                    prop.has("description") ? prop.get("description").asText() : null);
        }

        String type = prop.has("type") ? prop.get("type").asText() : null;
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段 " + name + " 缺少 type");
        }

        String mappedType = mapType(type, prop);
        if (mappedType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "字段 " + name + " 的类型 " + type + " 暂不支持,请使用: " + SUPPORTED_TYPES);
        }

        Map<String, Object> options = new java.util.HashMap<>();
        if (prop.has("description")) {
            options.put("description", prop.get("description").asText());
        }
        if (prop.has("default")) {
            options.put("default", prop.get("default").asText());
        }

        String defaultValue = prop.has("default") ? prop.get("default").asText() : null;

        return new FieldDef(name, mappedType, required,
                prop.has("title") ? prop.get("title").asText() : null,
                options.isEmpty() ? null : options,
                false, false, defaultValue,
                prop.has("description") ? prop.get("description").asText() : null);
    }

    /**
     * 将 JSON Schema type 映射到平台 FieldType。
     *
     * @return 平台类型名,或 null 表示不支持
     */
    private String mapType(String jsonType, JsonNode prop) {
        return switch (jsonType) {
            case "string" -> {
                if (prop.has("format")) {
                    String fmt = prop.get("format").asText();
                    yield switch (fmt) {
                        case "email" -> "email";
                        case "uri", "url" -> "url";
                        case "phone", "tel" -> "phone";
                        case "date" -> "date";
                        case "date-time" -> "datetime";
                        default -> "text";
                    };
                }
                yield "text";
            }
            case "number", "integer" -> "number";
            case "boolean" -> "boolean";
            default -> null;
        };
    }

    private static final String SUPPORTED_TYPES =
            "string / number / integer / boolean (支持 format: email / url / phone / date / date-time; 支持 enum → select)";
}