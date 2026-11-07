package org.myweb.flowmat.domain.flowrun.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "flow_run_event")
public class FlowRunEvent {
    @Id private String eventId;
    private String flowRunId;
    private String stepId;
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private String payloadJson;
    private String requestId;
    private OffsetDateTime occurredAt;
    private String actorType;
    private String actorId;
}
