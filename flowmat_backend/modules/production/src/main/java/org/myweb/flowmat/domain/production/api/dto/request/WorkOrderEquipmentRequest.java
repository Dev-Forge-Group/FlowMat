package org.myweb.flowmat.domain.production.api.dto.request;

/** The equipment the order runs on; null or blank unassigns it. */
public record WorkOrderEquipmentRequest(String equipmentId) {
}
