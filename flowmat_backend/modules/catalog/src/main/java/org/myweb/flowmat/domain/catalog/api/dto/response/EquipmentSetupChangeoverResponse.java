package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.util.Map;

public record EquipmentSetupChangeoverResponse(String changeoverId,String equipmentId,Map<String,String> fromAttributes,
    Map<String,String> toAttributes,int priority,int minutes,String note,long version) {
}
