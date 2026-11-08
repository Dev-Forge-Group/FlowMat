package org.myweb.flowmat.domain.catalog.api.dto.request;

import java.util.Map;

public record SetupChangeoverRequest(Map<String,String> fromAttributes,Map<String,String> toAttributes,
    int priority,int minutes,String note,long expectedVersion) {
}
