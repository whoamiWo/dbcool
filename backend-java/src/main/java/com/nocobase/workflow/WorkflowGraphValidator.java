package com.nocobase.workflow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工作流图静态校验器(Week 42 D4b.2 — R08 死循环防护第 1 道).
 *
 * <p>在保存/更新 workflow 时调用,检测:
 * <ol>
 *   <li><strong>节点最大数</strong>:防止用户堆数百个节点(超出则抛)</li>
 *   <li><strong>有向图环</strong>:用 DFS 三色标记检测,有环则抛(描述具体环)</li>
 *   <li><strong>边端点存在性</strong>:source/target 节点必须存在(防止 dangling edge)</li>
 * </ol>
 *
 * <p>运行时 cycle 检测在 {@link WorkflowEngine#executeGraphFrom} 的 visited Set,
 * 这里是<strong>保存前</strong>静态检测,降低运行时失败率。
 *
 * @see WorkflowEngine
 */
@Component
public class WorkflowGraphValidator {

    /** 单 workflow 最大节点数 — 防止误操作堆几百节点拖慢执行。 */
    public static final int MAX_NODES = 200;

    /** 边端点缺失时的错误消息前缀。 */
    static final String ERR_MISSING_NODE = "边引用了不存在的节点: ";

    /** 检测到环时的错误消息前缀。 */
    static final String ERR_CYCLE = "工作流图存在环,保存拒绝(违反 R08 死循环防护): ";

    /** 节点超限错误消息前缀。 */
    static final String ERR_TOO_MANY_NODES = "节点数超过上限(" + MAX_NODES + "): ";

    /**
     * 校验 nodes + edges,任何错误抛 {@link WorkflowGraphValidationException}.
     *
     * @param nodes 节点列表,每项必须含 {@code id}
     * @param edges 边列表,每项必须含 {@code source} + {@code target}
     * @throws WorkflowGraphValidationException 校验失败
     */
    public void validate(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        if (nodes == null || nodes.isEmpty()) {
            return; // 空 workflow 合法
        }
        if (nodes.size() > MAX_NODES) {
            throw new WorkflowGraphValidationException(ERR_TOO_MANY_NODES + nodes.size());
        }

        // 1. 节点 ID 索引
        Set<String> nodeIds = new HashSet<>();
        for (Map<String, Object> n : nodes) {
            Object id = n == null ? null : n.get("id");
            if (id != null) nodeIds.add(id.toString());
        }

        // 2. 边端点存在性
        java.util.Map<String, java.util.List<String>> adj = new HashMap<>();
        for (Map<String, Object> e : (edges == null ? java.util.Collections.<Map<String, Object>>emptyList() : edges)) {
            String src = e == null ? null : (String) e.get("source");
            String tgt = e == null ? null : (String) e.get("target");
            if (src == null || tgt == null) continue;
            if (!nodeIds.contains(src) || !nodeIds.contains(tgt)) {
                throw new WorkflowGraphValidationException(
                        ERR_MISSING_NODE + src + " → " + tgt);
            }
            adj.computeIfAbsent(src, k -> new java.util.ArrayList<>()).add(tgt);
        }

        // 3. DFS 三色标记检测环
        Set<String> white = new HashSet<>(nodeIds);   // 未访问
        Set<String> gray = new HashSet<>();           // 当前路径
        Set<String> black = new HashSet<>();           // 完成
        Deque<String> pathStack = new ArrayDeque<>();

        for (String start : nodeIds) {
            if (!white.contains(start)) continue;
            if (hasCycleDfs(start, adj, white, gray, black, pathStack)) {
                throw new WorkflowGraphValidationException(
                        ERR_CYCLE + String.join(" → ", pathStack));
            }
        }
    }

    /** DFS 返回是否在以 start 为根的子树中发现环;pathStack 反映触发环的路径。 */
    private static boolean hasCycleDfs(String start,
                                       Map<String, java.util.List<String>> adj,
                                       Set<String> white,
                                       Set<String> gray,
                                       Set<String> black,
                                       Deque<String> pathStack) {
        white.remove(start);
        gray.add(start);
        pathStack.push(start);

        java.util.List<String> neighbors = adj.getOrDefault(start, List.of());
        for (String next : neighbors) {
            if (black.contains(next)) continue;
            if (gray.contains(next)) {
                // 找到环 — 把环路径拍平到 pathStack
                pathStack.push(next);
                return true;
            }
            if (white.contains(next) && hasCycleDfs(next, adj, white, gray, black, pathStack)) {
                return true;
            }
        }

        pathStack.pop();
        gray.remove(start);
        black.add(start);
        return false;
    }
}
