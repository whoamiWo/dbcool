package com.nocobase.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DingTalkApprovalNodeHandlerTest {
    private final DingTalkApprovalNodeHandler handler = new DingTalkApprovalNodeHandler();

    @Test
    void externalNodeAlwaysWaitsWithoutHttpOrLocalTask() {
        NodeExecutionContext ctx = new NodeExecutionContext(null, Map.of(), null, null, null);
        assertThat(handler.execute(ctx)).isEqualTo(NodeOutcome.NEEDS_APPROVAL);
        assertThat(handler.type()).isEqualTo("DINGTALK_APPROVAL");
    }
}
