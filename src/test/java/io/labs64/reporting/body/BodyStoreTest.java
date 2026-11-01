package io.labs64.reporting.body;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;

import io.labs64.reporting.ReportingException;
import software.amazon.awssdk.core.exception.SdkClientException;
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
    void theScopeInTheKeyIsReducedAsTheSinkReducesIt() {
        assertThat(BodyStore.key("customer={scope}/{eventId}", "demo", "V:1", "abc", OffsetDateTime.now()))
                .isEqualTo("customer=V_1/abc");
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

    @Test
    void aMissingObjectIsEmptyForARoleThatMayNotList() {
        BodyStore noList = new BodyStore("b", request -> {
            throw S3Exception.builder().statusCode(403).message("Access Denied").build();
        });
        assertThat(noList.read("k")).isEmpty();
    }

    @Test
    void aFailureBeforeS3AnswersIsAnOutageToo() {
        BodyStore unreachable = new BodyStore("b", request -> {
            throw SdkClientException.create("Unable to execute HTTP request");
        });
        assertThatThrownBy(() -> unreachable.read("k")).isInstanceOf(ReportingException.class)
                .extracting(e -> ((ReportingException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return gz.toByteArray();
    }

    @Test
    void theFolderMatchesWhatABatchingSinkWrites() {
        String folder = BodyStore.key("bodies/tenant={tenant}/v={scope}/year={yyyy}/month={MM}/day={dd}/hour={HH}/minute={mm}/",
                "demo", "V1", "abc", OffsetDateTime.parse("2026-10-01T23:30:05.678+01:00"));
        assertThat(folder).isEqualTo("bodies/tenant=demo/v=V1/year=2026/month=10/day=01/hour=22/minute=30/");
    }

    @Test
    void findsAnEventAmongTheBatchesOfAFolder() throws Exception {
        Map<String, byte[]> objects = Map.of(
                "f/first.jsonl.gz", gzip("{\"eventId\":\"a\",\"extra\":{\"request\":{\"body\":\"quotes target\"}}}\n"),
                "f/second.jsonl.gz", gzip("{\"eventId\":\"b\"}\n{\"eventId\":\"target\",\"extra\":{\"request\":{\"body\":\"x\"}}}\n"));
        BodyStore store = new BodyStore("b", request -> new ByteArrayInputStream(objects.get(request.key())),
                request -> {
                    assertThat(request.prefix()).isEqualTo("f/");
                    return List.of("f/first.jsonl.gz", "f/second.jsonl.gz");
                });

        assertThat(store.find("f/", "target").orElseThrow().path("extra").path("request").path("body").asText()).isEqualTo("x");
        assertThat(store.find("f/", "absent")).isEmpty();
    }

    @Test
    void aFolderThatCannotBeListedIsAnOutageAndAnEmptyOneIsNotKept() {
        BodyStore down = new BodyStore("b", request -> null, request -> {
            throw S3Exception.builder().statusCode(500).message("down").build();
        });
        assertThatThrownBy(() -> down.find("f/", "x")).isInstanceOf(ReportingException.class);

        assertThat(new BodyStore("b", request -> null, request -> List.of()).find("f/", "x")).isEmpty();
    }
}
