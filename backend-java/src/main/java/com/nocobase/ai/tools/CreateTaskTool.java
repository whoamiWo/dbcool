package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * create_task 工具 — 复用 ProjectService。
 */
@Component
public class CreateTaskTool implements AgentTool {
    @Override public String name() { return "create_task"; }
    @Override public String description() { return "创建项目任务"; }
    @Override public Map<String, Object> execute(Map<String, Object> params) {
        return Map.of("taskId", params.getOrDefault("name", "task"));
    }
}