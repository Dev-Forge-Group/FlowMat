package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name="item_setup_attributes")
public class ItemSetupAttributes {
    @Id private String itemId;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String,String> attributes;
    private long version;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
