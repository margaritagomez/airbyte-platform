/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.test.acceptance;

import static io.airbyte.test.utils.AcceptanceTestHarness.COLUMN_ID;
import static io.airbyte.test.utils.AcceptanceTestHarness.COLUMN_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.generated.WebBackendApi;
import io.airbyte.api.client.invoker.generated.ApiClient;
import io.airbyte.api.client.invoker.generated.ApiException;
import io.airbyte.api.client.model.generated.AirbyteCatalog;
import io.airbyte.api.client.model.generated.AirbyteStream;
import io.airbyte.api.client.model.generated.AirbyteStreamAndConfiguration;
import io.airbyte.api.client.model.generated.ConnectionRead;
import io.airbyte.api.client.model.generated.ConnectionState;
import io.airbyte.api.client.model.generated.ConnectionStateType;
import io.airbyte.api.client.model.generated.DestinationDefinitionIdRequestBody;
import io.airbyte.api.client.model.generated.DestinationDefinitionRead;
import io.airbyte.api.client.model.generated.DestinationSyncMode;
import io.airbyte.api.client.model.generated.JobInfoRead;
import io.airbyte.api.client.model.generated.JobRead;
import io.airbyte.api.client.model.generated.OperationRead;
import io.airbyte.api.client.model.generated.SourceDefinitionIdRequestBody;
import io.airbyte.api.client.model.generated.SourceDefinitionRead;
import io.airbyte.api.client.model.generated.SourceDiscoverSchemaRead;
import io.airbyte.api.client.model.generated.SourceRead;
import io.airbyte.api.client.model.generated.StreamDescriptor;
import io.airbyte.api.client.model.generated.StreamState;
import io.airbyte.api.client.model.generated.SyncMode;
import io.airbyte.api.client.model.generated.WebBackendConnectionUpdate;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.Database;
import io.airbyte.test.utils.AcceptanceTestHarness;
import io.airbyte.test.utils.Databases;
import io.airbyte.test.utils.SchemaTableNamePair;
import io.airbyte.test.utils.TestConnectionCreate;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * These tests test the CDC source behavior in Airbyte, ensuring that the behavior of syncs when in
 * CDC mode is as expected
 * <p>
 * Some of the tests in this class are specifically testing partial reset behavior when in CDC mode,
 * support for which was recently added to the postgres connector.
 * <p>
 * These tests are disabled in Kube, similar to the BasicAcceptanceTests, because they aren't
 * testing any behavior that is specific to or dependent on this being run on kube vs docker.
 * Therefore, since operations tend to take longer to perform on kube, there is little value in
 * re-running these tests on kube when we already run them on docker.
 */
@DisabledIfEnvironmentVariable(named = "KUBE",
                               matches = "true")
@SuppressWarnings({"PMD.JUnitTestsShouldIncludeAssert", "DataFlowIssue", "SqlDialectInspection", "SqlNoDataSourceInspection"})
@TestInstance(Lifecycle.PER_CLASS)
@Disabled
class CdcAcceptanceTests {

  record DestinationCdcRecordMatcher(JsonNode sourceRecord, Instant minUpdatedAt, Optional<Instant> minDeletedAt) {

  }

  private static final Logger LOGGER = LoggerFactory.getLogger(BasicAcceptanceTests.class);

  private static final String POSTGRES_INIT_SQL_FILE = "postgres_init_cdc.sql";
  private static final String CDC_METHOD = "CDC";
  // must match Postgres' default CDC cursor name
  private static final String POSTGRES_DEFAULT_CDC_CURSOR = "_ab_cdc_lsn";
  // must match replication slot name used in the above POSTGRES_INIT_SQL_FILE
  private static final String REPLICATION_SLOT = "airbyte_slot";
  // must match publication name used in the above POSTGRES_INIT_SQL_FILE
  private static final String PUBLICATION = "airbyte_publication";
  private static final Integer INITIAL_WAITING_SECONDS = 5;

  private static final String SOURCE_NAME = "CDC Source";
  private static final String SCHEMA_NAME = "public";
  private static final String CDC_UPDATED_AT_COLUMN = "_ab_cdc_updated_at";
  private static final String CDC_DELETED_AT_COLUMN = "_ab_cdc_deleted_at";
  private static final String ID_AND_NAME_TABLE = "id_and_name";
  private static final String COLOR_PALETTE_TABLE = "color_palette";
  private static final String COLUMN_COLOR = "color";
  private static final String STARTING = "Starting {}";
  private static final String STARTING_SYNC_ONE = "Starting {} sync 1";

  // todo (cgardens) - this looks like it isn't used, but i'm not sure if it should be. delete?
  // version of the postgres destination connector that was built with the
  // old Airbyte protocol that does not contain any per-stream logic/fields
  @SuppressWarnings("unused")
  private static final String POSTGRES_DESTINATION_LEGACY_CONNECTOR_VERSION = "0.3.19";

  private static UUID workspaceId;
  private static OperationRead operationRead;

  private static AcceptanceTestHarness testHarness;

  private static final String AIRBYTE_SERVER_HOST = Optional.ofNullable(System.getenv("AIRBYTE_SERVER_HOST")).orElse("http://localhost:8001");

  @BeforeAll
  static void init() throws ApiException, URISyntaxException, IOException, InterruptedException {
    final URI url = new URI(AIRBYTE_SERVER_HOST);
    final var apiClient = new AirbyteApiClient(
        new ApiClient().setScheme(url.getScheme())
            .setHost(url.getHost())
            .setPort(url.getPort())
            .setBasePath("/api"));
    final var webBackendApi = new WebBackendApi(
        new ApiClient().setScheme(url.getScheme())
            .setHost(url.getHost())
            .setPort(url.getPort())
            .setBasePath("/api"));
    // work in whatever default workspace is present.
    workspaceId = apiClient.getWorkspaceApi().listWorkspaces().getWorkspaces().get(0).getWorkspaceId();
    LOGGER.info("workspaceId = " + workspaceId);

    // log which connectors are being used.
    final SourceDefinitionRead sourceDef = apiClient.getSourceDefinitionApi()
        .getSourceDefinition(new SourceDefinitionIdRequestBody()
            .sourceDefinitionId(UUID.fromString("decd338e-5647-4c0b-adf4-da0e75f5a750")));
    final DestinationDefinitionRead destinationDef = apiClient.getDestinationDefinitionApi()
        .getDestinationDefinition(new DestinationDefinitionIdRequestBody()
            .destinationDefinitionId(UUID.fromString("25c5221d-dce2-4163-ade9-739ef790f503")));
    LOGGER.info("pg source definition: {}", sourceDef.getDockerImageTag());
    LOGGER.info("pg destination definition: {}", destinationDef.getDockerImageTag());
    testHarness = new AcceptanceTestHarness(apiClient, webBackendApi, workspaceId, POSTGRES_INIT_SQL_FILE);
  }

  @BeforeEach
  void setup() throws URISyntaxException, IOException, InterruptedException, SQLException, ApiException {
    testHarness.setup();
  }

  @AfterEach
  void end() {
    testHarness.cleanup();
    testHarness.stopDbAndContainers();
  }

  @Test
  void testIncrementalCdcSync(final TestInfo testInfo) throws Exception {
    LOGGER.info(STARTING, testInfo.getDisplayName());

    final var conn = createCdcConnection();
    final var connectionId = conn.getConnectionId();
    final var outputSchema = conn.getNamespaceFormat();
    LOGGER.info(STARTING_SYNC_ONE, testInfo.getDisplayName());

    final JobInfoRead connectionSyncRead1 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead1.getJob());
    LOGGER.info("state after sync 1: {}", testHarness.getConnectionState(connectionId));

    final Database source = testHarness.getSourceDatabase();

    List<DestinationCdcRecordMatcher> expectedIdAndNameRecords = getCdcRecordMatchersFromSource(source, ID_AND_NAME_TABLE);
    assertDestinationMatches(testHarness.getDestinationDatabase(), outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);

    List<DestinationCdcRecordMatcher> expectedColorPaletteRecords = getCdcRecordMatchersFromSource(source, COLOR_PALETTE_TABLE);
    assertDestinationMatches(testHarness.getDestinationDatabase(), outputSchema, COLOR_PALETTE_TABLE, expectedColorPaletteRecords);

    final List<StreamDescriptor> expectedStreams = List.of(
        new StreamDescriptor().namespace(SCHEMA_NAME).name(ID_AND_NAME_TABLE),
        new StreamDescriptor().namespace(SCHEMA_NAME).name(COLOR_PALETTE_TABLE));
    assertGlobalStateContainsStreams(connectionId, expectedStreams);

    final Instant beforeFirstUpdate = Instant.now();

    LOGGER.info("Inserting and updating source db records");
    // add new records and run again.
    // add a new record
    source.query(ctx -> ctx.execute("INSERT INTO id_and_name(id, name) VALUES(6, 'geralt')"));
    // mutate a record that was already synced without updating its cursor value.
    // since this is a CDC connection, the destination should contain a record with this
    // new value and an updated_at time corresponding to this update query
    source.query(ctx -> ctx.execute("UPDATE id_and_name SET name='yennefer' WHERE id=2"));
    expectedIdAndNameRecords.add(new DestinationCdcRecordMatcher(
        Jsons.jsonNode(ImmutableMap.builder().put(COLUMN_ID, 6).put(COLUMN_NAME, "geralt").build()),
        beforeFirstUpdate,
        Optional.empty()));
    expectedIdAndNameRecords.add(new DestinationCdcRecordMatcher(
        Jsons.jsonNode(ImmutableMap.builder().put(COLUMN_ID, 2).put(COLUMN_NAME, "yennefer").build()),
        beforeFirstUpdate,
        Optional.empty()));

    // do the same for the other table
    source.query(ctx -> ctx.execute("INSERT INTO color_palette(id, color) VALUES(4, 'yellow')"));
    source.query(ctx -> ctx.execute("UPDATE color_palette SET color='purple' WHERE id=2"));
    expectedColorPaletteRecords.add(new DestinationCdcRecordMatcher(
        Jsons.jsonNode(ImmutableMap.builder().put(COLUMN_ID, 4).put(COLUMN_COLOR, "yellow").build()),
        beforeFirstUpdate,
        Optional.empty()));
    expectedColorPaletteRecords.add(new DestinationCdcRecordMatcher(
        Jsons.jsonNode(ImmutableMap.builder().put(COLUMN_ID, 2).put(COLUMN_COLOR, "purple").build()),
        beforeFirstUpdate,
        Optional.empty()));

    LOGGER.info("Starting {} sync 2", testInfo.getDisplayName());
    final JobInfoRead connectionSyncRead2 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead2.getJob());
    LOGGER.info("state after sync 2: {}", testHarness.getConnectionState(connectionId));

    final var dst = testHarness.getDestinationDatabase();
    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, expectedColorPaletteRecords);
    assertGlobalStateContainsStreams(connectionId, expectedStreams);

    // reset back to no data.

    LOGGER.info("Starting {} reset", testInfo.getDisplayName());
    final JobInfoRead jobInfoRead = testHarness.resetConnection(connectionId);
    testHarness.waitForSuccessfulJob(jobInfoRead.getJob());
    LOGGER.info("state after reset: {}", testHarness.getConnectionState(connectionId));

    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, Collections.emptyList());
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, Collections.emptyList());
    assertNoState(connectionId);

    // sync one more time. verify it is the equivalent of a full refresh.
    LOGGER.info("Starting {} sync 3", testInfo.getDisplayName());
    final JobInfoRead connectionSyncRead3 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead3.getJob());
    LOGGER.info("state after sync 3: {}", testHarness.getConnectionState(connectionId));

    expectedIdAndNameRecords = getCdcRecordMatchersFromSource(source, ID_AND_NAME_TABLE);
    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);

    expectedColorPaletteRecords = getCdcRecordMatchersFromSource(source, COLOR_PALETTE_TABLE);
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, expectedColorPaletteRecords);

    assertGlobalStateContainsStreams(connectionId, expectedStreams);
  }

  @Test
  void testDeleteRecordCdcSync(final TestInfo testInfo) throws Exception {
    LOGGER.info(STARTING, testInfo.getDisplayName());

    final var conn = createCdcConnection();
    final var connectionId = conn.getConnectionId();
    final var outputSchema = conn.getNamespaceFormat();
    LOGGER.info(STARTING_SYNC_ONE, testInfo.getDisplayName());

    final JobInfoRead connectionSyncRead1 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead1.getJob());
    LOGGER.info("state after sync 1: {}", testHarness.getConnectionState(connectionId));

    final Database source = testHarness.getSourceDatabase();
    final Database dst = testHarness.getDestinationDatabase();
    final List<DestinationCdcRecordMatcher> expectedIdAndNameRecords = getCdcRecordMatchersFromSource(source, ID_AND_NAME_TABLE);
    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);

    final Instant beforeDelete = Instant.now();

    LOGGER.info("Deleting record");
    // delete a record
    source.query(ctx -> ctx.execute("DELETE FROM id_and_name WHERE id=1"));

    final Map<String, Object> deletedRecordMap = new HashMap<>();
    deletedRecordMap.put(COLUMN_ID, 1);
    deletedRecordMap.put(COLUMN_NAME, null);
    expectedIdAndNameRecords.add(new DestinationCdcRecordMatcher(
        Jsons.jsonNode(deletedRecordMap),
        beforeDelete,
        Optional.of(beforeDelete)));

    LOGGER.info("Starting {} sync 2", testInfo.getDisplayName());
    final JobInfoRead connectionSyncRead2 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead2.getJob());
    LOGGER.info("state after sync 2: {}", testHarness.getConnectionState(connectionId));

    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);
  }

  @Test
  void testPartialResetFromSchemaUpdate(final TestInfo testInfo) throws Exception {
    LOGGER.info(STARTING, testInfo.getDisplayName());

    final var conn = createCdcConnection();
    final var connectionId = conn.getConnectionId();
    final var outputSchema = conn.getNamespaceFormat();
    LOGGER.info(STARTING_SYNC_ONE, testInfo.getDisplayName());

    final JobInfoRead connectionSyncRead1 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead1.getJob());

    final Database source = testHarness.getSourceDatabase();
    final Database dst = testHarness.getDestinationDatabase();

    final List<DestinationCdcRecordMatcher> expectedIdAndNameRecords = getCdcRecordMatchersFromSource(source, ID_AND_NAME_TABLE);
    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);

    final List<DestinationCdcRecordMatcher> expectedColorPaletteRecords = getCdcRecordMatchersFromSource(source, COLOR_PALETTE_TABLE);
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, expectedColorPaletteRecords);

    final StreamDescriptor idAndNameStreamDescriptor = new StreamDescriptor().namespace(SCHEMA_NAME).name(ID_AND_NAME_TABLE);
    final StreamDescriptor colorPaletteStreamDescriptor = new StreamDescriptor().namespace(SCHEMA_NAME).name(COLOR_PALETTE_TABLE);
    assertGlobalStateContainsStreams(connectionId, List.of(idAndNameStreamDescriptor, colorPaletteStreamDescriptor));

    LOGGER.info("Removing color palette table");
    source.query(ctx -> ctx.dropTable(COLOR_PALETTE_TABLE).execute());

    LOGGER.info("Refreshing schema and updating connection");
    final ConnectionRead connectionRead = testHarness.getConnection(connectionId);
    final UUID sourceId =
        createCdcSource().getSourceId();
    final AirbyteCatalog refreshedCatalog = testHarness.discoverSourceSchema(sourceId);
    refreshedCatalog.getStreams().forEach(s -> s.getConfig().selected(true));
    LOGGER.info("Refreshed catalog: {}", refreshedCatalog);
    final WebBackendConnectionUpdate update = testHarness.getUpdateInput(connectionRead, refreshedCatalog, operationRead);
    testHarness.webBackendUpdateConnection(update);

    LOGGER.info("Waiting for sync job after update to complete");
    final JobRead syncFromTheUpdate = testHarness.waitUntilTheNextJobIsStarted(connectionId);
    testHarness.waitForSuccessfulJob(syncFromTheUpdate);

    // We do not check that the source and the dest are in sync here because removing a stream doesn't
    // delete its data in the destination
    assertGlobalStateContainsStreams(connectionId, List.of(idAndNameStreamDescriptor));
  }

  @Test
  void testPartialResetFromStreamSelection(final TestInfo testInfo) throws Exception {
    LOGGER.info(STARTING, testInfo.getDisplayName());

    final var conn = createCdcConnection();
    final var connectionId = conn.getConnectionId();
    final var outputSchema = conn.getNamespaceFormat();
    LOGGER.info(STARTING_SYNC_ONE, testInfo.getDisplayName());

    final JobInfoRead connectionSyncRead1 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead1.getJob());

    final Database source = testHarness.getSourceDatabase();
    final Database dst = testHarness.getDestinationDatabase();

    final List<DestinationCdcRecordMatcher> expectedIdAndNameRecords = getCdcRecordMatchersFromSource(source, ID_AND_NAME_TABLE);
    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);

    final List<DestinationCdcRecordMatcher> expectedColorPaletteRecords = getCdcRecordMatchersFromSource(source, COLOR_PALETTE_TABLE);
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, expectedColorPaletteRecords);

    final StreamDescriptor idAndNameStreamDescriptor = new StreamDescriptor().namespace(SCHEMA_NAME).name(ID_AND_NAME_TABLE);
    final StreamDescriptor colorPaletteStreamDescriptor = new StreamDescriptor().namespace(SCHEMA_NAME).name(COLOR_PALETTE_TABLE);
    assertGlobalStateContainsStreams(connectionId, List.of(idAndNameStreamDescriptor, colorPaletteStreamDescriptor));

    LOGGER.info("Removing color palette stream from configured catalog");
    final ConnectionRead connectionRead = testHarness.getConnection(connectionId);
    final UUID sourceId = connectionRead.getSourceId();
    AirbyteCatalog catalog = testHarness.discoverSourceSchema(sourceId);
    catalog.getStreams().forEach(s -> s.getConfig().selected(true));
    final List<AirbyteStreamAndConfiguration> streams = catalog.getStreams();
    // filter out color_palette stream
    final List<AirbyteStreamAndConfiguration> updatedStreams = streams
        .stream()
        .filter(stream -> !COLOR_PALETTE_TABLE.equals(stream.getStream().getName()))
        .toList();
    catalog.setStreams(updatedStreams);
    LOGGER.info("Updated catalog: {}", catalog);
    WebBackendConnectionUpdate update = testHarness.getUpdateInput(connectionRead, catalog, operationRead);
    testHarness.webBackendUpdateConnection(update);

    LOGGER.info("Waiting for sync job after update to start");
    JobRead syncFromTheUpdate = testHarness.waitUntilTheNextJobIsStarted(connectionId);
    LOGGER.info("Waiting for sync job after update to complete");
    testHarness.waitForSuccessfulJob(syncFromTheUpdate);

    // We do not check that the source and the dest are in sync here because removing a stream doesn't
    // delete its data in the destination
    assertGlobalStateContainsStreams(connectionId, List.of(idAndNameStreamDescriptor));

    LOGGER.info("Adding color palette stream back to configured catalog");
    catalog = testHarness.discoverSourceSchema(sourceId);
    catalog.getStreams().forEach(s -> s.getConfig().selected(true));
    LOGGER.info("Updated catalog: {}", catalog);
    update = testHarness.getUpdateInput(connectionRead, catalog, operationRead);
    testHarness.webBackendUpdateConnection(update);

    LOGGER.info("Waiting for sync job after update to start");
    syncFromTheUpdate = testHarness.waitUntilTheNextJobIsStarted(connectionId);
    LOGGER.info("Checking that id_and_name table is unaffected by the partial reset");
    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);
    LOGGER.info("Checking that color_palette table was cleared in the destination due to the reset triggered by the update");
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, List.of());
    LOGGER.info("Waiting for sync job after update to complete");
    testHarness.waitForSuccessfulJob(syncFromTheUpdate);

    // Verify that color palette table records exist in destination again after sync.
    // If we see 0 records for this table in the destination, that means the CDC partial reset logic is
    // not working properly, and it continued from the replication log cursor for this stream despite
    // this stream's state being reset
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, expectedColorPaletteRecords);
    assertGlobalStateContainsStreams(connectionId, List.of(idAndNameStreamDescriptor, colorPaletteStreamDescriptor));

    // Verify that incremental still works properly after partial reset
    LOGGER.info("Adding new records to tables");
    final Instant beforeInsert = Instant.now();
    source.query(ctx -> ctx.execute("INSERT INTO id_and_name(id, name) VALUES(6, 'geralt')"));
    expectedIdAndNameRecords.add(new DestinationCdcRecordMatcher(
        Jsons.jsonNode(ImmutableMap.builder().put(COLUMN_ID, 6).put(COLUMN_NAME, "geralt").build()),
        beforeInsert,
        Optional.empty()));

    source.query(ctx -> ctx.execute("INSERT INTO color_palette(id, color) VALUES(4, 'yellow')"));
    expectedColorPaletteRecords.add(new DestinationCdcRecordMatcher(
        Jsons.jsonNode(ImmutableMap.builder().put(COLUMN_ID, 4).put(COLUMN_COLOR, "yellow").build()),
        beforeInsert,
        Optional.empty()));

    LOGGER.info("Starting {} sync after insert", testInfo.getDisplayName());
    final JobInfoRead connectionSyncRead2 = testHarness.syncConnection(connectionId);
    testHarness.waitForSuccessfulJob(connectionSyncRead2.getJob());

    assertDestinationMatches(dst, outputSchema, ID_AND_NAME_TABLE, expectedIdAndNameRecords);
    assertDestinationMatches(dst, outputSchema, COLOR_PALETTE_TABLE, expectedColorPaletteRecords);
    assertGlobalStateContainsStreams(connectionId, List.of(idAndNameStreamDescriptor, colorPaletteStreamDescriptor));
  }

  private List<DestinationCdcRecordMatcher> getCdcRecordMatchersFromSource(final Database source, final String tableName) throws SQLException {
    final List<JsonNode> sourceRecords = testHarness.retrieveRecordsFromDatabase(source, tableName);
    return new ArrayList<>(sourceRecords
        .stream()
        .map(sourceRecord -> new DestinationCdcRecordMatcher(sourceRecord, Instant.EPOCH, Optional.empty()))
        .toList());
  }

  private ConnectionRead createCdcConnection() throws Exception {
    final SourceRead sourceRead = createCdcSource();
    final UUID sourceId = sourceRead.getSourceId();
    final UUID destinationId = testHarness.createPostgresDestination().getDestinationId();

    operationRead = testHarness.createNormalizationOperation();
    final UUID normalizationOpId = operationRead.getOperationId();
    final SourceDiscoverSchemaRead discoverResult = testHarness.discoverSourceSchemaWithId(sourceId);
    final AirbyteCatalog catalog = discoverResult.getCatalog();
    final AirbyteStream stream = catalog.getStreams().get(0).getStream();
    LOGGER.info("stream: {}", stream);

    assertEquals(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL), stream.getSupportedSyncModes());
    assertTrue(stream.getSourceDefinedCursor(), "missing source defined cursor");
    assertEquals(List.of(POSTGRES_DEFAULT_CDC_CURSOR), stream.getDefaultCursorField());
    assertEquals(List.of(List.of("id")), stream.getSourceDefinedPrimaryKey());

    final SyncMode syncMode = SyncMode.INCREMENTAL;
    final DestinationSyncMode destinationSyncMode = DestinationSyncMode.APPEND;
    catalog.getStreams().forEach(s -> s.getConfig()
        .syncMode(syncMode)
        .selected(true)
        .cursorField(List.of(COLUMN_ID))
        .destinationSyncMode(destinationSyncMode));
    return testHarness.createConnection(new TestConnectionCreate.Builder(
        sourceId,
        destinationId,
        catalog,
        discoverResult.getCatalogId())
            .setNormalizationOperationId(normalizationOpId)
            .build());
  }

  @SuppressWarnings("unchecked")
  private SourceRead createCdcSource() {
    final UUID postgresSourceDefinitionId = testHarness.getPostgresSourceDefinitionId();
    final JsonNode sourceDbConfig = testHarness.getSourceDbConfig();
    final Map<Object, Object> sourceDbConfigMap = Jsons.object(sourceDbConfig, Map.class);
    sourceDbConfigMap.put("is_test", true);
    sourceDbConfigMap.put("replication_method", ImmutableMap.builder()
        .put("method", CDC_METHOD)
        .put("replication_slot", REPLICATION_SLOT)
        .put("publication", PUBLICATION)
        .put("initial_waiting_seconds", INITIAL_WAITING_SECONDS)
        .build());
    LOGGER.info("final sourceDbConfigMap: {}", sourceDbConfigMap);

    return testHarness.createSource(
        SOURCE_NAME,
        workspaceId,
        postgresSourceDefinitionId,
        Jsons.jsonNode(sourceDbConfigMap));
  }

  @SuppressWarnings({"PMD.AvoidLiteralsInIfCondition", "unchecked", "OptionalIsPresent"})
  private void assertDestinationMatches(final Database db,
                                        final String schema,
                                        final String streamName,
                                        final List<DestinationCdcRecordMatcher> expectedDestRecordMatchers)
      throws Exception {
    final List<JsonNode> destRecords = Databases.retrieveRawDestinationRecords(db, schema, streamName);
    if (destRecords.size() != expectedDestRecordMatchers.size()) {
      final String errorMessage = String.format(
          "The number of destination records %d does not match the expected number %d",
          destRecords.size(),
          expectedDestRecordMatchers.size());
      LOGGER.error(errorMessage);
      LOGGER.error("Expected dest record matchers: {}\nActual destination records: {}", expectedDestRecordMatchers, destRecords);
      throw new IllegalStateException(errorMessage);
    }

    for (final DestinationCdcRecordMatcher recordMatcher : expectedDestRecordMatchers) {
      final List<JsonNode> matchingDestRecords = destRecords.stream().filter(destRecord -> {
        final Map<String, Object> sourceRecordMap = Jsons.object(recordMatcher.sourceRecord, Map.class);
        final Map<String, Object> destRecordMap = Jsons.object(destRecord, Map.class);

        final boolean sourceRecordValuesMatch = sourceRecordMap.keySet()
            .stream()
            .allMatch(column -> Objects.equals(sourceRecordMap.get(column), destRecordMap.get(column)));

        final Object cdcUpdatedAtValue = destRecordMap.get(CDC_UPDATED_AT_COLUMN);
        // use epoch millis to guarantee the two values are at the same precision
        final boolean cdcUpdatedAtMatches = cdcUpdatedAtValue != null
            && Instant.parse(String.valueOf(cdcUpdatedAtValue)).toEpochMilli() >= recordMatcher.minUpdatedAt.toEpochMilli();

        final Object cdcDeletedAtValue = destRecordMap.get(CDC_DELETED_AT_COLUMN);
        final boolean cdcDeletedAtMatches;
        if (recordMatcher.minDeletedAt.isPresent()) {
          cdcDeletedAtMatches = cdcDeletedAtValue != null
              && Instant.parse(String.valueOf(cdcDeletedAtValue)).toEpochMilli() >= recordMatcher.minDeletedAt.get().toEpochMilli();
        } else {
          cdcDeletedAtMatches = cdcDeletedAtValue == null;
        }

        return sourceRecordValuesMatch && cdcUpdatedAtMatches && cdcDeletedAtMatches;
      }).toList();

      if (matchingDestRecords.isEmpty()) {
        throw new IllegalStateException(String.format(
            "Could not find a matching CDC destination record for record matcher %s. Destination records: %s", recordMatcher, destRecords));
      }
      if (matchingDestRecords.size() > 1) {
        throw new IllegalStateException(String.format(
            "Expected only one matching CDC destination record for record matcher %s, but found multiple: %s", recordMatcher, matchingDestRecords));
      }
    }
  }

  private void assertGlobalStateContainsStreams(final UUID connectionId, final List<StreamDescriptor> expectedStreams) throws Exception {
    final ConnectionState state = testHarness.getConnectionState(connectionId);
    LOGGER.info("state: {}", state);
    assertEquals(ConnectionStateType.GLOBAL, state.getStateType());
    final List<StreamDescriptor> stateStreams = state.getGlobalState().getStreamStates().stream().map(StreamState::getStreamDescriptor).toList();

    Assertions.assertTrue(stateStreams.containsAll(expectedStreams) && expectedStreams.containsAll(stateStreams),
        String.format("Expected state to have streams %s, but it actually had streams %s", expectedStreams, stateStreams));
  }

  private void assertNoState(final UUID connectionId) throws Exception {
    final ConnectionState state = testHarness.getConnectionState(connectionId);
    assertEquals(ConnectionStateType.NOT_SET, state.getStateType());
    assertNull(state.getState());
    assertNull(state.getStreamState());
    assertNull(state.getGlobalState());
  }

  // can be helpful for debugging
  @SuppressWarnings({"PMD.UnusedPrivateMethod", "unused"})
  private void printDbs() throws SQLException {
    final Database sourceDb = testHarness.getSourceDatabase();
    Set<SchemaTableNamePair> pairs = Databases.listAllTables(sourceDb);
    LOGGER.info("Printing source tables");
    for (final SchemaTableNamePair pair : pairs) {
      final Result<Record> result = sourceDb.query(
          context -> context.fetch(String.format("SELECT * FROM %s.%s", pair.schemaName(), pair.tableName())));
      LOGGER.info("{}.{} contents:\n{}", pair.schemaName(), pair.tableName(), result);
    }

    final Database destDb = testHarness.getDestinationDatabase();
    pairs = Databases.listAllTables(destDb);
    LOGGER.info("Printing destination tables");
    for (final SchemaTableNamePair pair : pairs) {
      final Result<Record> result = destDb.query(context -> context.fetch(String.format("SELECT * FROM %s.%s", pair.schemaName(), pair.tableName())));
      LOGGER.info("{}.{} contents:\n{}", pair.schemaName(), pair.tableName(), result);
    }
  }

}
