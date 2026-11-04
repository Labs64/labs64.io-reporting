package io.labs64.reporting.body;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Warms the archive client before the service reports ready. Spring publishes readiness after the runners have
 * finished, so a pod that is still cold does not receive traffic.
 */
@Component
public class ArchiveWarmUp implements ApplicationRunner {

    private final BodyStore bodies;

    public ArchiveWarmUp(BodyStore bodies) {
        this.bodies = bodies;
    }

    @Override
    public void run(ApplicationArguments args) {
        bodies.warmUp();
    }
}
