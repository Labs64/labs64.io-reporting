package io.labs64.reporting.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
