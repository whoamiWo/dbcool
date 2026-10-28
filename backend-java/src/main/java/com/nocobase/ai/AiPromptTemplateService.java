package com.nocobase.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 提示词模板服务 (C4)。
 *
 * <p>提供模板 CRUD + 变量替换功能。
 */
@Service
@Transactional
public class AiPromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(AiPromptTemplateService.class);

    private final AiPromptTemplateRepository templateRepo;

    public AiPromptTemplateService(AiPromptTemplateRepository templateRepo) {
        this.templateRepo = templateRepo;
    }

    /** 创建模板。 */
    public AiPromptTemplateEntity createTemplate(String tenantId, String name, String description,
                                                  String promptTemplate, String variablesJson) {
        var entity = new AiPromptTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setPromptTemplate(promptTemplate);
        entity.setVariablesJson(variablesJson);
        return templateRepo.save(entity);
    }

    /** 更新模板。 */
    public Optional<AiPromptTemplateEntity> updateTemplate(UUID id, String name, String description,
                                                          String promptTemplate, String variablesJson) {
        return templateRepo.findById(id).map(entity -> {
            if (name != null) entity.setName(name);
            if (description != null) entity.setDescription(description);
            if (promptTemplate != null) entity.setPromptTemplate(promptTemplate);
            if (variablesJson != null) entity.setVariablesJson(variablesJson);
            entity.setUpdatedAt(Instant.now());
            return templateRepo.save(entity);
        });
    }

    /** 删除模板。 */
    public boolean deleteTemplate(UUID id) {
        if (!templateRepo.existsById(id)) {
            return false;
        }
        templateRepo.deleteById(id);
        return true;
    }

    /** 查询模板列表。 */
    public List<AiPromptTemplateEntity> listTemplates(String tenantId) {
        return templateRepo.findByTenantId(tenantId);
    }

    /** 搜索模板。 */
    public List<AiPromptTemplateEntity> searchTemplates(String tenantId, String name) {
        return templateRepo.searchByName(tenantId, name);
    }

    /** 获取单个模板。 */
    public Optional<AiPromptTemplateEntity> getTemplate(UUID id, String tenantId) {
        return templateRepo.findById(id).filter(e -> e.getTenantId().equals(tenantId));
    }

    /**
     * 填充模板变量。
     *
     * <p>模板中的变量格式为 {{variableName}}。
     * 例如："请生成一个 {{language}} 语言的 {{feature}} 功能"
     * 填入 {"language":"Java","feature":"REST API"} 得到 "请生成一个 Java 语言的 REST API 功能"
     */
    public String fillVariables(String template, Map<String, String> values) {
        StringBuilder result = new StringBuilder(template);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            int idx = 0;
            while ((idx = result.indexOf(placeholder, idx)) != -1) {
                result.replace(idx, idx + placeholder.length(), value);
                idx += value.length();
            }
        }
        return result.toString();
    }

    /**
     * 从模板中提取占位符变量名列表。
     *
     * @return ["language", "feature"]
     */
    public List<String> extractVariables(String template) {
        Pattern pattern = Pattern.compile("\\{\\{(\\w+)\\}\\}");
        Matcher matcher = pattern.matcher(template);
        Set<String> vars = new LinkedHashSet<>();
        while (matcher.find()) {
            vars.add(matcher.group(1));
        }
        return new ArrayList<>(vars);
    }
}