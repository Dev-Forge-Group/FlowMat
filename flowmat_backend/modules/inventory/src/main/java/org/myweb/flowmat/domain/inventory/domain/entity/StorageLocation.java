package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/** A place stock is kept: a site, warehouse, zone, location or bin (docs/domain/storage-location.md). */
@Getter
@Setter
@Entity
@Table(name = "storage_location")
public class StorageLocation extends CreatedUpdatedAuditEntity {

    @Id
    private String locationId;

    private String projectId;
    private String parentLocationId;
    /** Stored on stock records as inventory.location and matched case-insensitively. */
    private String locationCode;
    private String locationName;
    private String locationType;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeYn;

    private String note;

    /** Free text like an item's storage condition; places inside without their own take it (SL1). */
    @Column(length = 100)
    private String storageCondition;
}
