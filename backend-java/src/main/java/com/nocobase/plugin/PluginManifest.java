package com.nocobase.plugin;

/**
 * R15: 插件 Manifest 模型.
 * 用于验证插件元数据的完整性与权限声明.
 */
public record PluginManifest(
        String name,
        String version,
        String description,
        java.util.List<String> permissions,
        boolean requiresAuth
) {
    public PluginManifest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 必填");
        }
        if (permissions == null) {
            permissions = java.util.List.of();
        }
    }

    /**
     * 从 YAML 字符串解析 Manifest(支持嵌套列表)。
     * 不依赖 SnakeYAML/YAML 库,以避免新增依赖。
     */
    public static PluginManifest fromYaml(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new PluginValidationException("YAML 内容为空");
        }
        try {
            Parsed parsed = new Parsed();
            int[] indentOfLastList = { -1 };
            String currentKey = null;
            for (String raw : yamlContent.split("\\R")) {
                if (raw.isBlank() || raw.trim().startsWith("#")) continue;
                int indent = leadingSpaces(raw);
                String trimmed = raw.trim();
                if (trimmed.startsWith("- ")) {
                    // 列表项
                    String value = trimmed.substring(2).trim();
                    parsed.listItems.add(value);
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon < 0) continue;
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                if (value.isEmpty()) {
                    // 接下来是列表
                    currentKey = key;
                    indentOfLastList[0] = indent;
                } else {
                    parsed.scalars.put(key, value);
                    currentKey = null;
                }
            }
            // 合并列表到 scalars['permissions']
            if (!parsed.listItems.isEmpty() && currentKey != null) {
                parsed.scalars.put(currentKey, String.join(",", parsed.listItems));
            } else if (!parsed.listItems.isEmpty()) {
                parsed.scalars.put("permissions", String.join(",", parsed.listItems));
            }

            String name = parsed.scalars.getOrDefault("name", "");
            String version = parsed.scalars.getOrDefault("version", "");
            String description = parsed.scalars.getOrDefault("description", "");
            String perms = parsed.scalars.getOrDefault("permissions", "");
            boolean requiresAuth = "true".equalsIgnoreCase(
                    parsed.scalars.getOrDefault("requiresAuth", "false").trim());

            java.util.List<String> permissionsList = perms.isBlank()
                    ? java.util.List.of()
                    : java.util.Arrays.stream(perms.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();

            return new PluginManifest(name, version, description, permissionsList, requiresAuth);
        } catch (PluginValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new PluginValidationException("Manifest 解析失败: " + e.getMessage(), e);
        }
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static final class Parsed {
        final java.util.Map<String, String> scalars = new java.util.HashMap<>();
        final java.util.List<String> listItems = new java.util.ArrayList<>();
    }
}
