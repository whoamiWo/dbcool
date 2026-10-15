package com.nocobase.im.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE105: RichTextParser 全静态方法测试。 */
class RichTextParserTest {

    private static final String UID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void parse_nullOrEmpty_returnsEmpty() {
        assertThat(RichTextParser.parse(null).mentions()).isEmpty();
        assertThat(RichTextParser.parse("").links()).isEmpty();
        assertThat(RichTextParser.parse("").codeBlocks()).isEmpty();
    }

    @Test
    void parse_mentionsLinksCode() {
        String content = "Hello @{Alice}:" + UID + " check https://example.com path and `code`:\n```java\nint x=1;\n```";
        RichTextParser.ParseResult r = RichTextParser.parse(content);

        assertThat(r.mentions()).hasSize(1);
        assertThat(r.mentions().get(0).displayName()).isEqualTo("Alice");
        assertThat(r.mentions().get(0).userId()).isEqualTo(UID);
        assertThat(r.links()).contains("https://example.com");
        // 代码块 trim 仅去首尾,保留内部换行
        assertThat(r.codeBlocks()).contains("java\nint x=1;");
    }

    @Test
    void formatMention_buildsTag() {
        String s = RichTextParser.formatMention(UID, "Bob");
        assertThat(s).isEqualTo("@{Bob}:" + UID);
        // 可被 parse 还原
        assertThat(RichTextParser.parse(s).mentions()).hasSize(1);
    }

    @Test
    void hasMentions_detectsPresence() {
        assertThat(RichTextParser.hasMentions("hi @{Zed}:" + UID)).isTrue();
        assertThat(RichTextParser.hasMentions("plain text")).isFalse();
        assertThat(RichTextParser.hasMentions(null)).isFalse();
    }

    @Test
    void extractMentionUserIds_returnsIds() {
        List<String> ids = RichTextParser.extractMentionUserIds("a @{A}:" + UID + " b @{B}:" + UID);
        assertThat(ids).containsExactly(UID, UID);
        assertThat(RichTextParser.extractMentionUserIds(null)).isEmpty();
        assertThat(RichTextParser.extractMentionUserIds("")).isEmpty();
    }
}
