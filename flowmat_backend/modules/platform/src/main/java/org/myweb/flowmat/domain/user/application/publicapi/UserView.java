package org.myweb.flowmat.domain.user.application.publicapi;

/** What another bounded context may read about a user: no password, roles or status. */
public record UserView(String userId, String userEmail, String userName) {
}
