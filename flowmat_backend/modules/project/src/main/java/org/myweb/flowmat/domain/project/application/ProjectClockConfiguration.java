package org.myweb.flowmat.domain.project.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class ProjectClockConfiguration {
    @Bean public Clock projectClock() { return Clock.systemUTC(); }
}
