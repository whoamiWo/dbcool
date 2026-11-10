package com.nocobase.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ApprovalCallbackEventId implements Serializable {
    @Column(name = "corp_id", nullable = false)
    private String corpId;
    @Column(name = "event_id", nullable = false)
    private String eventId;

    public ApprovalCallbackEventId() {}

    public ApprovalCallbackEventId(String corpId, String eventId) {
        this.corpId = corpId;
        this.eventId = eventId;
    }

    public String getCorpId() { return corpId; }
    public void setCorpId(String corpId) { this.corpId = corpId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    @Override public boolean equals(Object other) {
        return other instanceof ApprovalCallbackEventId id
                && Objects.equals(corpId, id.corpId) && Objects.equals(eventId, id.eventId);
    }
    @Override public int hashCode() { return Objects.hash(corpId, eventId); }
}
