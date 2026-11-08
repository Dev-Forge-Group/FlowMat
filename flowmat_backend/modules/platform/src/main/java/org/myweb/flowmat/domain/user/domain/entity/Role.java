package org.myweb.flowmat.domain.user.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "roles")
public class Role extends BaseTimeEntity {

    @Id
    private UUID roleId;

    private String roleName;
    private String roleDescription;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String roleIsSystem;
}
