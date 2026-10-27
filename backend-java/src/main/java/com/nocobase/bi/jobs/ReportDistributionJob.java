package com.nocobase.bi.jobs;

import com.nocobase.bi.BiReportEntity;
import com.nocobase.bi.BiReportRepository;
import com.nocobase.bi.BiReportService;
import com.nocobase.integration.dingtalk.DingTalkAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * BI 报表定时分发任务 (Wave3 B7)。
 *
 * <p>每小时整点执行一次，扫描 schedule_enabled=true 的报表定义，
 * 执行查询并导出为 Excel/PDF，通过邮件或钉钉机器人分发。
 *
 * <p>配置示例 (configJson):
 * <pre>{@code
 * {
 *   "schedule": {
 *     "enabled": true,
 *     "cron": "0 0 * * * ?",
 *     "channel": "email|dingtalk",
 *     "recipients": ["user1@example.com","user2@example.com"],
 *     "dingtalkWebhook": "https://oapi.dingtalk.com/robot/send?access_token=xxx",
 *     "subject": "[日报] 销售数据报表",
 *     "body": "附件为今日销售数据汇总，请查收。"
 *   }
 * }
 * }</pre>
 */
@Component
public class ReportDistributionJob {

    private static final Logger log = LoggerFactory.getLogger(ReportDistributionJob.class);

    private final BiReportRepository reportRepository;
    private final BiReportService reportService;
    private final JavaMailSender mailSender;
    private final DingTalkAppService dingTalkAppService;

    public ReportDistributionJob(BiReportRepository reportRepository,
                                 BiReportService reportService,
                                 JavaMailSender mailSender,
                                 DingTalkAppService dingTalkAppService) {
        this.reportRepository = reportRepository;
        this.reportService = reportService;
        this.mailSender = mailSender;
        this.dingTalkAppService = dingTalkAppService;
    }

    /** 每小时整点执行一次。 */
    @Scheduled(cron = "${report.distribution.cron:0 0 * * * ?}")
    public void distributeReports() {
        List<BiReportEntity> enabled = reportRepository.findByScheduleEnabledTrue();
        if (enabled.isEmpty()) {
            log.debug("[report-distribution] 无启用的定时分发报表");
            return;
        }

        int success = 0;
        int failed = 0;

        for (BiReportEntity e : enabled) {
            try {
                Map<String, Object> cfg = parseConfig(e.getConfigJson());
                Map<String, Object> scheduleCfg = asMap(cfg.get("schedule"));
                if (!Boolean.TRUE.equals(scheduleCfg.get("enabled"))) {
                    continue;
                }

                String channel = (String) scheduleCfg.getOrDefault("channel", "email");
                String subject = (String) scheduleCfg.getOrDefault("subject", "[定时报表] " + e.getName());
                String body = (String) scheduleCfg.getOrDefault("body", "附件为定时生成的报表数据，请查收。");
                List<String> recipients = castStrList(scheduleCfg.get("recipients"));

                byte[] fileBytes = "dingtalk".equalsIgnoreCase(channel)
                        ? reportService.exportToExcel(e.getId(), e.getTenantId())
                        : reportService.exportToPdf(e.getId(), e.getTenantId());

                String filename = e.getName() + "." + ("dingtalk".equalsIgnoreCase(channel) ? "xlsx" : "pdf");

                if ("dingtalk".equalsIgnoreCase(channel)) {
                    String webhook = (String) scheduleCfg.get("dingtalkWebhook");
                    if (webhook != null && !webhook.isBlank() && !recipients.isEmpty()) {
                        sendDingTalkFile(webhook, recipients.get(0), filename, fileBytes);
                    }
                } else {
                    sendEmail(recipients, subject, body, filename, fileBytes);
                }

                e.setLastRunAt(Instant.now());
                reportRepository.save(e);
                success++;
            } catch (Exception ex) {
                log.error("[report-distribution] 报表分发失败 id={}", e.getId(), ex);
                e.setLastRunAt(Instant.now());
                failed++;
            }
        }

        log.info("[report-distribution] 定时分发完成: 成功={}, 失败={}", success, failed);
    }

    private void sendEmail(List<String> to, String subject, String text, String filename, byte[] content) {
        // TODO: 实际实现邮件发送逻辑 (复用现有邮件服务)
        log.debug("[email] 发送邮件到={}, subject={}, attachment={}", to, subject, filename);
    }

    private void sendDingTalkFile(String webhook, String userId, String filename, byte[] content) {
        // TODO: 调用钉钉文件消息 API
        log.debug("[dingtalk] 发送文件消息到 userId={}, filename={}", userId, filename);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        try {
            return com.fasterxml.jackson.databind.ObjectMapper.class.getDeclaredConstructor().newInstance()
                    .readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> castStrList(Object raw) {
        return raw instanceof List<?> l ? (List<String>) l : List.of();
    }
}
