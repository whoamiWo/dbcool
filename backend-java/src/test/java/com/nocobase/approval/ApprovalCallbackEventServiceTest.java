package com.nocobase.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.integration.credential.TenantCredentialService;
import com.nocobase.integration.credential.TenantIntegrationCredentialEntity;
import com.nocobase.integration.dingtalk.DingCallbackCrypto;
import com.nocobase.workflow.WorkflowTaskService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class ApprovalCallbackEventServiceTest {
    private static final String TOKEN = "callback-token";
    private static final String AES_KEY = "1234567890123456789012345678901234567890123";
    private static final String CORP = "corp-a";
    private final TenantCredentialService credentials = mock(TenantCredentialService.class);
    private final ApprovalExternalBindingRepository bindings = mock(ApprovalExternalBindingRepository.class);
    private final ApprovalCallbackEventRepository events = mock(ApprovalCallbackEventRepository.class);
    private final WorkflowTaskService tasks = mock(WorkflowTaskService.class);
    private final ApprovalCallbackEventService service;
    private final ApprovalExternalBindingEntity binding = new ApprovalExternalBindingEntity();

    ApprovalCallbackEventServiceTest() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        service = new ApprovalCallbackEventService(credentials, bindings, events, tasks, tx);
    }

    @BeforeEach
    void setUp() {
        binding.setId(UUID.randomUUID());
        binding.setTenantId("tenant_a");
        binding.setRequestId(UUID.randomUUID());
        binding.setCorpId(CORP);
        binding.setProcessCode("PROC-1");
        binding.setProcessInstanceId("oa-instance-1");
        var row = new TenantIntegrationCredentialEntity();
        row.setTenantId("tenant_a");
        row.setCredentialKey("corp-id");
        row.setStatus("ACTIVE");
        when(credentials.activeDingTalkCallbackCredentials()).thenReturn(List.of(row));
        when(credentials.get("tenant_a", "dingtalk", "token")).thenReturn(Optional.of(TOKEN));
        when(credentials.get("tenant_a", "dingtalk", "encoding-aes-key")).thenReturn(Optional.of(AES_KEY));
        when(credentials.get("tenant_a", "dingtalk", "corp-id")).thenReturn(Optional.of(CORP));
        when(credentials.findDingTalkTenantByCorpId(CORP)).thenReturn(Optional.of("tenant_a"));
        when(bindings.findByCorpIdAndProcessCodeAndProcessInstanceId(CORP, "PROC-1", "oa-instance-1"))
                .thenReturn(Optional.of(binding));
        when(bindings.findById(binding.getId())).thenReturn(Optional.of(binding));
        when(bindings.findLockedById(binding.getId())).thenReturn(Optional.of(binding));
    }

    @Test
    void checkUrl_returnsEncryptedSuccessWithoutBusinessAdvance() {
        var ack = service.receive(signature(encrypt("{\"EventType\":\"check_url\"}")), "1700000000000", "nonce",
                encrypt("{\"EventType\":\"check_url\"}"));
        var crypto = new DingCallbackCrypto(TOKEN, AES_KEY, CORP);
        assertThat(crypto.decrypt(ack.get("msg_signature").toString(), ack.get("timeStamp").toString(),
                ack.get("nonce").toString(), ack.get("encrypt").toString())).isEqualTo("success");
        verify(tasks, never()).approveByExternalBinding(anyString(), any(), any(Boolean.class), anyString());
    }

    @Test
    void processedReplayAcksButAdvancesOnlyOnce() {
        var existing = event("PROCESSED", "agree");
        when(events.findById(new ApprovalCallbackEventId(CORP, "event-1"))).thenReturn(Optional.of(existing));
        var body = encryptedEvent("agree");
        service.receive(signature(body), "1700000000000", "nonce", body);
        service.receive(signature(body), "1700000000000", "nonce", body);
        verify(tasks, never()).approveByExternalBinding(anyString(), any(), any(Boolean.class), anyString());
        verify(events, never()).claim(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void sharedCorpCredentialsUseBindingTenantNotDecryptingTenant() {
        var other = new TenantIntegrationCredentialEntity();
        other.setTenantId("tenant_other");
        other.setCredentialKey("corp-id");
        other.setStatus("ACTIVE");
        when(credentials.activeDingTalkCallbackCredentials()).thenReturn(List.of(other, credentialsRow()));
        when(credentials.get("tenant_other", "dingtalk", "token")).thenReturn(Optional.of(TOKEN));
        when(credentials.get("tenant_other", "dingtalk", "encoding-aes-key")).thenReturn(Optional.of(AES_KEY));
        when(credentials.get("tenant_other", "dingtalk", "corp-id")).thenReturn(Optional.of(CORP));
        when(events.claim(anyString(), anyString(), any(), any(), anyString())).thenReturn(1);
        when(events.finish(anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
        var body = encryptedEvent("agree");
        service.receive(signature(body), "1700000000000", "nonce", body);
        verify(tasks).approveByExternalBinding(org.mockito.ArgumentMatchers.eq("tenant_a"),
                org.mockito.ArgumentMatchers.eq(binding.getRequestId()),
                org.mockito.ArgumentMatchers.eq(true), anyString());
    }

    private TenantIntegrationCredentialEntity credentialsRow() {
        var row = new TenantIntegrationCredentialEntity();
        row.setTenantId("tenant_a");
        row.setCredentialKey("corp-id");
        row.setStatus("ACTIVE");
        return row;
    }

    @Test
    void businessFailurePersistsFailedAndDoesNotAck() {
        when(events.findById(new ApprovalCallbackEventId(CORP, "event-1"))).thenReturn(Optional.empty());
        when(events.claim(anyString(), anyString(), any(), any(), anyString())).thenReturn(1);
        when(tasks.approveByExternalBinding(anyString(), any(), any(Boolean.class), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "already terminal"));
        assertThatThrownBy(() -> service.receive(signature(encryptedEvent("agree")),
                "1700000000000", "nonce", encryptedEvent("agree")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(events).finish(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("FAILED"),
                anyString(), any());
    }

    @Test
    void startEventCannotFinishLocalApproval() {
        var body = encrypt("{\"EventType\":\"bpms_instance_change\",\"CorpId\":\"corp-a\","
                + "\"eventId\":\"event-start\",\"processCode\":\"PROC-1\","
                + "\"processInstanceId\":\"oa-instance-1\",\"type\":\"start\",\"result\":\"agree\"}");
        assertThatThrownBy(() -> service.receive(signature(body), "1700000000000", "nonce", body))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("finish");
        verify(tasks, never()).approveByExternalBinding(anyString(), any(), any(Boolean.class), anyString());
    }

    @Test
    void businessAndProcessedStateUseOneTransactionAndCheckAffectedRows() {
        when(events.findById(new ApprovalCallbackEventId(CORP, "event-1"))).thenReturn(Optional.empty());
        when(events.claim(anyString(), anyString(), any(), any(), anyString())).thenReturn(1);
        when(events.finish(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("PROCESSED"),
                any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.receive(signature(encryptedEvent("agree")),
                "1700000000000", "nonce", encryptedEvent("agree")))
                .isInstanceOf(ResponseStatusException.class);
        verify(events).finish(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("FAILED"),
                anyString(), any());
    }

    @Test
    void wrongSignatureNeverReachesBusinessOrWritesEvent() {
        assertThatThrownBy(() -> service.receive("bad", "1700000000000", "nonce", encryptedEvent("agree")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(events, never()).saveAndFlush(any());
        verify(tasks, never()).approveByExternalBinding(anyString(), any(), any(Boolean.class), anyString());
    }

    @Test
    void conflictingEventResultIsRejected() {
        when(events.findById(new ApprovalCallbackEventId(CORP, "event-1"))).thenReturn(Optional.of(event("PROCESSED", "refuse")));
        assertThatThrownBy(() -> service.receive(signature(encryptedEvent("agree")),
                "1700000000000", "nonce", encryptedEvent("agree")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("冲突");
        verify(tasks, never()).approveByExternalBinding(anyString(), any(), any(Boolean.class), anyString());
    }

    private ApprovalCallbackEventEntity event(String status, String result) {
        var event = new ApprovalCallbackEventEntity();
        event.setCorpId(CORP);
        event.setEventId("event-1");
        event.setBindingId(binding.getId());
        event.setTenantId(binding.getTenantId());
        event.setProcessInstanceId(binding.getProcessInstanceId());
        event.setResult(result);
        event.setProcessingStatus(status);
        return event;
    }

    private String encryptedEvent(String result) {
        return encrypt("{\"EventType\":\"bpms_instance_change\",\"CorpId\":\"corp-a\","
                + "\"eventId\":\"event-1\",\"processCode\":\"PROC-1\","
                + "\"processInstanceId\":\"oa-instance-1\",\"type\":\"finish\",\"result\":\"" + result + "\"}");
    }

    private String signature(String encrypt) {
        return DingCallbackCrypto.computeSignature(TOKEN, "1700000000000", "nonce", encrypt);
    }

    private String encrypt(String plaintext) {
        return TestCrypto.encrypt(plaintext);
    }

    private static final class TestCrypto {
        static String encrypt(String plaintext) {
            try {
                byte[] msg = plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] corp = CORP.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] raw = new byte[20 + msg.length + corp.length];
                java.nio.ByteBuffer.wrap(raw).putInt(16, msg.length);
                System.arraycopy(msg, 0, raw, 20, msg.length);
                System.arraycopy(corp, 0, raw, 20 + msg.length, corp.length);
                int padding = 32 - raw.length % 32;
                byte[] padded = java.util.Arrays.copyOf(raw, raw.length + padding);
                java.util.Arrays.fill(padded, raw.length, padded.length, (byte) padding);
                byte[] key = java.util.Base64.getDecoder().decode(AES_KEY + "=");
                var cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                        new javax.crypto.spec.IvParameterSpec(key, 0, 16));
                return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(padded));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
