package io.labs64.reporting.body;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.reporting.ReportingException;
import io.labs64.reporting.ReportingProperties;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Reads an event's bodies from the archive. An unbatched S3 sink writes one object per event, whose key follows from
 * the tenant, the scope, the receipt time and the event ID, so no index is needed.
 */
@Component
public class BodyStore {

    private static final Logger LOG = LoggerFactory.getLogger(BodyStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the sink's partition values are reduced to, so the scope in a key is the one in the object's path. */
    private static final Pattern UNSAFE_KEY_SEGMENT = Pattern.compile("[^A-Za-z0-9._-]");

    /** Inside a backend's own timeout, so a slow archive answers as an outage here and not as a hung call. */
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

    private final String bucket;
    private final Function<GetObjectRequest, InputStream> reader;

    @Autowired
    public BodyStore(ReportingProperties properties) {
        ReportingProperties.Bodies bodies = properties.bodies();
        this.bucket = bodies == null ? "" : bodies.bucket();
        if (bucket.isBlank()) {
            this.reader = null;
        } else {
            S3ClientBuilder builder = S3Client.builder()
                    .overrideConfiguration(configuration -> configuration.apiCallTimeout(API_CALL_TIMEOUT));
            if (!bodies.region().isBlank()) {
                builder.region(Region.of(bodies.region()));
            }
            S3Client client = builder.build();
            this.reader = request -> {
                ResponseInputStream<?> stream = client.getObject(request);
                return stream;
            };
        }
    }

    BodyStore(String bucket, Function<GetObjectRequest, InputStream> reader) {
        this.bucket = bucket;
        this.reader = reader;
    }

    public boolean enabled() {
        return reader != null;
    }

    /** The archived event, or empty when retention removed it or it was never archived. */
    public Optional<JsonNode> read(String key) {
        if (!enabled()) {
            return Optional.empty();
        }
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try (InputStream object = reader.apply(request);
                InputStream content = key.endsWith(".gz") ? new GZIPInputStream(object) : object) {
            return Optional.of(JSON.readTree(content));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // The role may read objects but not list the bucket, and without s3:ListBucket S3 answers 403 for a key
            // that doesn't exist. Expired bodies are normal, so this must not read as an outage; a role that really
            // lost its access still shows here, in the log.
            if (e.statusCode() == 403) {
                LOG.warn("The archive refused an object; treated as not kept. key={}", key);
                return Optional.empty();
            }
            throw ReportingException.unavailable("The archive could not answer.", e);
        } catch (SdkException | IOException e) {
            throw ReportingException.unavailable("The archive could not answer.", e);
        }
    }

    /**
     * Fills a catalogue's key template. {@code {tenant}}, {@code {scope}}, {@code {eventId}}, and date parts in
     * braces such as {@code {yyyy}} or {@code {yyyyMMdd-HHmmss}}, formatted from the receipt time.
     */
    public static String key(String template, String tenant, String scope, String eventId, OffsetDateTime received) {
        OffsetDateTime utc = received.withOffsetSameInstant(ZoneOffset.UTC);
        String key = template;
        for (Map.Entry<String, String> value : Map.of("tenant", tenant, "scope",
                UNSAFE_KEY_SEGMENT.matcher(scope).replaceAll("_"), "eventId", eventId)
                .entrySet()) {
            key = key.replace("{" + value.getKey() + "}", value.getValue());
        }
        for (String pattern : new String[] { "yyyyMMdd-HHmmss", "yyyy", "MM", "dd" }) {
            key = key.replace("{" + pattern + "}", DateTimeFormatter.ofPattern(pattern).format(utc));
        }
        return key;
    }
}
