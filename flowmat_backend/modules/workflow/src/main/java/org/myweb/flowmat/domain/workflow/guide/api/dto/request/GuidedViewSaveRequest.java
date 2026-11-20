package org.myweb.flowmat.domain.workflow.guide.api.dto.request;

import java.util.List;

/**
 * A chapter to make or to change (ADR-037).
 *
 * @param label 1 to 48 characters
 * @param note up to 140 characters; blank for none
 * @param focusProcessIds 1 to 30 processes of the workflow, each once, in the order a reader walks them
 * @param version the version the change was made on; required to change a chapter, ignored when making one
 */
public record GuidedViewSaveRequest(String label, String note, List<String> focusProcessIds, Integer version) {
}
