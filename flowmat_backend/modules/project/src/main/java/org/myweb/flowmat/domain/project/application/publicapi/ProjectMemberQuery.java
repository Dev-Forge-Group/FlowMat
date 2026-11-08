package org.myweb.flowmat.domain.project.application.publicapi;

import java.util.List;

/**
 * Project membership reads for other bounded contexts, which use this instead of the project repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). It answers and never refuses, so a caller inside a transaction
 * can turn a "no" into its own message. Add an operation only when a caller needs it.
 */
public interface ProjectMemberQuery {

    /** Whether the user is an active member of the project; the owner is not a member row, so ask about the owner apart. */
    boolean isActiveMember(String projectId, String userId);

    /** Whether the user can read the project: it is not deleted, and they own it or are an active member. */
    boolean canRead(String projectId, String userId);

    /** Whether the user can change the project: it is not deleted, and they own it or are an active owner or editor member. */
    boolean canWrite(String projectId, String userId);

    /** The owner of a project that is not deleted; null otherwise. */
    String ownerOf(String projectId);

    /** Who can read the project: its owner first, then its active members in the order they joined; empty when deleted. */
    List<String> readers(String projectId);
}
