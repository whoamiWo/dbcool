package com.nocobase.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentTool 注册 SPI。
 *
 * <p>所有工具实现 AgentTool 接口，通过 @Component 自动注册。
 * AgentService 通过此注册表获取可用工具。
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools = new HashMap<>();

    public AgentToolRegistry(List<AgentTool> toolList) {
        for (AgentTool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<AgentTool> all() {
        return new ArrayList<>(tools.values());
    }

    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }
}