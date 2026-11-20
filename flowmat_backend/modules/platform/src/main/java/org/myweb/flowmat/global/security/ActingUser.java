package org.myweb.flowmat.global.security;

import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Work done for a user outside a request of theirs, such as a scheduled run they switched on. The services it calls
 * see that user as the current one, with the same checks a request of theirs would meet; nothing is granted beyond
 * their own access. The thread's earlier security context comes back afterwards.
 */
public final class ActingUser {

    private ActingUser() {
    }

    public static <T> T call(String userId, Supplier<T> work) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("A user to act as is required");
        SecurityContext earlier = SecurityContextHolder.getContext();
        AuthUser user = new AuthUser(userId.trim());
        SecurityContext acting = SecurityContextHolder.createEmptyContext();
        acting.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(acting);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(earlier);
        }
    }
}
