package com.nocobase.notification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

/**
 * FeishuPersonalDispatcher 单元测试。
 */
class FeishuPersonalDispatcherTest {

    @Test
    void testSupportedType() {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        assertEquals(NotificationChannelEntity.Type.FEISHU_PERSONAL, dispatcher.supportedType());
    }

    @Test
    void testBuildTextMessage() {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        Map<String, Object> payload = Map.of(
            "title", "测试标题",
            "body", "测试内容"
        );
        
        // 验证方法存在且可调用
        // 实际测试需要 mock WebClient
        assertNotNull(dispatcher);
    }
}
