package com.nocobase.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ratelimit")
public class GlobalRateLimitProperties {
    private Login login = new Login();
    private Im im = new Im();
    private FileUpload fileUpload = new FileUpload();
    /** PHASE99 T2: 第三方回调入站限流（匿名放行端点，必须防刷） */
    private Inbound inbound = new Inbound();

    public Login getLogin() { return login; }
    public void setLogin(Login login) { this.login = login; }
    public Im getIm() { return im; }
    public void setIm(Im im) { this.im = im; }
    public FileUpload getFileUpload() { return fileUpload; }
    public void setFileUpload(FileUpload fileUpload) { this.fileUpload = fileUpload; }
    public Inbound getInbound() { return inbound; }
    public void setInbound(Inbound inbound) { this.inbound = inbound; }

    public static class Login {
        private int limit = 5;
        private int windowSeconds = 300;
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }

    public static class Im {
        private int limit = 30;
        private int windowSeconds = 60;
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }

    public static class FileUpload {
        private int limit = 10;
        private int windowSeconds = 60;
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }

    /**
     * PHASE99 T2: 第三方回调入站限流。
     *
     * <p>覆盖 /api/{slack|dingtalk|feishu|wecom|mattermost}/events 与
     * /api/dingtalk/approval-callback 等**匿名放行**端点 —— 对外暴露后，
     * 任何人拿到回调地址就能打爆服务，此前这些端点无任何限流。
     */
    public static class Inbound {
        /** 每 IP 每 provider 的请求上限 */
        private int limit = 60;
        private int windowSeconds = 60;
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }
}