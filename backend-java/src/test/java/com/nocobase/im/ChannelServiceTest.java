package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.im.entity.ImChannelEntity;
import com.nocobase.im.entity.ImChannelMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ChannelServiceTest {

    private ImChannelRepository channelRepo;
    private ImChannelMemberRepository memberRepo;
    private ChannelService service;

    @BeforeEach
    void setUp() {
        channelRepo = mock(ImChannelRepository.class);
        memberRepo = mock(ImChannelMemberRepository.class);
        service = new ChannelService(channelRepo, memberRepo);
    }

    @Test
    void directKey_isOrderIndependent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // A→B 与 B→A 必须得到同一个键,否则会建出两条直聊
        assertThat(ChannelService.directKey(a, b)).isEqualTo(ChannelService.directKey(b, a));
    }

    @Test
    void create_savesChannelAndAddsOwnerAsMember() {
        when(channelRepo.save(any(ImChannelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID owner = UUID.randomUUID();
        ImChannelEntity c = service.create("tenant_default", owner, "general", "PUBLIC", "topic", List.of());

        assertThat(c.getType()).isEqualTo("PUBLIC");
        assertThat(c.getCreatedBy()).isEqualTo(owner);
        assertThat(c.getTenantId()).isEqualTo("tenant_default");
        verify(memberRepo).save(any(ImChannelMemberEntity.class));
    }

    @Test
    void create_invalidType_returns400() {
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> service.create("t", owner, "x", "BANANA", null, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_directTypeIsRejected() {
        // DIRECT 必须走 getOrCreateDirect,避免绕过去重
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> service.create("t", owner, null, "DIRECT", null, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void getOrCreateDirect_returnsExistingWhenPresent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ImChannelEntity existing = new ImChannelEntity();
        existing.setId(UUID.randomUUID());
        when(channelRepo.findByTenantIdAndDirectKey(any(), any())).thenReturn(Optional.of(existing));

        assertThat(service.getOrCreateDirect("t", a, b)).isSameAs(existing);
        verify(channelRepo, never()).save(any());
    }

    @Test
    void getOrCreateDirect_rejectsSelf() {
        UUID a = UUID.randomUUID();
        assertThatThrownBy(() -> service.getOrCreateDirect("t", a, a))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void listMine_returnsEmptyWhenNoMembership() {
        UUID me = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId("t", me)).thenReturn(List.of());
        assertThat(service.listMine("t", me)).isEmpty();
    }
}
