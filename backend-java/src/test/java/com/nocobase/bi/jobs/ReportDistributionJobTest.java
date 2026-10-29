package com.nocobase.bi.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.bi.BiReportEntity;
import com.nocobase.bi.BiReportRepository;
import com.nocobase.bi.BiReportService;
import com.nocobase.integration.dingtalk.DingTalkAppService;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * {@link ReportDistributionJob} 单元测试（PHASE 100 覆盖率补测，B7 核心交付物）。
 *
 * <p>两点关键处理：
 * <ol>
 *   <li>{@code restTemplate} 是类内 {@code new RestTemplate()} 私有字段，无法注入 →
 *       用 {@link ReflectionTestUtils#setField} 替换为 mock。</li>
 *   <li>{@code mailSender.send(MimeMessagePreparator)} 的 preparator 是 lambda，
 *       mock 默认不执行它 → 用 {@code doAnswer} 真实调用 prepare，
 *       才能覆盖到 MimeMessageHelper 构造附件的代码。</li>
 * </ol>
 */
class ReportDistributionJobTest {

    private BiReportRepository reportRepository;
    private BiReportService reportService;
    private JavaMailSender mailSender;
    private RestTemplate restTemplate;
    private ReportDistributionJob job;

    @BeforeEach
    void setUp() {
        reportRepository = mock(BiReportRepository.class);
        reportService = mock(BiReportService.class);
        mailSender = mock(JavaMailSender.class);
        restTemplate = mock(RestTemplate.class);
        job = new ReportDistributionJob(reportRepository, reportService, mailSender,
                mock(DingTalkAppService.class));
        ReflectionTestUtils.setField(job, "restTemplate", restTemplate);
    }

    private BiReportEntity report(String configJson) {
        BiReportEntity e = new BiReportEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId("t1");
        e.setName("sales_daily");
        e.setConfigJson(configJson);
        return e;
    }

    /** schedule 配置 JSON；channel 可为 null（走默认 email）。 */
    private String cfg(boolean enabled, String channel, List<String> recipients, String webhook) {
        StringBuilder sb = new StringBuilder("{\"schedule\":{\"enabled\":" + enabled);
        if (channel != null) sb.append(",\"channel\":\"").append(channel).append('"');
        if (recipients != null) {
            sb.append(",\"recipients\":[");
            for (int i = 0; i < recipients.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(recipients.get(i)).append('"');
            }
            sb.append(']');
        }
        if (webhook != null) sb.append(",\"dingtalkWebhook\":\"").append(webhook).append('"');
        sb.append(",\"subject\":\"日报\",\"body\":\"请查收\"}}");
        return sb.toString();
    }

    /** 让 mailSender 真实执行 preparator，以便覆盖 MimeMessageHelper 逻辑。 */
    private void stubMailSenderExecutesPreparator() {
        doAnswer(inv -> {
            MimeMessagePreparator preparator = inv.getArgument(0);
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
            preparator.prepare(mime);
            return null;
        }).when(mailSender).send(any(MimeMessagePreparator.class));
    }

    // ================= 主流程 =================

    @Test
    @DisplayName("无启用报表 → 直接返回，不调用导出与发送")
    void noEnabledReports_returnsEarly() {
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of());

        job.distributeReports();

        verify(reportService, never()).exportToPdf(any(), anyString());
        verify(mailSender, never()).send(any(MimeMessagePreparator.class));
    }

    @Test
    @DisplayName("schedule.enabled=false → 跳过该报表（continue）")
    void scheduleDisabled_skipped() {
        when(reportRepository.findByScheduleEnabledTrue())
                .thenReturn(List.of(report(cfg(false, "email", List.of("a@b.com"), null))));

        job.distributeReports();

        verify(reportService, never()).exportToPdf(any(), anyString());
        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("configJson 非法 → parseConfig 降级为空 Map，跳过该报表")
    void invalidJson_skipped() {
        when(reportRepository.findByScheduleEnabledTrue())
                .thenReturn(List.of(report("{not valid json")));

        job.distributeReports();

        verify(reportService, never()).exportToPdf(any(), anyString());
    }

    @Test
    @DisplayName("email 渠道（默认）→ 导出 PDF + 真实投递带附件邮件 + 回写 lastRunAt")
    void emailChannel_sendsMailWithAttachment() {
        BiReportEntity e = report(cfg(true, null, List.of("a@b.com", "c@d.com"), null));
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of(e));
        when(reportService.exportToPdf(e.getId(), "t1")).thenReturn("PDF-BYTES".getBytes());
        stubMailSenderExecutesPreparator();

        job.distributeReports();

        verify(reportService).exportToPdf(e.getId(), "t1");
        verify(mailSender).send(any(MimeMessagePreparator.class));
        verify(reportRepository).save(e);          // 成功 → 回写 lastRunAt
        org.junit.jupiter.api.Assertions.assertNotNull(e.getLastRunAt());
    }

    @Test
    @DisplayName("email 渠道但无收件人 → sendEmail 抛 IAE，计入 failed 并回写 lastRunAt")
    void emailChannel_noRecipients_countsFailed() {
        BiReportEntity e = report(cfg(true, "email", List.of(), null));
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of(e));
        when(reportService.exportToPdf(e.getId(), "t1")).thenReturn("PDF".getBytes());

        job.distributeReports();

        verify(mailSender, never()).send(any(MimeMessagePreparator.class));
        // 实测行为：catch 分支只 setLastRunAt（内存），并未 save —— 与成功路径的
        // save 不一致，失败报表的 lastRunAt 不会落库。此处如实锁定现状，
        // 是否修复（失败也落库以便观测重试节奏）待定。
        org.junit.jupiter.api.Assertions.assertNotNull(e.getLastRunAt());
        verify(reportRepository, never()).save(e);
    }

    @Test
    @DisplayName("导出失败 → 异常被捕获，计入 failed，不影响流程")
    void exportFailure_countsFailed() {
        BiReportEntity e = report(cfg(true, "email", List.of("a@b.com"), null));
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of(e));
        when(reportService.exportToPdf(e.getId(), "t1"))
                .thenThrow(new IllegalStateException("export boom"));

        job.distributeReports();

        verify(mailSender, never()).send(any(MimeMessagePreparator.class));
        // 同 noRecipients 用例：catch 分支不落库
        org.junit.jupiter.api.Assertions.assertNotNull(e.getLastRunAt());
        verify(reportRepository, never()).save(e);
    }

    // ================= 钉钉渠道 =================

    @Test
    @DisplayName("dingtalk 渠道 → 导出 Excel + 推送 webhook（errcode=0）")
    void dingtalkChannel_sendsWebhook() {
        BiReportEntity e = report(cfg(true, "dingtalk", List.of("user1"), "https://oapi.dingtalk.com/robot/send?access_token=x"));
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of(e));
        when(reportService.exportToExcel(e.getId(), "t1")).thenReturn("XLSX-BYTES".getBytes());
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("errcode", 0));

        job.distributeReports();

        verify(reportService).exportToExcel(e.getId(), "t1");
        verify(restTemplate).postForObject(anyString(), any(), eq(Map.class));
        verify(reportRepository).save(e);
    }

    @Test
    @DisplayName("dingtalk 渠道但 errcode!=0 → 抛异常，计入 failed")
    void dingtalkChannel_errcodeNonZero_fails() {
        BiReportEntity e = report(cfg(true, "dingtalk", List.of("user1"), "https://hook"));
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of(e));
        when(reportService.exportToExcel(e.getId(), "t1")).thenReturn("X".getBytes());
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("errcode", 130101, "errmsg", "no robot"));

        job.distributeReports();

        // 失败路径同样不落库（catch 分支无 save）
        verify(reportRepository, never()).save(e);
    }

    @Test
    @DisplayName("dingtalk 渠道但 webhook 为空 → 不推送，仍计成功并回写")
    void dingtalkChannel_noWebhook_skipsPush() {
        BiReportEntity e = report(cfg(true, "dingtalk", List.of("user1"), null));
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of(e));
        when(reportService.exportToExcel(e.getId(), "t1")).thenReturn("X".getBytes());

        job.distributeReports();

        verify(restTemplate, never()).postForObject(anyString(), any(), eq(Map.class));
        verify(reportRepository).save(e);
    }

    @Test
    @DisplayName("dingtalk 渠道无收件人 → 不推送（recipients 为空）")
    void dingtalkChannel_noRecipients_skipsPush() {
        BiReportEntity e = report(cfg(true, "dingtalk", List.of(), "https://hook"));
        when(reportRepository.findByScheduleEnabledTrue()).thenReturn(List.of(e));
        when(reportService.exportToExcel(e.getId(), "t1")).thenReturn("X".getBytes());

        job.distributeReports();

        verify(restTemplate, never()).postForObject(anyString(), any(), eq(Map.class));
        verify(reportRepository).save(e);
    }
}
