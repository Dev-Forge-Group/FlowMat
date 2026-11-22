package org.myweb.flowmat.domain.workflow.application;

import java.util.Optional;

/**
 * The units a workflow's ports and connections may name: the one fact outside the graph that its checks need. The server
 * answers from the catalog; a check run on a graph alone (the flow kernel's fixtures, ADR-040) answers from a fixed list.
 */
interface WorkflowUnits {

    /** Whether the code is a unit the project knows. */
    boolean known(String code);

    /** The unit's type (mass, length, ...), when the code is known. */
    Optional<String> type(String code);
}
