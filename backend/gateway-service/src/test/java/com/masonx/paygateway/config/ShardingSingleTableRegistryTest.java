package com.masonx.paygateway.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table a Flyway migration creates in gateway-service must be either a sharded logical
 * table or registered in {@link DataSourceConfig#singleTables()}. An unregistered table makes
 * ShardingSphere intercept its SQL and fail on Postgres-specific syntax at runtime.
 */
class ShardingSingleTableRegistryTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    // One alternation so statements are applied in document order: a migration may rename a
    // table and then create a fresh one under the old name (see V13 gateway_logs partitioning).
    private static final Pattern TABLE_STATEMENT = Pattern.compile(
            "(?i)\\b(?:create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(?<created>[a-z_][a-z0-9_]*)"
                    + "|drop\\s+table\\s+(?:if\\s+exists\\s+)?(?<dropped>[a-z_][a-z0-9_]*)"
                    + "|alter\\s+table\\s+(?:if\\s+exists\\s+)?(?<renamedFrom>[a-z_][a-z0-9_]*)\\s+rename\\s+to\\s+(?<renamedTo>[a-z_][a-z0-9_]*))");

    @Test
    void everyMigrationCreatedTable_isShardedOrRegisteredSingleTable() throws IOException {
        Set<String> live = liveTablesAfterAllMigrations();

        Set<String> registered = new TreeSet<>(DataSourceConfig.SHARDED_LOGICAL_TABLES);
        DataSourceConfig.singleTables().forEach(qualified -> registered.add(unqualified(qualified)));

        Set<String> unregistered = new TreeSet<>(live);
        unregistered.removeAll(registered);

        assertThat(live).isNotEmpty();
        assertThat(unregistered)
                .as("tables created by Flyway but missing from DataSourceConfig.singleTables(); "
                        + "register them as ds_0.<table> so ShardingSphere routes them without SQL interception")
                .isEmpty();
    }

    @Test
    void registeredSingleTables_allExistInMigrations() throws IOException {
        Set<String> live = liveTablesAfterAllMigrations();

        Set<String> stale = new TreeSet<>();
        for (String qualified : DataSourceConfig.singleTables()) {
            String table = unqualified(qualified);
            boolean shardSuffixed = table.matches(".*_\\d{2}$");
            if (!shardSuffixed && !live.contains(table)) {
                stale.add(table);
            }
        }
        assertThat(stale)
                .as("single tables registered in DataSourceConfig but never created, or already dropped, by migrations")
                .isEmpty();
    }

    private static Set<String> liveTablesAfterAllMigrations() throws IOException {
        Set<String> live = new TreeSet<>();
        for (Path migration : sortedMigrations()) {
            Matcher statement = TABLE_STATEMENT.matcher(Files.readString(migration));
            while (statement.find()) {
                if (statement.group("created") != null) {
                    live.add(statement.group("created"));
                } else if (statement.group("dropped") != null) {
                    live.remove(statement.group("dropped"));
                } else {
                    live.remove(statement.group("renamedFrom"));
                    live.add(statement.group("renamedTo"));
                }
            }
        }
        // Names ending in "_" are format templates inside DO blocks that stamp out the shard suffix.
        live.removeIf(name -> name.endsWith("_"));
        return live;
    }

    private static String unqualified(String qualifiedTable) {
        return qualifiedTable.substring(qualifiedTable.indexOf('.') + 1);
    }

    private static List<Path> sortedMigrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(p -> p.toString().endsWith(".sql"))
                    .sorted((a, b) -> Integer.compare(version(a), version(b)))
                    .toList();
        }
    }

    private static int version(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
