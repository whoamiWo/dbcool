package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** 钉钉新版 OA API；只允许调用方传入已解析的外部身份与结构化控件值。 */
@Service
public class DingTalkApprovalService {
    private static final Logger log = LoggerFactory.getLogger(DingTalkApprovalService.class);
    private final DingTalkAppService appService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DingTalkApprovalService(DingTalkAppService appService, RestTemplate restTemplate,
                                   ObjectMapper objectMapper) {
        this.appService = appService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 OA 审批实例。corpId/processCode 与外部身份来自模板及用户映射，不能接受本地 UUID。
     * 官方创建响应字段为 instanceId；事件字段才命名为 processInstanceId。
     */
    public String createApproval(String tenantId, String corpId, String processCode,
                                 String originatorUserId, Long deptId,
                                 List<Map<String, Object>> approvers,
                                 List<Map<String, String>> formComponentValues) {
        if (!appService.isTenantConfigured(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本租户未配置钉钉应用凭证");
        }
        require(corpId, "corpId");
        require(processCode, "processCode");
        require(originatorUserId, "originatorUserId");
        if (formComponentValues == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 formComponentValues 映射");
        }
        String token = appService.getAppAccessToken(tenantId);
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法取得本租户钉钉 access token");
        }

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("processCode", processCode);
        body.put("originatorUserId", originatorUserId);
        body.put("formComponentValues", formComponentValues);
        if (approvers != null && !approvers.isEmpty()) {
            body.put("approvers", approvers);
        } else if (deptId != null) {
            body.put("deptId", deptId);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "OA_TEMPLATE 模式必须显式提供 deptId，或配置 approvers");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-acs-dingtalk-access-token", token);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.dingtalk.com/v1.0/workflow/processInstances",
                new HttpEntity<>(body, headers), String.class);
        try {
            JsonNode json = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            String instanceId = json.path("instanceId").asText("");
            if (!response.getStatusCode().is2xxSuccessful() || instanceId.isBlank()) {
                log.warn("[DingTalk] 创建 OA 实例失败 status={} request tenant={}",
                        response.getStatusCode().value(), tenantId);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "钉钉未返回审批实例 ID");
            }
            return instanceId;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "钉钉审批响应无法解析");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 " + name);
        }
    }
}
