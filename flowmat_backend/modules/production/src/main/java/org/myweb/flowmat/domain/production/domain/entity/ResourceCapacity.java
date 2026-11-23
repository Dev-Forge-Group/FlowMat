package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** How much energy or water a project can draw per hour or per day (docs/architecture/adr/ADR-046-resources-first-class.md decision 5). */
@Getter
@Setter
@Entity
@Table(name = "resource_capacity")
@IdClass(ResourceCapacity.Key.class)
public class ResourceCapacity {

    public record Key(String projectId, String resourceType, String unit) implements Serializable {
        public Key() {
            this(null, null, null);
        }
    }

    @Id
    private String projectId;
    @Id
    private String resourceType;
    @Id
    private String unit;
    private BigDecimal amount;
    private String per;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
