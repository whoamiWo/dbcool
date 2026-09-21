package com.nocobase.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface TicketRepository extends JpaRepository<TicketEntity, UUID> {
    List<TicketEntity> findByTenantId(String tenantId);
    List<TicketEntity> findByTenantIdAndStatus(String tenantId, TicketEntity.Status status);
}
