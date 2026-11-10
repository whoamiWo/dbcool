package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 校验管理员显式配置的 OA 模板映射；绝不把任意 formData 直接当控件值外发。 */
@Component
public class DingTalkExternalConfig {
    private final ObjectMapper mapper;

    public DingTalkExternalConfig(ObjectMapper mapper) { this.mapper = mapper; }

    public Resolved resolve(String processCode, String externalConfig,
                            Map<String, Object> formData, String originatorUserId) {
        Map<String, Object> config = parse(externalConfig);
        String corpId = requiredText(config.get("corpId"), "external_config.corpId");
        String originator = text(config.get("originatorUserId"));
        if (originator == null) originator = originatorUserId;
        if (originator == null || originator.isBlank()) {
            throw bad("申请人未映射钉钉 userid（external_source=DINGTALK/external_id）");
        }
        String mode = text(config.get("approversMode"));
        List<Map<String, Object>> approvers = listOfMaps(config.get("approvers"));
        Long deptId = number(config.get("deptId"));
        if (!"OA_TEMPLATE".equals(mode) && approvers.isEmpty()) {
            throw bad("external_config 必须声明 approversMode=OA_TEMPLATE 或 approvers");
        }
        if ("OA_TEMPLATE".equals(mode) && deptId == null) {
            throw bad("OA_TEMPLATE 模式必须显式提供 deptId");
        }
        if (mode != null && !"OA_TEMPLATE".equals(mode) && !"EXPLICIT".equals(mode)) {
            throw bad("不支持的 approversMode");
        }

        List<Map<String, Object>> mappings = listOfMaps(config.get("formComponentValues"));
        if (mappings.isEmpty()) throw bad("external_config.formComponentValues 映射为空");
        List<Map<String, String>> values = new ArrayList<>();
        for (Map<String, Object> mapping : mappings) {
            String name = requiredText(mapping.get("name"), "控件 name");
            String field = requiredText(mapping.get("field"), "控件 field");
            if (!formData.containsKey(field) || formData.get(field) == null) {
                throw bad("必填控件映射字段缺失：" + field);
            }
            Object value = formData.get(field);
            if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                throw bad("控件值必须是标量：" + field);
            }
            String stringValue = String.valueOf(value);
            if (Boolean.TRUE.equals(mapping.get("required")) && stringValue.isBlank()) {
                throw bad("必填控件值为空：" + field);
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("value", stringValue);
            values.add(item);
        }
        return new Resolved(corpId, processCode, originator, deptId, approvers, values);
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) throw bad("缺少 external_config 映射");
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw bad("external_config 不是有效 JSON");
        }
    }

    private static List<Map<String, Object>> listOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw bad("external_config 列表项必须是对象");
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            result.add(out);
        }
        return result;
    }

    private static Long number(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s && !s.isBlank()) {
            try { return Long.valueOf(s); } catch (NumberFormatException e) { throw bad("deptId 必须是数字"); }
        }
        return null;
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String requiredText(Object raw, String name) {
        String value = text(raw);
        if (value == null || value.isBlank()) throw bad("缺少 " + name);
        return value;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Resolved(String corpId, String processCode, String originatorUserId,
                           Long deptId, List<Map<String, Object>> approvers,
                           List<Map<String, String>> formComponentValues) {}
}
