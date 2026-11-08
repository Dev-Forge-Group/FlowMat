package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** A project's inventory settings (docs/domain/storage-location.md SL5-SL7, V117). */
@Getter
@Setter
@Entity
@Table(name = "inventory_setting")
public class InventorySetting {
    @Id
    private String projectId;
    /** warn or refuse: what a place's storage condition does to stock of an item kept differently. */
    private String storageConditionPolicy;
    private long version;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
