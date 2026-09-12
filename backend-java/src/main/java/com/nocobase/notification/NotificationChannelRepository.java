package com.nocobase.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannelEntity, UUID> {

    @Query("SELECT n FROM NotificationChannelEntity n WHERE n.tenantId = :tenantId ORDER BY n.type, n.name")
    List<NotificationChannelEntity> findByTenantId(@Param("tenantId") String tenantId);

    @Query("SELECT n FROM NotificationChannelEntity n WHERE n.tenantId = :tenantId AND n.enabled = true ORDER BY n.type, n.name")
    List<NotificationChannelEntity> findEnabledByTenantId(@Param("tenantId") String tenantId);

    @Query("SELECT n FROM NotificationChannelEntity n WHERE n.tenantId = :tenantId AND n.type = :type ORDER BY n.name")
    List<NotificationChannelEntity> findByTenantIdAndType(@Param("tenantId") String tenantId, @Param("type") NotificationChannelEntity.Type type);
}
