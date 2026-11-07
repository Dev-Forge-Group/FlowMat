package org.myweb.flowmat.domain.user.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "role_permissions")
public class RolePermission {

    @Id
    @Column(name = "role_permissions_id")
    private UUID rolePermissionsId;

    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "resource")
    private String resource;

    @Column(name = "action")
    private String action;

    @Column(name = "permission_code")
    private String permissionCode;

    @Column(name = "permission_description")
    private String permissionDescription;
}