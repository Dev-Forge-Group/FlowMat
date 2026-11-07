package org.myweb.flowmat.domain.user.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.user.domain.entity.SocialAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    boolean existsByProviderAndProviderUserId(String provider, String providerUserId);

    Optional<SocialAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<SocialAccount> findAllByUser_UserId(String userId);

    Optional<SocialAccount> findByUser_UserIdAndProvider(String userId, String provider);
}
