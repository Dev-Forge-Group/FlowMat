package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record NonconformityDefectsRequest(@NotEmpty List<String> defectLogIds) {
}
