package com.nocobase.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ratelimit")
public class GlobalRateLimitProperties {
    private Login login = new Login();
    private Im im = new Im();
    private FileUpload fileUpload = new FileUpload();

    public Login getLogin() { return login; }
    public void setLogin(Login login) { this.login = login; }
    public Im getIm() { return im; }
    public void setIm(Im im) { this.im = im; }
    public FileUpload getFileUpload() { return fileUpload; }
    public void setFileUpload(FileUpload fileUpload) { this.fileUpload = fileUpload; }

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
}