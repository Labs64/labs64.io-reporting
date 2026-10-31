package io.labs64.reporting.body;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import io.labs64.reporting.ReportingException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

class BodyStoreTest {

    @Test
    void theKeyMatchesWhatTheSinkWrites() {
        String key = BodyStore.key(
                "bodies/tenant={tenant}/customer={scope}/year={yyyy}/month={MM}/day={dd}/{yyyyMMdd-HHmmss}-{eventId}.json.gz",
                "demo", "V1", "abc", OffsetDateTime.parse("2026-10-01T23:30:05.678+01:00"));
        assertThat(key).isEqualTo("bodies/tenant=demo/customer=V1/year=2026/month=10/day=01/20261001-223005-abc.json.gz");
    }

    @Test
    void readsAGzippedObject() throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write("{\"extra\":{\"request\":{\"body\":\"x\"}}}".getBytes(StandardCharsets.UTF_8));
        }
        BodyStore store = new BodyStore("b", request -> new ByteArrayInputStream(gz.toByteArray()));
        assertThat(store.read("k.json.gz").orElseThrow().path("extra").path("request").path("body").asText()).isEqualTo("x");
    }

    @Test
    void aMissingObjectIsEmptyAndAnOutageIsAnError() {
        BodyStore gone = new BodyStore("b", request -> {
            throw NoSuchKeyException.builder().build();
        });
        assertThat(gone.read("k")).isEmpty();

        BodyStore down = new BodyStore("b", request -> {
            throw S3Exception.builder().message("down").build();
        });
        assertThatThrownBy(() -> down.read("k")).isInstanceOf(ReportingException.class);
    }
}
