package com.nocobase.im;

import com.nocobase.im.entity.ImChannelEntity;
import com.nocobase.im.entity.ImChannelMemberEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 频道服务:创建、加入/离开、成员管理、直聊去重。
 *
 * <p>所有方法显式接收 tenantId,与既有 Service(如 CollectionService)保持一致,
 * 便于在 WS 线程等非 HTTP 上下文中复用。
 */
@Service
public class ChannelService {

    private static final Set<String> VALID_TYPES =
            Set.of(ImChannelEntity.Type.PUBLIC.name(),
                   ImChannelEntity.Type.PRIVATE.name(),
                   ImChannelEntity.Type.DIRECT.name());

    private final ImChannelRepository channelRepository;
    private final ImChannelMemberRepository memberRepository;

    public ChannelService(ImChannelRepository channelRepository,
                          ImChannelMemberRepository memberRepository) {
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
    }

    /** 我加入且未归档的频道,按更新时间倒序。 */
    public List<ImChannelEntity> listMine(String tenantId, UUID userId) {
        List<UUID> joined = memberRepository.findByTenantIdAndUserId(tenantId, userId)
                .stream().map(ImChannelMemberEntity::getChannelId).toList();
        if (joined.isEmpty()) return List.of();
        Set<UUID> index = new LinkedHashSet<>(joined);
        return channelRepository.findByTenantIdAndArchivedAtIsNullOrderByUpdatedAtDesc(tenantId)
                .stream().filter(c -> index.contains(c.getId())).toList();
    }

    /** 建频道;创建者为 OWNER,memberIds 为受邀成员(自动去重、排除自己)。 */
    @Transactional
    public ImChannelEntity create(String tenantId, UUID userId, String name, String type,
                                  String topic, List<UUID> memberIds) {
        String t = (type == null || type.isBlank())
                ? ImChannelEntity.Type.PUBLIC.name() : type.trim().toUpperCase();
        if (!VALID_TYPES.contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "type 必须是 PUBLIC / PRIVATE / DIRECT");
        }
        if (ImChannelEntity.Type.DIRECT.name().equals(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "DIRECT 频道请改用 getOrCreateDirect()");
        }

        ImChannelEntity c = new ImChannelEntity();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setType(t);
        c.setTopic(topic);
        c.setCreatedBy(userId);
        c.setTenantId(tenantId);

        ImChannelEntity saved = channelRepository.save(c);
        addMember(tenantId, saved.getId(), userId, ImChannelMemberEntity.Role.OWNER.name());

        List<UUID> invites = new ArrayList<>();
        if (memberIds != null) {
            for (UUID uid : memberIds) {
                if (uid != null && !uid.equals(userId) && !invites.contains(uid)) invites.add(uid);
            }
        }
        for (UUID uid : invites) {
            addMember(tenantId, saved.getId(), uid, ImChannelMemberEntity.Role.MEMBER.name());
        }
        return saved;
    }

    /** 获取或创建一对一会话(幂等)。 */
    @Transactional
    public ImChannelEntity getOrCreateDirect(String tenantId, UUID self, UUID other) {
        if (other == null || other.equals(self)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能与自己建立直聊");
        }
        String key = directKey(self, other);
        return channelRepository.findByTenantIdAndDirectKey(tenantId, key)
                .orElseGet(() -> {
                    ImChannelEntity c = new ImChannelEntity();
                    c.setId(UUID.randomUUID());
                    c.setType(ImChannelEntity.Type.DIRECT.name());
                    c.setDirectKey(key);
                    c.setCreatedBy(self);
                    c.setTenantId(tenantId);
                    ImChannelEntity saved = channelRepository.save(c);
                    addMember(tenantId, saved.getId(), self, ImChannelMemberEntity.Role.MEMBER.name());
                    addMember(tenantId, saved.getId(), other, ImChannelMemberEntity.Role.MEMBER.name());
                    return saved;
                });
    }

    /**
     * 直聊去重键:两个 uuid 按字典序拼接,保证 A→B 与 B→A 得到同一个键。
     */
    public static String directKey(UUID a, UUID b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) <= 0 ? x + ":" + y : y + ":" + x;
    }

    @Transactional
    public void join(String tenantId, UUID channelId, UUID userId, String role) {
        mustGet(tenantId, channelId);
        if (memberRepository.existsByChannelIdAndUserId(channelId, userId)) return;
        addMember(tenantId, channelId, userId,
                role == null ? ImChannelMemberEntity.Role.MEMBER.name() : role.trim().toUpperCase());
    }

    @Transactional
    public void leave(UUID channelId, UUID userId) {
        memberRepository.findByChannelIdAndUserId(channelId, userId)
                .ifPresent(memberRepository::delete);
    }

    @Transactional
    public void archive(String tenantId, UUID channelId) {
        ImChannelEntity c = mustGet(tenantId, channelId);
        c.setArchivedAt(java.time.Instant.now());
        channelRepository.save(c);
    }

    public boolean isMember(UUID channelId, UUID userId) {
        return memberRepository.existsByChannelIdAndUserId(channelId, userId);
    }

    public List<ImChannelMemberEntity> members(UUID channelId) {
        return memberRepository.findByChannelId(channelId);
    }

    public ImChannelEntity mustGet(String tenantId, UUID channelId) {
        return channelRepository.findByIdAndTenantId(channelId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "频道不存在"));
    }

    private void addMember(String tenantId, UUID channelId, UUID userId, String role) {
        ImChannelMemberEntity m = new ImChannelMemberEntity();
        m.setId(UUID.randomUUID());
        m.setChannelId(channelId);
        m.setUserId(userId);
        m.setRole(role);
        m.setTenantId(tenantId);
        memberRepository.save(m);
    }
}
