package org.myweb.flowmat.global.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@MappedSuperclass
public abstract class SoftDeleteEntity extends BaseTimeEntity {

    // Schema declares char(1); map as CHAR so ddl-auto=validate matches.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "deleted_yn", length = 1)
    private String deletedYn = "N";
}
