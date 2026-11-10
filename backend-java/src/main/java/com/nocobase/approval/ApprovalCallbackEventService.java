package com.nocobase.approval;

import com.nocobase.integration.dingtalk.DingCallbackCrypto;
import com.nocobase.integration.credential.TenantCredentialService;
import com.nocobase.workflow.WorkflowTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** SyncHTTP 验签解密、精确绑定与 eventId 幂等的唯一消费入口。 */
@Service
public class ApprovalCallbackEventService {
    private static final Logger log = LoggerFactory.getLogger(ApprovalCallbackEventService.class);
    private final TenantCredentialService credentialService;
    private final ApprovalExternalBindingRepository bindingRepository;
    private final ApprovalCallbackEventRepository eventRepository;
    private final WorkflowTaskService taskService;
    private final TransactionTemplate transactions;
    private final String workerId = UUID.randomUUID().toString();

    public ApprovalCallbackEventService(TenantCredentialService credentialService,
            ApprovalExternalBindingRepository bindingRepository,
            ApprovalCallbackEventRepository eventRepository, WorkflowTaskService taskService,
            PlatformTransactionManager transactionManager) {
        this.credentialService = credentialService;
        this.bindingRepository = bindingRepository;
        this.eventRepository = eventRepository;
        this.taskService = taskService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> receive(String msgSignature, String timeStamp, String nonce,
                                        String encryptedBody) {
        CallbackEvent event = null;
        for (var credential : credentialService.activeDingTalkCallbackCredentials()) {
            String tenantHint = credential.getTenantId();
            String token = credentialService.get(tenantHint, "dingtalk", "token").orElse(null);
            String aesKey = credentialService.get(tenantHint, "dingtalk", "encoding-aes-key").orElse(null);
            String corpId = credentialService.get(tenantHint, "dingtalk", "corp-id").orElse(null);
            if (token == null || aesKey == null || corpId == null) continue;
            try {
                String plaintext = new DingCallbackCrypto(token, aesKey, corpId)
                        .decrypt(msgSignature, timeStamp, nonce, encryptedBody);
                event = new CallbackEvent(corpId, token, aesKey, plaintext);
                break;
            } catch (DingCallbackCrypto.DingCallbackCryptoException | IllegalArgumentException ignored) {
                // 解密只用于定位持久绑定对应的租户，不接受 body 指定租户。
            }
        }
        if (event == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回调验签或解密失败");
        final CallbackEvent verifiedEvent = event;
        if ("check_url".equals(verifiedEvent.eventType())) return success(verifiedEvent);
        if (!"bpms_instance_change".equals(event.eventType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的审批事件类型");
        }
        if (event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 128
                || event.processInstanceId() == null || event.processInstanceId().length() > 128
                || event.processCode() == null || event.processCode().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批事件缺少 eventId/processCode/processInstanceId");
        }
        if (!"finish".equals(event.instanceAction())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅 finish 审批事件可推进");
        }
        if (!"agree".equals(event.result()) && !"refuse".equals(event.result())
                && !"reject".equals(event.result())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非终态审批事件不推进");
        }
        var binding = bindingRepository.findByCorpIdAndProcessCodeAndProcessInstanceId(
                event.corpId(), event.processCode(), event.processInstanceId())
                .filter(row -> verifiedEvent.corpId().equals(row.getCorpId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "外部审批绑定不存在"));
        EventState state = prepareEvent(event, binding);
        if (state == EventState.DUPLICATE) return success(event);
        if (state == EventState.BUSY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "相同事件正在处理，请稍后重试");
        }
        try {
            // 业务与事件完成状态必须同库同事务，任何异常一起回滚；不发送成功 ACK。
            transactions.executeWithoutResult(status -> {
                ApprovalExternalBindingEntity bound = bindingRepository.findLockedById(binding.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "外部审批绑定不存在"));
                taskService.approveByExternalBinding(bound.getTenantId(), bound.getRequestId(),
                        "agree".equals(verifiedEvent.result()), "钉钉 OA 审批回调");
                if (eventRepository.finish(verifiedEvent.corpId(), verifiedEvent.eventId(), workerId,
                        "PROCESSED", null, Instant.now()) != 1) {
                    throw new IllegalStateException("事件租约已变化，回滚本次推进");
                }
            });
            return success(event);
        } catch (Exception e) {
            final String savedError = "BUSINESS_CONFLICT: 审批事件推进失败，可安全重试";
            transactions.executeWithoutResult(status ->
                    eventRepository.finish(verifiedEvent.corpId(), verifiedEvent.eventId(), workerId, "FAILED", savedError, Instant.now()));
            log.warn("[approval-callback] 事件处理失败 eventId={} category={}",
                    event.eventId(), e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批事件推进失败，可安全重试");
        }
    }

    private EventState prepareEvent(CallbackEvent event, ApprovalExternalBindingEntity binding) {
        return transactions.execute(status -> {
            bindingRepository.findLockedById(binding.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "外部审批绑定不存在"));
            var existing = eventRepository.findById(new ApprovalCallbackEventId(event.corpId(), event.eventId()));
            if (existing.isPresent()) {
                var old = existing.get();
                if (!old.getBindingId().equals(binding.getId()) || !old.getResult().equals(event.result())
                        || !old.getTenantId().equals(binding.getTenantId())
                        || !old.getProcessInstanceId().equals(event.processInstanceId())
                        || !old.getEventType().equals(event.instanceAction())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "eventId 与既有事件内容冲突");
                }
                if ("PROCESSED".equals(old.getProcessingStatus())) return EventState.DUPLICATE;
            } else {
                ApprovalCallbackEventEntity entity = new ApprovalCallbackEventEntity();
                entity.setCorpId(event.corpId());
                entity.setEventId(event.eventId());
                entity.setBindingId(binding.getId());
                entity.setTenantId(binding.getTenantId());
                entity.setProcessInstanceId(event.processInstanceId());
                entity.setResult(event.result());
                entity.setReceivedAt(Instant.now());
                entity.setEventType(event.instanceAction());
                eventRepository.saveAndFlush(entity);
            }
            if (eventRepository.existsByBindingIdAndResultNotAndProcessingStatusIn(
                    binding.getId(), event.result(), List.of("RECEIVED", "PROCESSING", "PROCESSED"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同一实例存在不同结果事件，拒绝重放");
            }
            int claimed = eventRepository.claim(event.corpId(), event.eventId(), Instant.now().minusSeconds(60),
                    Instant.now(), workerId);
            return claimed == 1 ? EventState.CLAIMED : EventState.BUSY;
        });
    }

    private Map<String, Object> success(CallbackEvent event) {
        return new java.util.LinkedHashMap<>(
                new DingCallbackCrypto(event.token(), event.aesKey(), event.corpId()).encryptSuccess());
    }

    private enum EventState { CLAIMED, DUPLICATE, BUSY }

    private static final class CallbackEvent {
        private final String token;
        private final String aesKey;
        private final String corpId;
        private final String eventType;
        private final String eventId;
        private final String processCode;
        private final String processInstanceId;
        private final String instanceAction;
        private final String result;

        CallbackEvent(String corpId, String token, String aesKey, String plaintext) {
            this.token = token;
            this.aesKey = aesKey;
            try {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(plaintext);
                this.corpId = json.path("CorpId").asText(corpId);
                if (!corpId.equals(this.corpId)) throw new IllegalArgumentException("corpId 不一致");
                this.eventType = json.path("EventType").asText(null);
                this.eventId = json.path("eventId").asText(null);
                this.processCode = json.path("processCode").asText(null);
                this.processInstanceId = json.path("processInstanceId").asText(null);
                this.instanceAction = json.path("type").asText(null);
                this.result = json.path("result").asText(null);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回调明文事件无效");
            }
        }
        String token() { return token; }
        String aesKey() { return aesKey; }
        String corpId() { return corpId; }
        String eventType() { return eventType; }
        String eventId() { return eventId; }
        String processCode() { return processCode; }
        String processInstanceId() { return processInstanceId; }
        String instanceAction() { return instanceAction; }
        String result() { return result; }
    }
}
