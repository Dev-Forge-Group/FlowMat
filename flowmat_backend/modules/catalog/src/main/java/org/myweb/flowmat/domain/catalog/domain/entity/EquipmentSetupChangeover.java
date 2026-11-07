package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name="equipment_setup_changeover")
public class EquipmentSetupChangeover extends CreatedUpdatedAuditEntity {
    @Id private String changeoverId;
    private String equipmentId;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String,String> fromAttributes;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String,String> toAttributes;
    private int priority;
    private int minutes;
    private long version;
    private String note;
}
