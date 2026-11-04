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
        for (TenantEntity tenant : tenantRepository.findByStatus(TenantEntity.Status.ACTIVE)) {
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
                    // 分页重建（PAGE 条/批）；当前 listRecords 不支持 offset，
                    // 记录量超过 PAGE 时至少保证前 PAGE 条可搜，并在日志中提示。
                    List<Map<String, Object>> rows = collectionService.listRecords(
                            meta.getName(), tenant.getId(), PAGE);
                    for (Map<String, Object> row : rows) {
                        Object id = row.get("id");
                        if (id == null) continue;
                        listener.reindexRecord(meta.getName(), id.toString(),
                                tenant.getId(), row);
                        records++;
                    }
                    if (rows.size() >= PAGE) {
                        log.warn("[reindex] collection={} tenant={} 记录数≥{}，"
                                        + "剩余记录将在下次增量变更时补建",
                                meta.getName(), tenant.getId(), PAGE);
                    }
                } catch (Exception e) {
                    log.warn("[reindex] collection={} tenant={} 重建失败: {}",
                            meta.getName(), tenant.getId(), e.getMessage());
                }
            }
        }
        log.info("[reindex] 完成 tenants={} collections={} records={}",
                tenantRepository.findByStatus(TenantEntity.Status.ACTIVE).size(),
                collections, records);
    }
}
