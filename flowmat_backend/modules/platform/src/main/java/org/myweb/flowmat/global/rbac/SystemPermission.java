package org.myweb.flowmat.global.rbac;

public enum SystemPermission {
    TEMPLATE_READ_PRIVATE("template:read_private"),
    TEMPLATE_MANAGE("template:manage"),
    USER_MANAGE("user:manage"),
    PROJECT_VIEW_ALL("project:view_all"),
    MASTER_DATA_MANAGE("master_data:manage");

    private final String code;

    SystemPermission(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
