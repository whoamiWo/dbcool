package com.nocobase.approval;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalCallbackEventRepository extends JpaRepository<ApprovalCallbackEventEntity, ApprovalCallbackEventId> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE approval_callback_event
               SET processing_status='PROCESSING', worker_id=:workerId, claimed_at=:claimedAt, error=NULL
              WHERE corp_id=:corpId AND event_id=:eventId
                AND (processing_status IN ('RECEIVED', 'FAILED')
                    OR (processing_status='PROCESSING' AND claimed_at < :leaseExpiresBefore))
            """, nativeQuery = true)
    int claim(@Param("corpId") String corpId, @Param("eventId") String eventId,
              @Param("leaseExpiresBefore") java.time.Instant leaseExpiresBefore,
              @Param("claimedAt") java.time.Instant claimedAt,
              @Param("workerId") String workerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE approval_callback_event
               SET processing_status=:status, error=:error, processed_at=:processedAt
              WHERE corp_id=:corpId AND event_id=:eventId
                AND processing_status='PROCESSING' AND worker_id=:workerId
            """, nativeQuery = true)
    int finish(@Param("corpId") String corpId, @Param("eventId") String eventId, @Param("workerId") String workerId,
               @Param("status") String status, @Param("error") String error,
               @Param("processedAt") java.time.Instant processedAt);

    boolean existsByBindingIdAndResultNotAndProcessingStatusIn(UUID bindingId, String result,
                                                                java.util.Collection<String> processingStatuses);
}
