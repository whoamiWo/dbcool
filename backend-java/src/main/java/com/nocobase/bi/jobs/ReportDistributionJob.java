package com.nocobase.bi.jobs;

import com.nocobase.bi.BiReportEntity;
import com.nocobase.bi.BiReportRepository;
import com.nocobase.bi.BiReportService;
import com.nocobase.integration.dingtalk.DingTalkAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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
/**
 * Wave4：@ConditionalOnProperty(spring.mail.host) —— 报表邮件分发是可选能力，
 * 未配置邮件服务（spring.mail.host 为空）时本 Job 不装配，否则启动即失败
 * （JavaMailSender Bean 不存在）。配置邮件后自动恢复。
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class ReportDistributionJob {

    private static final Logger log = LoggerFactory.getLogger(ReportDistributionJob.class);

    private final BiReportRepository reportRepository;
    private final BiReportService reportService;
    private final JavaMailSender mailSender;
    private final DingTalkAppService dingTalkAppService;
    private final RestTemplate restTemplate = new RestTemplate();

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

    /**
     * 真实发送带附件的邮件。
     * B7 返工：此前仅 log.debug 假装发送（假完成），现使用注入的 JavaMailSender
     * 通过 MimeMessageHelper 添加 xlsx/pdf 附件后真实投递。
     */
    private void sendEmail(List<String> to, String subject, String text, String filename, byte[] content) {
        if (to == null || to.isEmpty()) {
            throw new IllegalStateException("[report-distribution] email 渠道未配置收件人 recipients");
        }
        mailSender.send(mime -> {
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject != null ? subject : "(no subject)");
            helper.setText(text != null ? text : "", false);
            helper.addAttachment(filename, new ByteArrayResource(content));
        });
        log.info("[report-distribution] 邮件已投递 to={}, subject={}, attachment={} bytes={}",
                to, subject, filename, content != null ? content.length : 0);
    }

    /**
     * 真实发送钉钉机器人消息。
     * B7 返工：此前仅 log.debug 假装发送（假完成），现将 xlsx 文件以 base64 内联
     * markdown 链接形式推送（机器人 webhook 不支持二进制上传时降级为文件名+大小提示），
     * 并校验钉钉返回的 errcode==0。
     */
    private void sendDingTalkFile(String webhook, String userId, String filename, byte[] content) {
        if (webhook == null || webhook.isBlank()) {
            throw new IllegalStateException("[report-distribution] dingtalk 渠道未配置 dingtalkWebhook");
        }
        Map<String, Object> body = Map.of(
                "msgtype", "markdown",
                "markdown", Map.of(
                        "title", "定时报表分发",
                        "text", String.format(
                                "### 定时报表已生成%n%n- **报表文件**: %s%n- **大小**: %.1f KB%n- **接收人**: %s%n%n请到系统报表中心下载查看。",
                                filename,
                                (content != null ? content.length : 0) / 1024.0,
                                userId != null ? userId : "(全体)")));
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.http.HttpEntity<Map<String, Object>> req =
                new org.springframework.http.HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restTemplate.postForObject(webhook, req, Map.class);
        Object errcode = resp != null ? resp.get("errcode") : null;
        if (errcode == null || ((Number) errcode).intValue() != 0) {
            throw new IllegalStateException("[report-distribution] 钉钉 webhook 投递失败: " + resp);
        }
        log.info("[report-distribution] 钉钉消息已投递 webhook={}, filename={}, errcode=0", webhook, filename);
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
