package io.labs64.reporting.clickhouse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.labs64.reporting.ReportingException;

/**
 * A fair share of the store's query slots: one scope can't take them all. Per pod, on purpose: it needs no shared
 * state, and the store's own limit stays the bound over all pods, so keep pods x limit under that limit.
 */
class ScopeLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(ScopeLimiter.class);

    private final int permits;
    private final Duration wait;
    private final Map<String, Slots> scopes = new ConcurrentHashMap<>();

    ScopeLimiter(int permits, Duration wait) {
        this.permits = permits;
        this.wait = wait;
    }

    <T> T run(String scope, Supplier<T> query) {
        // Counted, so an idle scope leaves no entry: scopes are as many as a tenant has customers.
        Slots slots = scopes.compute(scope, (name, existing) -> {
            Slots current = existing == null ? new Slots(new Semaphore(permits)) : existing;
            current.users++;
            return current;
        });
        try {
            if (!slots.semaphore.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.info("A scope is over its share of queries. scope={}, share={}", scope, permits);
                throw ReportingException.busy("Too many reports at once: ask again in a moment.");
            }
            try {
                return query.get();
            } finally {
                slots.semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ReportingException.unavailable("The report store could not answer.", e);
        } finally {
            scopes.compute(scope, (name, current) -> --current.users == 0 ? null : current);
        }
    }

    int scopesInUse() {
        return scopes.size();
    }

    private static final class Slots {
        private final Semaphore semaphore;
        private int users;

        Slots(Semaphore semaphore) {
            this.semaphore = semaphore;
        }
    }
}
