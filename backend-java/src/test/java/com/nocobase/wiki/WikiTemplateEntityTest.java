package com.nocobase.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PHASE105: WikiTemplateEntity POJO 覆盖（getter/setter + 默认值）。 */
class WikiTemplateEntityTest {

    @Test
    void allFields_gettersAndSetters() {
        WikiTemplateEntity e = new WikiTemplateEntity();
        UUID id = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        Instant now = Instant.now();

        e.setId(id);
        e.setKbId(kbId);
        e.setName("tpl-name");
        e.setTitle("模板标题");
        e.setContentJson("[{\"type\":\"text\"}]");
        e.setIcon("icon-1");
        e.setTenantId("t1");
        e.setCreatedBy(createdBy);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getKbId()).isEqualTo(kbId);
        assertThat(e.getName()).isEqualTo("tpl-name");
        assertThat(e.getTitle()).isEqualTo("模板标题");
        assertThat(e.getContentJson()).isEqualTo("[{\"type\":\"text\"}]");
        assertThat(e.getIcon()).isEqualTo("icon-1");
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getCreatedBy()).isEqualTo(createdBy);
        assertThat(e.getCreatedAt()).isEqualTo(now);
        assertThat(e.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void defaultContentJson_isEmptyArray() {
        WikiTemplateEntity e = new WikiTemplateEntity();
        assertThat(e.getContentJson()).isEqualTo("[]");
    }

    @Test
    void defaultConstructor_leavesOtherFieldsNull() {
        WikiTemplateEntity e = new WikiTemplateEntity();
        assertThat(e.getId()).isNull();
        assertThat(e.getKbId()).isNull();
        assertThat(e.getName()).isNull();
        assertThat(e.getTitle()).isNull();
        assertThat(e.getIcon()).isNull();
        assertThat(e.getTenantId()).isNull();
        assertThat(e.getCreatedBy()).isNull();
        assertThat(e.getCreatedAt()).isNull();
        assertThat(e.getUpdatedAt()).isNull();
    }
}
