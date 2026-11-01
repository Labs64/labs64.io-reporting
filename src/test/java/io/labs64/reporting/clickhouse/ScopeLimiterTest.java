package io.labs64.reporting.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.labs64.reporting.ReportingException;

class ScopeLimiterTest {

    private final ScopeLimiter limiter = new ScopeLimiter(2, Duration.ofMillis(50));
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        pool.shutdownNow();
    }

    private void occupy(String scope, int queries) throws InterruptedException {
        CountDownLatch running = new CountDownLatch(queries);
        for (int i = 0; i < queries; i++) {
            pool.submit(() -> limiter.run(scope, () -> {
                running.countDown();
                try {
                    return release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return false;
                }
            }));
        }
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aScopeOverItsShareIsTheOnlyOneThatWaitsAndThenIsTurnedAway() throws Exception {
        occupy("busy", 2);

        assertThatThrownBy(() -> limiter.run("busy", () -> "answer"))
                .isInstanceOfSatisfying(ReportingException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(limiter.run("other", () -> "answer")).isEqualTo("answer");
    }

    @Test
    void aQueryThatWaitsWithinTheWaitGetsItsTurn() throws Exception {
        ScopeLimiter patient = new ScopeLimiter(1, Duration.ofSeconds(5));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        pool.submit(() -> patient.run("busy", () -> {
            running.countDown();
            try {
                return finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        var waiting = pool.submit(() -> patient.run("busy", () -> "answer"));
        finish.countDown();
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("answer");
    }

    @Test
    void aFailedQueryGivesItsSlotBackAndAnIdleScopeLeavesNothingBehind() {
        ScopeLimiter one = new ScopeLimiter(1, Duration.ZERO);
        assertThatThrownBy(() -> one.run("A", () -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(one.run("A", () -> "answer")).isEqualTo("answer");
        assertThat(one.scopesInUse()).isZero();
    }
}
