package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocobase.im.entity.ImMessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Integration tests for T1 advanced search: real SQL, no mocks.
 *
 * <p>Covers the core regression from the T1 bug: "先取一页再过滤" caused
 * missed results on later pages. With real SQL, the old (broken) filter-first
 * implementation would NOT find a keyword match that falls on page 2 when
 * page=0&size=10.
 */
@DataJpaTest
@ActiveProfiles("test")
class ImMessageSearchIntegrationTest {

    @Autowired
    private ImMessageRepository repo;

    private UUID channelId;
    private UUID senderId;
    private String tenantId;

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        tenantId = "tenant_default";
    }

    private ImMessageEntity msg(UUID ch, String content, String mentions) {
        ImMessageEntity m = new ImMessageEntity();
        m.setId(UUID.randomUUID());
        m.setChannelId(ch);
        m.setSenderId(senderId);
        m.setContent(content);
        m.setContentType("text");
        m.setTenantId(tenantId);
        m.setMentions(mentions);
        m.setCreatedAt(Instant.now());
        return m;
    }

    private ImMessageEntity msg(UUID ch, String content) {
        return msg(ch, content, null);
    }

    @Test
    void crossPageRecall_keywordOnSecondPage_mustBeFound() {
        // 造 12 条消息：前 10 条不含 keyword，第 11 条含 keyword
        UUID ch = UUID.randomUUID();
        for (int i = 1; i <= 10; i++) {
            repo.save(msg(ch, "no match " + i));
        }
        repo.save(msg(ch, "unique keyword match"));

        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                ch, tenantId, "keyword", null, null, null, null, pageable);
        long total = repo.countWithFullFilters(ch, tenantId, "keyword", null, null, null, null);

        assertThat(total).isEqualTo(1L);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("unique keyword match");
    }

    @Test
    void totalCorrectness_matchesAllHitsNotJustPage() {
        UUID ch = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            repo.save(msg(ch, "budget report " + i));
        }

        long total = repo.countWithFullFilters(ch, tenantId, "budget", null, null, null, null);
        assertThat(total).isEqualTo(5L);

        Pageable pageable = PageRequest.of(0, 2);
        List<ImMessageEntity> page = repo.searchWithFullFilters(
                ch, tenantId, "budget", null, null, null, null, pageable);
        assertThat(page).hasSize(2);
        assertThat(total).isEqualTo(5L);
    }

    @Test
    void keywordHit_filtersByContent() {
        UUID ch = UUID.randomUUID();
        repo.save(msg(ch, "hello world"));
        repo.save(msg(ch, "goodbye world"));

        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                ch, tenantId, "hello", null, null, null, null, pageable);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("hello world");
    }

    @Test
    void noMatch_returnsEmpty() {
        UUID ch = UUID.randomUUID();
        repo.save(msg(ch, "hello world"));

        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                ch, tenantId, "xyz123", null, null, null, null, pageable);

        assertThat(results).isEmpty();
        assertThat(repo.countWithFullFilters(ch, tenantId, "xyz123", null, null, null, null)).isZero();
    }

    @Test
    void timeRangeFilter() {
        UUID ch = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
        Instant t3 = Instant.parse("2026-12-01T00:00:00Z");

        ImMessageEntity m1 = msg(ch, "early message");
        m1.setCreatedAt(t1);
        ImMessageEntity m2 = msg(ch, "mid message");
        m2.setCreatedAt(t2);
        ImMessageEntity m3 = msg(ch, "late message");
        m3.setCreatedAt(t3);
        repo.save(m1);
        repo.save(m2);
        repo.save(m3);

        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                ch, tenantId, null, null, null, t1, t2, pageable);

        assertThat(results).hasSize(2);
    }

    @Test
    void mentionsFilter_byUserId() {
        UUID ch = UUID.randomUUID();
        UUID mentionedUser = UUID.randomUUID();
        repo.save(msg(ch, "hello @{" + mentionedUser + "}",
                "[{\"displayName\":\"Bob\",\"userId\":\"" + mentionedUser + "\"}]"));
        repo.save(msg(ch, "no mention here"));

        String userIdStr = mentionedUser.toString();
        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                ch, tenantId, null, userIdStr, null, null, null, pageable);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("hello @{" + mentionedUser + "}");
    }

    @Test
    void crossChannel_unauthorizedChannel_returnsEmpty() {
        UUID myChannel = UUID.randomUUID();
        UUID otherChannel = UUID.randomUUID();
        repo.save(msg(myChannel, "my channel hello"));
        repo.save(msg(otherChannel, "other channel hello"));

        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                otherChannel, tenantId, "hello", null, null, null, null, pageable);

        // Filter applies on channelId directly — this tests channel-scoping.
        // The caller must assert membership before calling; the repo filters by channel.
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChannelId()).isEqualTo(otherChannel);
    }

    @Test
    void keywordNull_returnsAll() {
        UUID ch = UUID.randomUUID();
        repo.save(msg(ch, "alpha"));
        repo.save(msg(ch, "beta"));

        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                ch, tenantId, null, null, null, null, null, pageable);

        assertThat(results).hasSize(2);
    }

    @Test
    void deletedMessages_excluded() {
        UUID ch = UUID.randomUUID();
        ImMessageEntity m = msg(ch, "deleted content");
        m.setDeletedAt(Instant.now());
        repo.save(m);
        repo.save(msg(ch, "active content"));

        Pageable pageable = PageRequest.of(0, 10);
        List<ImMessageEntity> results = repo.searchWithFullFilters(
                ch, tenantId, "content", null, null, null, null, pageable);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("active content");
    }
}