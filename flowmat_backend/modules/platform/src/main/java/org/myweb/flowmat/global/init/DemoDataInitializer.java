package org.myweb.flowmat.global.init;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Profile({"dev", "test"})
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoDataInitializer.class);

    private static final String DEMO_USER_ID  = "demo-owner";
    private static final String DEMO_PASSWORD = "demo1234";
    private static final String PLACEHOLDER   = "PLACEHOLDER_REPLACED_BY_INITIALIZER";
    private static final Pattern BCRYPT_PATTERN =
        Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Optional<User> existing = userRepository.findByUserId(DEMO_USER_ID);
        if (existing.isEmpty()) {
            User user = new User();
            user.setId(UUID.randomUUID());
            user.setUserId(DEMO_USER_ID);
            user.setUserName("Demo Owner");
            user.setUserEmail("demo-owner@flowmat.local");
            user.setUserPwd(passwordEncoder.encode(DEMO_PASSWORD));
            user.setUserBirth(LocalDate.of(1990, 1, 1));
            user.setUserTel("000-0000-0000");
            user.setUserRole("admin");
            user.setUserStatus("active");
            user.setDeleteYn("N");
            user.setEmailVerifiedYn("N");
            user.setFailedLoginCount(0);
            user.setPwdUpdatedAt(OffsetDateTime.now());
            userRepository.save(user);
            LOGGER.info("[DemoDataInitializer] demo-owner created for the active demo profile.");
            return;
        }
        User user = existing.get();
        if (needsDemoPasswordReset(user.getUserPwd())) {
            user.setUserPwd(passwordEncoder.encode(DEMO_PASSWORD));
            userRepository.save(user);
            LOGGER.info("[DemoDataInitializer] demo-owner password initialized for the active demo profile.");
        }
    }

    private boolean needsDemoPasswordReset(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return true;
        }
        if (PLACEHOLDER.equals(encodedPassword)) {
            return true;
        }
        return !BCRYPT_PATTERN.matcher(encodedPassword).matches();
    }
}
