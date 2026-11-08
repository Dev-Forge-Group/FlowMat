package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.time.OffsetDateTime;
import java.util.Map;

public record ItemSetupAttributesResponse(String itemId,Map<String,String> attributes,long version,String updatedBy,OffsetDateTime updatedAt) {
}
