package org.myweb.flowmat.domain.user.application;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.application.publicapi.UserQuery;
import org.myweb.flowmat.domain.user.application.publicapi.UserView;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryImpl implements UserQuery {

    private final UserRepository userRepository;

    @Override
    public Optional<UserView> findByEmail(String email) {
        return userRepository.findByUserEmail(email).map(UserQueryImpl::view);
    }

    @Override
    public Optional<UserView> findById(String userId) {
        return userRepository.findByUserId(userId).map(UserQueryImpl::view);
    }

    private static UserView view(User user) {
        return new UserView(user.getUserId(), user.getUserEmail(), user.getUserName());
    }
}
