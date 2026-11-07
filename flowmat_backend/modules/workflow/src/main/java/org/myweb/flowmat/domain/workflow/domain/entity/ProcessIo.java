package org.myweb.flowmat.domain.workflow.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "process_io")
public class ProcessIo extends CreatedUpdatedAuditEntity {

    @Id
    private String processIoId;

    private String processId;
    private String itemId;
    private String ioName;
    private String direction;
    private String ioType;
    private String role;
    private String resourceType;
    private BigDecimal quantity;
    private String unit;
    private String formula;
    @JdbcTypeCode(SqlTypes.JSON)
    private String schemaJson;
    private String validationRule;
    private String colorScheme;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String requiredYn;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String allowShortageYn;
}
