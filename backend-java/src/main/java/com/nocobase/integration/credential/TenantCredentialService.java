package com.nocobase.integration.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 保存/更新凭证（加密落库） */
    @Transactional
    public String save(String tenantId, String provider, String credentialKey, String plaintextSecret,
                       String updatedBy) {
        Optional<TenantIntegrationCredentialEntity> existing =
                repository.findByTenantIdAndProviderAndCredentialKey(tenantId, provider, credentialKey);

        if (existing.isPresent()) {
            var entity = existing.get();
            entity.setAppSecretEnc(cryptoService.encrypt(plaintextSecret));
            entity.setUpdatedAt(java.time.Instant.now());
            entity.setUpdatedBy(updatedBy);
            // 状态保持不变
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

    /** 按租户列出全部 provider（管理界面） */
    public java.util.List<String> listProviders(String tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(TenantIntegrationCredentialEntity::getProvider)
                .distinct()
                .toList();
    }
}
