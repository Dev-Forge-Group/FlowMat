package org.myweb.flowmat.domain.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUserId(String userId);

    Optional<User> findByUserEmail(String userEmail);

    Optional<User> findByUserIdOrUserEmail(String userId, String userEmail);

    Optional<User> findByDormantToken(String dormantToken);

    Optional<User> findByUserNameAndUserTel(String userName, String userTel);

    boolean existsByUserId(String userId);

    boolean existsByUserEmail(String userEmail);

    boolean existsByUserTel(String userTel);

    boolean existsByUserNickname(String userNickname);

    List<User> findByUserIdContainingIgnoreCaseOrUserNameContainingIgnoreCaseOrUserEmailContainingIgnoreCase(
        String userId, String userName, String userEmail);
}
