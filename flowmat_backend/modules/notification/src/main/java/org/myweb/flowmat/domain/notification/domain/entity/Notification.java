package org.myweb.flowmat.domain.notification.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** Something one person is told in the app, such as work given to them (docs/domain/notification.md NT1). */
@Getter
@Setter
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private String nfcId;

    private String userId;
    /** The project the work belongs to (V74); null for one about no project. */
    private String projectId;
    /** What happened, such as ncr_action or warehouse_task. */
    private String nfcType;
    private String nfcTitle;
    private String nfcBody;
    /** What to open: nonconformity or warehouse_task, with its id. */
    private String refType;
    private String refId;

    @Column(name = "is_read")
    private boolean read;

    private OffsetDateTime readAt;
    private OffsetDateTime createdAt;
    /** A reminder's key (V79, NS5): the same key reaches the same person once. Null for everything else. */
    private String dedupeKey;
}
