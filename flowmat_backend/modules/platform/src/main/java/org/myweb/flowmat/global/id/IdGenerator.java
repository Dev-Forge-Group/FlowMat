package org.myweb.flowmat.global.id;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
