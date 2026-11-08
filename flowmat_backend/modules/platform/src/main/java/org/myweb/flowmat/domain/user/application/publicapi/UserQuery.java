package org.myweb.flowmat.domain.user.application.publicapi;

import java.util.Optional;

/**
 * User reads for other bounded contexts, which use this instead of the user repository
 * (docs/architecture/adr/ADR-012-workflow-access-boundary.md decision 4). Add an operation only when a caller needs it.
 */
public interface UserQuery {

    /** The user with this email as stored. */
    Optional<UserView> findByEmail(String email);

    Optional<UserView> findById(String userId);
}
