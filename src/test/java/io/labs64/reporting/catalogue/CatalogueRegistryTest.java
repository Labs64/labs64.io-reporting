package io.labs64.reporting.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogueRegistryTest {

    @Test
    void loadsOneCataloguePerTenantFile() {
        Map<String, Catalogue> catalogues = CatalogueRegistry.load(Path.of("src/test/resources/catalogue"));
        Catalogue demo = catalogues.get("demo");
        assertThat(demo.database()).isEqualTo("demo");
        assertThat(demo.metrics()).containsKeys("calls", "calls.allTime", "errors");
        assertThat(demo.metrics().get("calls.allTime").fullHistory()).isTrue();
        assertThat(demo.trace()).containsExactly(demo.events());
    }

    @Test
    void noDirectoryIsNoTenants() {
        assertThat(CatalogueRegistry.load(Path.of("does/not/exist"))).isEmpty();
    }

    @Test
    void anInvalidFileStopsTheStartAndSaysWhich(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("broken.yaml"), "database: demo\nmetrics: {m: {group: g, table: 'a b'}}\n");
        assertThatThrownBy(() -> CatalogueRegistry.load(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken.yaml");
    }

    private static final String WITHOUT_BODIES = "database: demo\nmetrics: {}\n";

    @Test
    void bodiesAreEitherAnObjectPerEventOrAFolderOfBatches(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("object.yaml"), WITHOUT_BODIES + "bodies: {group: g, keyTemplate: 'k/{eventId}'}\n");
        Files.writeString(directory.resolve("folder.yaml"), WITHOUT_BODIES + "bodies: {group: g, prefixTemplate: 'k/{HH}/'}\n");

        var catalogues = CatalogueRegistry.load(directory);
        assertThat(catalogues.get("object").bodies().keyTemplate()).isEqualTo("k/{eventId}");
        assertThat(catalogues.get("folder").bodies().prefixTemplate()).isEqualTo("k/{HH}/");
    }

    @Test
    void bodiesCanNameTheColumnThatHoldsTheArchiveKeyWithoutAGroup(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("key.yaml"), WITHOUT_BODIES + "bodies: {keyColumn: archive_key, "
                + "keyPattern: 'tenants/tenant={tenant}/*', exclude: [tenantId, extra.planId]}\n");

        var bodies = CatalogueRegistry.load(directory).get("key").bodies();
        assertThat(bodies.keyColumn()).isEqualTo("archive_key");
        assertThat(bodies.group()).isNull();
        assertThat(bodies.keyPattern()).isEqualTo("tenants/tenant={tenant}/*");
        assertThat(bodies.exclude()).containsExactly("tenantId", "extra.planId");
    }

    @Test
    void aWrongKeyColumnSetupStopsTheStart(@TempDir Path directory) throws Exception {
        for (String bodies : List.of(
                "{keyColumn: archive_key, prefixTemplate: p/}",          // two ways at once
                "{keyTemplate: k, keyPattern: 'x/*'}",                   // a pattern without a key column
                "{keyColumn: 'archive_key; DROP TABLE x'}",              // not one expression
                "{keyColumn: archive_key, exclude: ['extra..x']}",       // not a dotted path
                "{keyColumn: archive_key, exclude: tenantId}")) {        // not a list
            Path dir = Files.createTempDirectory(directory, "c");
            Files.writeString(dir.resolve("bad.yaml"), WITHOUT_BODIES + "bodies: " + bodies + "\n");
            assertThatThrownBy(() -> CatalogueRegistry.load(dir)).as(bodies).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bad.yaml");
        }
    }

    @Test
    void bodiesWithBothOrNeitherStopTheStart(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("both.yaml"), WITHOUT_BODIES + "bodies: {group: g, keyTemplate: k, prefixTemplate: p/}\n");
        assertThatThrownBy(() -> CatalogueRegistry.load(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both.yaml");
    }

    @Test
    void aFragmentThatCouldEscapeItsPlaceStopsTheStart(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("escape.yaml"), "database: demo\nmetrics: {m: {group: g, table: t, scopeColumn: c, "
                + "time: at, value: count(), where: \"a = 1) OR (1 = 1\"}}\n");
        assertThatThrownBy(() -> CatalogueRegistry.load(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escape.yaml");
    }

    @Test
    void everyFragmentFieldIsChecked() {
        for (String field : List.of("time", "value", "where")) {
            Map<String, Object> metric = new HashMap<>(Map.of("group", "g", "table", "t", "scopeColumn", "c", "time", "at", "value", "count()"));
            metric.put(field, "x; DROP TABLE t");
            assertThatThrownBy(() -> CatalogueRegistry.parse(Map.of("database", "d", "metrics", Map.of("m", metric))))
                    .as(field).isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, Object> dimension = Map.of("group", "g", "table", "t", "scopeColumn", "c", "time", "at", "value", "count()",
                "dimensions", Map.of("d", "(SELECT 1)"));
        assertThatThrownBy(() -> CatalogueRegistry.parse(Map.of("database", "d", "metrics", Map.of("m", dimension))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHistoryCeilingIsAPositiveWholeNumber(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("ok.yaml"), WITHOUT_BODIES + "maxHistoryDays: 90\n");
        assertThat(CatalogueRegistry.load(directory).get("ok").maxHistoryDays()).isEqualTo(90);

        Files.writeString(directory.resolve("zero.yaml"), WITHOUT_BODIES + "maxHistoryDays: 0\n");
        assertThatThrownBy(() -> CatalogueRegistry.load(directory)).hasMessageContaining("zero.yaml");
    }
}
