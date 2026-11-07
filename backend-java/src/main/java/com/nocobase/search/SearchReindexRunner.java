package com.nocobase.search;

import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 存量索引重建（T5.1）。
 *
 * <p>A-6 修复前写入的记录索引内容为空 —— 只修写入器不会自动修复已有行，
 * 上线前必须全量重建一次，否则旧记录仍然搜不到。
 *
 * <p>用法：<code>java -jar app.jar --reindex-search</code>
 * （或 <code>--reindex-search=true</code>）。默认不执行，避免每次启动全表扫。
 *
 * <p>范围：record 类型（本次受 A-6 影响的类型）。wiki/im/project/automation
 * 的写入器未变，历史索引仍然有效，无需重建。
 */
@Component
public class SearchReindexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchReindexRunner.class);
    private static final int PAGE = 500;

    private final TenantRepository tenantRepository;
    private final CollectionService collectionService;
    private final SearchIndexSyncListener listener;

    public SearchReindexRunner(TenantRepository tenantRepository,
                               CollectionService collectionService,
                               SearchIndexSyncListener listener) {
        this.tenantRepository = tenantRepository;
        this.collectionService = collectionService;
        this.listener = listener;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("reindex-search")) {
            return;
        }
        int collections = 0;
        int records = 0;
        var tenants = tenantRepository.findByStatus(TenantEntity.Status.ACTIVE);
        for (TenantEntity tenant : tenants) {
            // 动态表按租户 schema 定位，重建期间必须显式切换上下文
            try {
                com.nocobase.tenant.TenantContext.set(tenant.getId());
            } catch (Exception e) {
                log.warn("[reindex] 设置租户上下文失败 tenant={}: {}", tenant.getId(), e.getMessage());
                continue;
            }
            try {
                List<CollectionMetaEntity> metas;
                try {
                    metas = collectionService.list(tenant.getId());
                } catch (Exception e) {
                    log.warn("[reindex] 读取租户 {} 集合失败: {}", tenant.getId(), e.getMessage());
                    continue;
                }
                for (CollectionMetaEntity meta : metas) {
                    collections++;
                    try {
                        // R3：keyset 分页循环直到空页（不再只重建前 500 条）
                        String afterId = null;
                        int scanned = 0;
                        int reindexed = 0;
                        while (true) {
                            List<Map<String, Object>> rows = collectionService.listRecordsAfter(
                                    meta.getName(), tenant.getId(), afterId, PAGE);
                            if (rows.isEmpty()) break;
                            String previousAfterId = afterId;
                            String lastValidId = null;
                            for (Map<String, Object> row : rows) {
                                Object id = row.get("id");
                                if (id == null) continue;
                                listener.reindexRecord(meta.getName(), id.toString(),
                                        tenant.getId(), row);
                                lastValidId = id.toString();
                                reindexed++;
                            }
                            scanned += rows.size();
                            records += rows.size();
                            // 本页没有任何有效 id（脏数据/投影异常）→ 游标无法前进，必须终止，
                            // 否则 while(true) 会反复读取同一页。
                            if (lastValidId == null) {
                                log.warn("[reindex] collection={} tenant={} 本页无有效 id，终止遍历",
                                        meta.getName(), tenant.getId());
                                break;
                            }
                            if (lastValidId.equals(previousAfterId)) {
                                log.warn("[reindex] collection={} tenant={} 游标未前进（{}），终止遍历",
                                        meta.getName(), tenant.getId(), lastValidId);
                                break;
                            }
                            afterId = lastValidId;
                            if (rows.size() < PAGE) break;
                        }
                        log.info("[reindex] collection={} tenant={} scanned={} reindexed={}",
                                meta.getName(), tenant.getId(), scanned, reindexed);
                    } catch (Exception e) {
                        log.warn("[reindex] collection={} tenant={} 重建失败: {}",
                                meta.getName(), tenant.getId(), e.getMessage());
                    }
                }
            } finally {
                com.nocobase.tenant.TenantContext.clear();
            }
        }
        log.info("[reindex] 完成 tenants={} collections={} records={}",
                tenants.size(), collections, records);
    }
}
