package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.Test;

/** PHASE105: MentionParser 测试。 */
class MentionParserTest {

    private final MentionParser parser = new MentionParser();

    @Test
    void parse_nullOrBlank_returnsEmpty() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse(""))
                .isEmpty();
    }

    @Test
    void parse_userMention() {
        var list = parser.parse("hello @user 123");
        assertThat(list).hasSize(1);
        var m = list.get(0);
        assertThat(m.kind()).isEqualTo("user");
        assertThat(m.raw()).isEqualTo("@user");
        assertThat(m.isUser()).isTrue();
        assertThat(m.isChannel()).isFalse();
        assertThat(m.isHere()).isFalse();
    }

    @Test
    void parse_channelMention() {
        var list = parser.parse("通知 @channel");
        assertThat(list).hasSize(1);
        var m = list.get(0);
        assertThat(m.kind()).isEqualTo("channel");
        assertThat(m.raw()).isEqualTo("@channel");
        assertThat(m.isChannel()).isTrue();
    }

    @Test
    void parse_hereMention() {
        var list = parser.parse("@here 集合");
        assertThat(list).hasSize(1);
        var m = list.get(0);
        assertThat(m.kind()).isEqualTo("here");
        assertThat(m.raw()).isEqualTo("@here");
        assertThat(m.isHere()).isTrue();
    }

    @Test
    void parse_multipleMentions() {
        var list = parser.parse("@user @channel @here");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).kind()).isEqualTo("user");
        assertThat(list.get(1).kind()).isEqualTo("channel");
        assertThat(list.get(2).kind()).isEqualTo("here");
    }

    @Test
    void parse_mixedText() {
        var list = parser.parse("请 @user 完成 @channel 任务");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).kind()).isEqualTo("user");
        assertThat(list.get(1).kind()).isEqualTo("channel");
    }

    @Test
    void parse_noMention_returnsEmpty() {
        assertThat(parser.parse("纯文本无提及")).isEmpty();
    }

    @Test
    void mention_record_accessors() {
        MentionParser.Mention m = new MentionParser.Mention("user", "@user");
        assertThat(m.isUser()).isTrue();
        assertThat(m.isChannel()).isFalse();
        assertThat(m.isHere()).isFalse();

        m = new MentionParser.Mention("channel", "@channel");
        assertThat(m.isUser()).isFalse();
        assertThat(m.isChannel()).isTrue();
        assertThat(m.isHere()).isFalse();

        m = new MentionParser.Mention("here", "@here");
        assertThat(m.isUser()).isFalse();
        assertThat(m.isChannel()).isFalse();
        assertThat(m.isHere()).isTrue();
    }

    @Test
    void mention_record_equalsAndHash() {
        MentionParser.Mention a = new MentionParser.Mention("user", "@user");
        MentionParser.Mention b = new MentionParser.Mention("user", "@user");
        MentionParser.Mention c = new MentionParser.Mention("channel", "@user");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void parse_mentionWithBoundary() {
        var list = parser.parse("@user123 @user_456 @user-789");
        assertThat(list).hasSize(1); // 只匹配 \b 边界，@user123 不是单独的单词
        assertThat(list.get(0).kind()).isEqualTo("user");
        assertThat(list.get(0).raw()).isEqualTo("@user");
    }
}
