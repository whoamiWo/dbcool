package com.nocobase.integration.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PHASE99 T1: 租户级集成凭证服务。
 *
 * <p>读取优先级：
 * <ol>
 *   <li>本表（tenant_id + provider + credential_key）</li>
 *   <li>回退全局 {@code @Value}（存量部署零破坏）</li>
 *   <li>两者都空 → {@code Optional.empty()}，调用方显式失败（不伪造）</li>
 * </ol>
 *
 * <p>跨租户读由 Repository 方法签名天然保证（不存在先查再比对的越权窗口）。
 */
@Service
@Transactional(readOnly = true)
public class TenantCredentialService {
    private static final Logger log = LoggerFactory.getLogger(TenantCredentialService.class);

    private final TenantIntegrationCredentialRepository repository;
    private final CredentialCryptoService cryptoService;

    public TenantCredentialService(TenantIntegrationCredentialRepository repository,
                                   CredentialCryptoService cryptoService) {
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    /** 批量保存/更新；任一失败整体回滚，不留下部分生效的凭证。 */
    @Transactional
    public void saveAll(String tenantId, String provider, Map<String, String> values, String updatedBy) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("凭证值不能为空");
        }
        values.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("凭证值不能为空: " + key);
            }
        });
        values.forEach((key, value) -> saveInternal(tenantId, provider, key, value, updatedBy));
    }

    /** 保存/更新凭证（加密落库） */
    @Transactional
    public String save(String tenantId, String provider, String credentialKey, String plaintextSecret,
                       String updatedBy) {
        return saveInternal(tenantId, provider, credentialKey, plaintextSecret, updatedBy);
    }

    private String saveInternal(String tenantId, String provider, String credentialKey, String plaintextSecret,
                                String updatedBy) {
        Optional<TenantIntegrationCredentialEntity> existing =
                repository.findByTenantIdAndProviderAndCredentialKey(tenantId, provider, credentialKey);

        if (existing.isPresent()) {
            var entity = existing.get();
            entity.setAppSecretEnc(cryptoService.encrypt(plaintextSecret));
            entity.setUpdatedAt(java.time.Instant.now());
            entity.setUpdatedBy(updatedBy);
            entity.setStatus("ACTIVE");
            repository.save(entity);
            log.info("[tenant-credential] 更新凭证 tenant={} provider={} key={}", tenantId, provider, credentialKey);
            return entity.getId().toString();
        } else {
            var entity = new TenantIntegrationCredentialEntity();
            entity.setId(UUID.randomUUID());
            entity.setTenantId(tenantId);
            entity.setProvider(provider);
            entity.setCredentialKey(credentialKey);
            entity.setAppSecretEnc(cryptoService.encrypt(plaintextSecret));
            entity.setStatus("ACTIVE");
            entity.setUpdatedBy(updatedBy);
            repository.save(entity);
            log.info("[tenant-credential] 新建凭证 tenant={} provider={} key={} id={}", tenantId, provider, credentialKey, entity.getId());
            return entity.getId().toString();
        }
    }

    public enum CredentialState { ACTIVE, MISSING, DISABLED, INVALID }

    /**
     * 供租户凭证消费方区分未配置与已禁用/损坏；后两者不得回退全局配置。
     */
    public record Lookup(CredentialState state, Optional<String> value) {
        static Lookup of(CredentialState state) {
            return new Lookup(state, Optional.empty());
        }
    }

    public Lookup lookup(String tenantId, String provider, String credentialKey) {
        Optional<TenantIntegrationCredentialEntity> entity =
                repository.findByTenantIdAndProviderAndCredentialKey(tenantId, provider, credentialKey);
        if (entity.isEmpty()) {
            return Lookup.of(CredentialState.MISSING);
        }
        var credential = entity.get();
        if (!"ACTIVE".equals(credential.getStatus())) {
            log.warn("[tenant-credential] 凭证不可用 tenant={} provider={} key={} status={}",
                    tenantId, provider, credentialKey, credential.getStatus());
            return Lookup.of(CredentialState.DISABLED);
        }
        try {
            return new Lookup(CredentialState.ACTIVE,
                    Optional.of(cryptoService.decrypt(credential.getAppSecretEnc())));
        } catch (IllegalStateException e) {
            log.error("[tenant-credential] 凭证解密失败 tenant={} provider={} key={} err={}",
                    tenantId, provider, credentialKey, e.getMessage(), e);
            return Lookup.of(CredentialState.INVALID);
        }
    }

    /** 禁用该 provider 的全部租户凭证，保留密文以便管理员显式 upsert 恢复。 */
    @Transactional
    public void disable(String tenantId, String provider, String updatedBy) {
        Instant now = Instant.now();
        repository.findByTenantIdAndProvider(tenantId, provider).forEach(entity -> {
            entity.setStatus("DISABLED");
            entity.setUpdatedAt(now);
            entity.setUpdatedBy(updatedBy);
            repository.save(entity);
        });
        log.info("[tenant-credential] 禁用凭证 tenant={} provider={}", tenantId, provider);
    }

    /** 只返回状态和掩码，不返回明文、密文或 updatedBy。 */
    public Map<String, Object> status(String tenantId, String provider) {
        var rows = repository.findByTenantIdAndProvider(tenantId, provider);
        var masked = new java.util.TreeMap<String, String>();
        Instant updatedAt = null;
        String status = rows.isEmpty() ? "NOT_CONFIGURED" : "ACTIVE";
        for (var row : rows) {
            masked.put(row.getCredentialKey(), "********");
            if (updatedAt == null || (row.getUpdatedAt() != null && row.getUpdatedAt().isAfter(updatedAt))) {
                updatedAt = row.getUpdatedAt();
            }
            if (!"ACTIVE".equals(row.getStatus())) {
                status = "DISABLED";
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("configured", !rows.isEmpty());
        result.put("status", status);
        result.put("masked", Collections.unmodifiableMap(masked));
        result.put("updatedAt", updatedAt);
        return Collections.unmodifiableMap(result);
    }

    /** 获取凭证（解密后）；未配置返回 Optional.empty()，调用方决定是否回退全局 @Value */
    public Optional<String> get(String tenantId, String provider, String credentialKey) {
        Optional<TenantIntegrationCredentialEntity> opt =
                repository.findByTenantIdAndProviderAndCredentialKey(tenantId, provider, credentialKey);
        if (opt.isEmpty()) {
            log.debug("[tenant-credential] 租户级凭证未找到 tenant={} provider={} key={}", tenantId, provider, credentialKey);
            return Optional.empty();
        }
        var entity = opt.get();
        if (!"ACTIVE".equals(entity.getStatus())) {
            log.warn("[tenant-credential] 凭证已禁用 tenant={} provider={} key={} status={}", tenantId, provider, credentialKey, entity.getStatus());
            return Optional.empty();
        }
        try {
            String decrypted = cryptoService.decrypt(entity.getAppSecretEnc());
            log.debug("[tenant-credential] 凭证解密成功 tenant={} provider={} key={}", tenantId, provider, credentialKey);
            return Optional.of(decrypted);
        } catch (IllegalStateException e) {
            log.error("[tenant-credential] 凭证解密失败 tenant={} provider={} key={} err={}", tenantId, provider, credentialKey, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** 删除凭证（轮换/撤销） */
    @Transactional
    public void delete(String tenantId, String provider, String credentialKey, String updatedBy) {
        repository.deleteByTenantIdAndProviderAndCredentialKey(tenantId, provider, credentialKey);
        log.info("[tenant-credential] 删除凭证 tenant={} provider={} key={} updatedBy={}", tenantId, provider, credentialKey, updatedBy);
    }

    /** 回调解密候选只包含 corp-id 明确配置的 ACTIVE 租户；绑定最终确定业务租户。 */
    public java.util.List<TenantIntegrationCredentialEntity> activeDingTalkCallbackCredentials() {
        return repository.findByProvider("dingtalk").stream()
                .filter(row -> "corp-id".equals(row.getCredentialKey()) && "ACTIVE".equals(row.getStatus()))
                .toList();
    }

    public Optional<String> findDingTalkTenantByCorpId(String corpId) {
        return activeDingTalkCallbackCredentials().stream()
                .filter(row -> get(row.getTenantId(), "dingtalk", "corp-id")
                        .map(corpId::equals).orElse(false))
                .map(TenantIntegrationCredentialEntity::getTenantId)
                .findFirst();
    }

    /** 按租户列出全部 provider（管理界面） */
    public java.util.List<String> listProviders(String tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(TenantIntegrationCredentialEntity::getProvider)
                .distinct()
                .toList();
    }
}
