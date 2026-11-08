package org.myweb.flowmat.domain.project.application.publicapi;

/**
 * Who is signed in and what they may do in a project, for other bounded contexts
 * (docs/architecture/adr/ADR-036-cross-context-contract-types.md decision 6). Each check refuses with the error the API
 * returns: 401 when no one is signed in, 404 for a missing or deleted project, 403 for too low a role.
 */
public interface ProjectAccess {

    /** The signed-in user's id. */
    String requireCurrentUserId();

    /** The project's owner or an active member of any role. */
    void requireProjectReadAccess(String projectId);

    /** As {@link #requireProjectReadAccess(String)}, for the given user. */
    void requireProjectReadAccess(String projectId, String userId);

    /** The project's owner or an active editor. */
    void requireProjectWriteAccess(String projectId);

    /** As {@link #requireProjectWriteAccess(String)}, for the given user. */
    void requireProjectWriteAccess(String projectId, String userId);

    /** The project's owner. */
    void requireProjectOwnerAccess(String projectId);

    /** The signed-in user's role in the project: owner, editor or viewer. Refuses a non-member. */
    String resolveCurrentUserRole(String projectId);
}
