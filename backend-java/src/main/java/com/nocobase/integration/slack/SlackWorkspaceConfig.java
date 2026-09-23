package com.nocobase.integration.slack;

/**
 * Slack 工作区配置（OAuth2 安装后保存）。
 */
public record SlackWorkspaceConfig(
    String teamId,
    String teamName,
    String botUserId,
    String botToken,
    String appLevelToken,
    String installationTime
) {}
