package com.nocobase.im;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nocobase.im.entity.ImChannelEntity;
import com.nocobase.im.entity.ImHuddleEntity;
import com.nocobase.im.entity.ImHuddleParticipantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE105: HuddleService 覆盖（创建/加入/离开/参与者状态）。
 *
 * <p>纯 Mockito，不加载 Spring 上下文。
 */
public class HuddleServiceTest {

    private ImHuddleRepository huddleRepository;
    private ImHuddleParticipantRepository participantRepository;
    private ImChannelRepository channelRepository;
    private ImChannelMemberRepository channelMemberRepository;
    private HuddleService service;

    private static final String TENANT = "tenant_default";
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HUDDLE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        huddleRepository = mock(ImHuddleRepository.class);
        participantRepository = mock(ImHuddleParticipantRepository.class);
        channelRepository = mock(ImChannelRepository.class);
        channelMemberRepository = mock(ImChannelMemberRepository.class);
        service = new HuddleService(huddleRepository, participantRepository,
                channelRepository, channelMemberRepository);

        ImChannelEntity channel = new ImChannelEntity();
        channel.setId(CHANNEL_ID);
        channel.setTenantId(TENANT);
        when(channelRepository.findByIdAndTenantId(CHANNEL_ID, TENANT))
                .thenReturn(Optional.of(channel));
        when(channelMemberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                .thenReturn(true);
    }

    // ── create ──────────────────────────────────────────────────

    @Test
    void create_channelNotFound_throws404() {
        when(channelRepository.findByIdAndTenantId(CHANNEL_ID, TENANT))
                .thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(TENANT, USER_ID, CHANNEL_ID, "h"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void create_notMember_throws403() {
        when(channelMemberRepository.existsByChannelIdAndUserId(CHANNEL_ID, USER_ID))
                .thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(TENANT, USER_ID, CHANNEL_ID, "h"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void create_success_persistsHuddleAndCreatorParticipant() {
        when(huddleRepository.save(any(ImHuddleEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(participantRepository.save(any(ImHuddleParticipantEntity.class))).thenAnswer(i -> i.getArgument(0));

        ImHuddleEntity huddle = service.create(TENANT, USER_ID, CHANNEL_ID, "晨会");

        assertNotNull(huddle.getId());
        assertEquals("晨会", huddle.getName());
        org.mockito.Mockito.verify(huddleRepository).save(any(ImHuddleEntity.class));
        org.mockito.Mockito.verify(participantRepository).save(any(ImHuddleParticipantEntity.class));
    }

    // ── join ────────────────────────────────────────────────────

    @Test
    void join_huddleNotFound_throws404() {
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.join(HUDDLE_ID, USER_ID, TENANT));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void join_alreadyJoined_throwsConflict() {
        ImHuddleEntity huddle = new ImHuddleEntity();
        huddle.setId(HUDDLE_ID);
        huddle.setTenantId(TENANT);
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.of(huddle));
        when(participantRepository.existsByHuddleIdAndUserId(HUDDLE_ID, USER_ID))
                .thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.join(HUDDLE_ID, USER_ID, TENANT));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void join_newUser_addsParticipant() {
        ImHuddleEntity huddle = new ImHuddleEntity();
        huddle.setId(HUDDLE_ID);
        huddle.setTenantId(TENANT);
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.of(huddle));
        when(participantRepository.existsByHuddleIdAndUserId(HUDDLE_ID, USER_ID))
                .thenReturn(false);
        when(participantRepository.save(any(ImHuddleParticipantEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.join(HUDDLE_ID, USER_ID, TENANT);
        org.mockito.Mockito.verify(participantRepository).save(any(ImHuddleParticipantEntity.class));
    }

    // ── leave ───────────────────────────────────────────────────

    @Test
    void leave_huddleNotFound_throws404() {
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.leave(HUDDLE_ID, USER_ID, TENANT));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void leave_marksParticipantLeft() {
        ImHuddleEntity huddle = new ImHuddleEntity();
        huddle.setId(HUDDLE_ID);
        huddle.setTenantId(TENANT);
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.of(huddle));

        ImHuddleParticipantEntity p = new ImHuddleParticipantEntity();
        p.setId(UUID.randomUUID());
        when(participantRepository.findByHuddleIdAndUserId(HUDDLE_ID, USER_ID))
                .thenReturn(Optional.of(p));
        when(participantRepository.save(any(ImHuddleParticipantEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.leave(HUDDLE_ID, USER_ID, TENANT);
        // leave 实际是标记 leftAt，不删除
        org.mockito.Mockito.verify(participantRepository).save(p);
    }

    @Test
    void leave_notParticipant_throws404() {
        ImHuddleEntity huddle = new ImHuddleEntity();
        huddle.setId(HUDDLE_ID);
        huddle.setTenantId(TENANT);
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.of(huddle));
        when(participantRepository.findByHuddleIdAndUserId(HUDDLE_ID, USER_ID))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.leave(HUDDLE_ID, USER_ID, TENANT));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ── list / get ──────────────────────────────────────────────

    @Test
    void listActive_returnsHuddles() {
        ImHuddleEntity h = new ImHuddleEntity();
        h.setId(HUDDLE_ID);
        when(huddleRepository.findByTenantIdAndChannelIdAndStatusNot(TENANT, CHANNEL_ID, "ENDED"))
                .thenReturn(List.of(h));
        List<ImHuddleEntity> out = service.listActive(TENANT, CHANNEL_ID);
        assertEquals(1, out.size());
    }

    @Test
    void get_huddleNotFound_throws404() {
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.get(HUDDLE_ID, TENANT));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void listParticipants_huddleNotFound_throws404() {
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listParticipants(HUDDLE_ID, TENANT));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void listParticipants_returnsList() {
        ImHuddleEntity huddle = new ImHuddleEntity();
        huddle.setId(HUDDLE_ID);
        huddle.setTenantId(TENANT);
        when(huddleRepository.findByIdAndTenantId(HUDDLE_ID, TENANT))
                .thenReturn(Optional.of(huddle));

        ImHuddleParticipantEntity p = new ImHuddleParticipantEntity();
        p.setId(UUID.randomUUID());
        when(participantRepository.findByHuddleId(HUDDLE_ID)).thenReturn(List.of(p));
        assertEquals(1, service.listParticipants(HUDDLE_ID, TENANT).size());
    }
}
