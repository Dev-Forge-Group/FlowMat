package org.myweb.flowmat.domain.workflow.application.publicapi;

import java.util.Map;

/**
 * A process port as a production run reads it: its process, item and direction (input or output), and in
 * {@code ruleFacts} every property the port keeps, read-only, for flow rules
 * (docs/architecture/adr/ADR-018-entity-boundary.md decision 5).
 */
public record ProcessIoRunView(String processIoId, String processId, String itemId, String direction, Map<String, Object> ruleFacts) {
}
