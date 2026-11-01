package io.labs64.reporting.clickhouse;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.sun.net.httpserver.HttpServer;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.ReportingProperties;
import io.labs64.reporting.query.SqlQuery;

class ClickHouseClientTest {

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(int status, String code) {
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("X-ClickHouse-Exception-Code", code);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    private ClickHouseClient client() {
        return new ClickHouseClient(new ReportingProperties.ClickHouse(
                "http://127.0.0.1:" + server.getAddress().getPort(), "reporting", "reader", Duration.ofSeconds(2),
                Duration.ofSeconds(2), 4, Duration.ofMillis(500)));
    }

    private void assertAnswers(HttpStatus expected) {
        assertThatThrownBy(() -> client().query("demo", "A", new SqlQuery("SELECT 1", Map.of())))
                .isInstanceOfSatisfying(ReportingException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.status()).isEqualTo(expected));
    }

    @Test
    void tooManyQueriesAtOnceIsABurstNotAnOutage() {
        answer(500, "202");
        assertAnswers(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void anyOtherRefusalIsAnOutage() {
        answer(500, "241");
        assertAnswers(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
