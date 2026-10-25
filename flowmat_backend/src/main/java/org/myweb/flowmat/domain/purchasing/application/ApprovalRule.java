package org.myweb.flowmat.domain.purchasing.application;

import java.math.BigDecimal;

/**
 * One approval a project's purchase orders need from a value up (docs/domain/purchasing.md PO50).
 *
 * @param projectOwner the owner's, from the approval limit; otherwise a member step's
 */
record ApprovalRule(BigDecimal fromValue, String approverId, boolean projectOwner) {
}
