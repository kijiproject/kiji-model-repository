/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.modelrepo;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.modelrepo.avro.KijiModelContainer;
import org.kiji.modelrepo.packager.Packager;
import org.kiji.modelrepo.packager.WarPackager;
import org.kiji.modelrepo.uploader.ArtifactUploader;
import org.kiji.modelrepo.uploader.MavenArtifactUploader;
import org.kiji.schema.AtomicKijiPutter;
import org.kiji.schema.EntityId;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiDataRequestBuilder;
import org.kiji.schema.KijiDataRequestBuilder.ColumnsDef;
import org.kiji.schema.KijiDeleter;
import org.kiji.schema.KijiMetaTable;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.KijiRowScanner;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.KijiURI;
import org.kiji.schema.avro.TableLayoutDesc;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.util.ProtocolVersion;
/**
 *
 * A class providing an API to install and access the model repository Kiji table.
 *
 * Used by CLI tools to read and write to the model repository along with other downstream
 * clients such as the scoring server to fetch information from the repository.
 */
public final class KijiModelRepository implements Closeable {

  /** The default table name to use for housing model metadata information. **/
  public static final String MODEL_REPO_TABLE_NAME = "model_repo";

  /** Meta table key at which the ScoringServer base URL is stored. */
  public static final String SCORING_SERVER_URL_METATABLE_KEY =
      "kiji.model.repo.scoring_server_url";

  /** The path to the layout for the table in our resources. */
  private static final String TABLE_LAYOUT_BASE_PKG = "/org/kiji/modelrepo/layouts";

  /** The HBaseKijiTable managed by the KijiModelRepository. */
  private final KijiTable mKijiTable;

  private final int mCurrentModelRepoVersion;
  private final URI mCurrentBaseStorageURI;

  private final ArtifactUploader mUploader = new MavenArtifactUploader();

  private final Packager mArtifactPackager = new WarPackager();

  /**
   * The latest layout version. Defined by looking at all the
   * json files org.kiji.modelrepo.layouts
   **/
  private static int mLatestLayoutVersion = 0;

  /** The latest layout. **/
  private static KijiTableLayout mLatestLayout = null;

  private static final String REPO_VERSION_KEY = "kiji.model_repo.version";
  private static final String REPO_BASE_URL_KEY = "kiji.model_repo.base_repo_url";

  private static final String REPO_LAYOUT_VERSION_PREFIX = "MR-";

  private static final Logger LOG = LoggerFactory.getLogger(KijiModelRepository.class);

  /**
   * The latest layout file. This will have to be updated when we make a change to the
   * model repository table layout. Was hoping to inspect the contents of
   * org.kiji.modelrepo.layouts/*.json but doing this in a jar doesn't seem very straightforward.
   **/
  private static final String LATEST_LAYOUT_FILE =
      TABLE_LAYOUT_BASE_PKG + "/model-repo-layout-MR-1.json";

  static {
    try {
      mLatestLayout = KijiTableLayout
          .createFromEffectiveJson(KijiModelRepository
          .class.getResourceAsStream(LATEST_LAYOUT_FILE));
      String tableLayoutId = mLatestLayout.getDesc().getLayoutId();
      mLatestLayoutVersion = Integer.parseInt(tableLayoutId
          .substring(REPO_LAYOUT_VERSION_PREFIX.length()));
    } catch (IOException ioe) {
      LOG.error("Error opening file ", ioe);
    }
  }

  /**
   * Opens a KijiModelRepository for a given kiji using the default model repository table name.
   * This method should be matched with a call to {@link #close}.
   *
   * @param kiji The kiji instance to use.
   * @return An opened KijiModelRepository.
   * @throws IOException If there is an error opening the table or the table is not a valid
   *     model repository table.
   */
  public static KijiModelRepository open(Kiji kiji) throws IOException {
    return new KijiModelRepository(kiji);
  }

  /**
   * Private constructor that opens a new KijiModelRepository, creating it if necessary.
   * This method also updates an existing layout to the latest layout for the job
   * history table.
   *
   * @param kiji The kiji instance to retrieve the job history table from.
   * @throws IOException If there's an error opening the underlying HBaseKijiTable.
   */
  private KijiModelRepository(Kiji kiji) throws IOException {
    if (!hasModelRepoTable(kiji)) {
      throw new IOException(MODEL_REPO_TABLE_NAME + " is not a valid model repository table.");
    }

    mKijiTable = kiji.openTable(MODEL_REPO_TABLE_NAME);

    mCurrentBaseStorageURI = getCurrentBaseURI(kiji.getMetaTable());
    mCurrentModelRepoVersion = getCurrentVersion(kiji.getMetaTable());
  }

  @Override
  public void close() throws IOException {
    mKijiTable.release();
  }

  /**
   * Returns the URI of the model repository table.
   * @return the URI of the model repository table.
   */
  public KijiURI getURI() {
    return mKijiTable.getURI();
  }

  /**
   * Returns the current version of the model repository.
   *
   * @return the current version of the model repository.
   */
  public int getCurrentVersion() {
    return mCurrentModelRepoVersion;
  }

  /**
   * Install the model repository table into a Kiji instance. Will use the latest version
   * of the layouts as the table's layout.
   *
   * @param kiji is the Kiji instance to install this table in.
   * @param baseStorageURI is the base URI of the storage layer.
   * @throws IOException If there is an error.
   */
  public static void install(Kiji kiji, URI baseStorageURI) throws IOException {

    // Few possibilities:
    // 1) The tableName doesn't exist at all ==> Create new table and set meta information
    // 2) The tableName exists and is a model-repo table ==> Do nothing or upgrade.
    // 3) The tableName exists and is not a model-repo table ==> Error.

    if (mLatestLayout == null) {
      throw new IOException("Unable to upgrade. Latest layout information is null.");
    }

    if (!kiji.getTableNames().contains(MODEL_REPO_TABLE_NAME)) {
      TableLayoutDesc tableLayout = mLatestLayout.getDesc();
      tableLayout.setReferenceLayout(null);
      tableLayout.setName(MODEL_REPO_TABLE_NAME);
      kiji.createTable(tableLayout);

      // Set the version
      writeLatestVersion(kiji.getMetaTable());

      // Set the base URI
      kiji.getMetaTable().putValue(MODEL_REPO_TABLE_NAME, REPO_BASE_URL_KEY,
          baseStorageURI.toString().getBytes());
    } else if (hasModelRepoTable(kiji)) {
      // Do an upgrade possibly.
      int currentVersion = getCurrentVersion(kiji.getMetaTable());
      doUpgrade(kiji, currentVersion);
    } else {
      throw new IOException("Can not install model repository in table "
          + MODEL_REPO_TABLE_NAME + ".");
    }
  }

  /**
   * Write the latest version of the model repository into the Kiji metadata table.
   *
   * @param metaTable is the Kiji metadata table.
   * @throws IOException if there is an exception writing the latest layout to the metatable.
   */
  private static void writeLatestVersion(KijiMetaTable metaTable)
      throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(Integer.SIZE);
    buf.putInt(mLatestLayoutVersion);
    metaTable.putValue(MODEL_REPO_TABLE_NAME, REPO_VERSION_KEY, buf.array());
  }

  /**
   * Retrieves the model repository version.
   *
   * @param metaTable is the Kiji metadata table.
   * @return the current version of the model repository.
   * @throws IOException if there is an exception retrieving the current model repo version.
   */
  private static int getCurrentVersion(KijiMetaTable metaTable)
      throws IOException {
    ByteBuffer buf = ByteBuffer.wrap(metaTable.getValue(MODEL_REPO_TABLE_NAME, REPO_VERSION_KEY));
    return buf.getInt();
  }

  /**
   * Retrieves the model repository's base storage URI.
   *
   * @param metaTable is the Kiji metadata table.
   * @return the model repository's base storage URI.
   * @throws IOException if there is an exception retrieving the URI.
   */
  private static URI getCurrentBaseURI(KijiMetaTable metaTable) throws IOException {
    ByteBuffer buf = ByteBuffer.wrap(metaTable.getValue(MODEL_REPO_TABLE_NAME, REPO_BASE_URL_KEY));
    String uri = new String(buf.array());
    return URI.create(uri);
  }

  /**
   * Performs the upgrade of the model repository. Will apply the latest layout to the
   * model repository table.
   *
   * @param kiji is the kiji instance.
   * @param fromVersion is the previous version of the repository.
   * @throws IOException if there is an exception performing the upgrade.
   */
  private static void doUpgrade(Kiji kiji, int fromVersion)
      throws IOException {

    if (mLatestLayout == null) {
      throw new IOException("Unable to upgrade. Latest layout information is null.");
    }
    if (fromVersion != mLatestLayoutVersion) {
      // Apply the latest layout and set the reference layout to the previous known version.
      TableLayoutDesc newLayoutDesc = mLatestLayout.getDesc();
      newLayoutDesc.setName(MODEL_REPO_TABLE_NAME);
      newLayoutDesc.setReferenceLayout(REPO_LAYOUT_VERSION_PREFIX + Integer.toString(fromVersion));
      kiji.modifyTableLayout(newLayoutDesc);
      writeLatestVersion(kiji.getMetaTable());
    }
  }

  /**
   * Upgrade the model repository by applying the latest layout.
   *
   * @param kiji is the Kiji instance.
   * @throws IOException if there is an exception upgrading the model repository.
   */
  public static void upgrade(Kiji kiji) throws IOException {
    Preconditions.checkNotNull(mLatestLayout,
        "Unable to upgrade. Latest layout information is null.");

    if (hasModelRepoTable(kiji)) {
      int fromVersion = getCurrentVersion(kiji.getMetaTable());
      doUpgrade(kiji, fromVersion);
    } else {
      throw new IOException(MODEL_REPO_TABLE_NAME + " is not a valid model repository table.");
    }
  }

  /**
   * Deletes the model repository.
   *
   * @param kiji is the instance in which the repo resides.
   * @throws IOException if delete cannot be correctly performed.
   */
  public static void delete(Kiji kiji) throws IOException {

    // Similarly to the install tool, there are a few possibilities:
    // 1) The tableName doesn't exist at all ==> throw Exception.
    // 2) The tableName exists and is a model-repo table ==> delete table.
    // 3) The tableName exists and is not a model-repo table ==> throw Exception.

    if (!kiji.getTableNames().contains(MODEL_REPO_TABLE_NAME)) {
      throw new IOException("Model repository which is to be deleted "
          + MODEL_REPO_TABLE_NAME
          + " does not exist in instance "
          + kiji.getURI() + ".");
    } else if (hasModelRepoTable(kiji)) {
      LOG.info("Deleting model repository table...");
      kiji.deleteTable(MODEL_REPO_TABLE_NAME);
      // Remove metadata keys.
      LOG.info("Removing model repository keys from metadata table...");
      kiji.getMetaTable().removeValues(MODEL_REPO_TABLE_NAME, REPO_BASE_URL_KEY);
      kiji.getMetaTable().removeValues(MODEL_REPO_TABLE_NAME, REPO_VERSION_KEY);
    } else {
      throw new IOException(
          "Expected model repository table is not a valid model repository table "
              + MODEL_REPO_TABLE_NAME + ".");
    }
  }

  /**
   * Determines whether or not the model repository table is a valid table (For safety).
   *
   * @param kiji is the Kiji instance in which the model repository resides.
   * @return whether or not the model repository table is valid.
   * @throws IOException if there is an exception reading any information from the Kiji
   *         instance or the metadata table.
   */
  private static boolean hasModelRepoTable(Kiji kiji) throws IOException {
    // Checks the instance metadata table for the model repo keys and that the kiji instance has
    // a model repository.
    if (!kiji.getTableNames().contains(MODEL_REPO_TABLE_NAME)) {
      return false;
    }
    try {
      kiji.getMetaTable().getValue(MODEL_REPO_TABLE_NAME, REPO_BASE_URL_KEY);
      kiji.getMetaTable().getValue(MODEL_REPO_TABLE_NAME, REPO_VERSION_KEY);
      return true;
    } catch (IOException ioe) {
      // Means that the key doesn't exist (or something else bad happened).
      // TODO: Once SCHEMA-507 is patched to return null on getValue() not existing, then
      // we can change this OR if an exists() method is added on the MetaTable intf.
      if (ioe.getMessage() != null
          && ioe.getMessage().contains("Could not find any values associated with table")) {
        return false;
      } else {
        throw ioe;
      }
    }
  }

  /**
   * "Reserves" a new row by checking if the row exists.
   * If the row doesn't exist, then sets the "model:uploaded" field to false.
   *
   * @param artifact name of the model.
   * @throws IOException if the uploaded field for specified model name was not properly written.
   */
  private void reserveNewRow(final ArtifactName artifact) throws IOException {
    final EntityId eid = mKijiTable.getEntityId(artifact.getName(),
        artifact.getVersion().toCanonicalString());
    final AtomicKijiPutter putter = mKijiTable.getWriterFactory().openAtomicPutter();
    try {
      // Allocate and lock row by setting "model:uploaded" as false.
      putter.begin(eid);
      putter.put(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.UPLOADED_KEY, false);
      // Check that row doesn't exist (check uploaded cell) and commit.
      if (!putter.checkAndCommit(ModelContainer.MODEL_REPO_FAMILY,
          ModelContainer.UPLOADED_KEY,
          null)) {
        throw new IllegalArgumentException(
            String.format("Error Version %s exists.", artifact.getVersion().toCanonicalString()));
      }
    } finally {
      putter.close();
    }
  }

  /**
   * Writes to a reserved row in the model repository table.
   *
   * @param artifact name of the model.
   * @param modelContainer configuration.
   * @param location where the code artifact is uploaded.
   * @param productionReady is true iff model is ready for scoring
   * @param message (optional) latest update message of the model.
   * @throws IOException if the row was not written properly to the model repository table.
   */
  private void writeRow(
      final ArtifactName artifact,
      final KijiModelContainer modelContainer,
      final String location,
      final boolean productionReady,
      final String message) throws IOException {
    final AtomicKijiPutter putter = mKijiTable.getWriterFactory().openAtomicPutter();
    try {
      final EntityId eid = mKijiTable.getEntityId(artifact.getName(),
          artifact.getVersion().toCanonicalString());
      putter.begin(eid);
      putter.put(
          ModelContainer.MODEL_REPO_FAMILY,
          ModelContainer.MODEL_CONTAINER_KEY,
          modelContainer);
      putter.put(
          ModelContainer.MODEL_REPO_FAMILY,
          ModelContainer.LOCATION_KEY,
          location);
      putter.put(
          ModelContainer.MODEL_REPO_FAMILY,
          ModelContainer.PRODUCTION_READY_KEY,
          productionReady);
      if (null != message) {
        putter.put(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.MESSAGES_KEY, message);
      }
      // Enable this entry.
      putter.put(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.UPLOADED_KEY, true);
      // Check that the uploaded field is false and commit.
      putter.checkAndCommit(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.UPLOADED_KEY, false);
    } finally {
      putter.close();
    }
  }

  /**
   * Delete an entire row.
   *
   * @param artifact name of the model to delete.
   * @throws IOException if the deletion attempt was unsuccessful.
   */
  private void deleteRow(final ArtifactName artifact) throws IOException {
    final EntityId eid = mKijiTable.getEntityId(
        artifact.getName(),
        artifact.getVersion().toCanonicalString());
    final KijiDeleter deleter = mKijiTable.getWriterFactory().openTableWriter();
    deleter.deleteRow(eid);
    deleter.close();
  }

  /**
   * Deploys a new model by packaging and uploading the artifact file and dependencies.
   *
   * @param artifact name of the model being deployed.
   * @param artifactFile is the actual artifact to upload.
   * @param dependencies are the third-party dependencies to include in the artifact
   * @param modelContainer configuration
   * @param productionReady is true iff model is ready for scoring
   * @param message (optional) latest update message of the model
   * @throws IOException if model cannot be deployed.
   */
  // CSOFF: ParameterNumberCheck
  public void deployModelContainer(
      final ArtifactName artifact,
      final File artifactFile,
      final List<File> dependencies,
      final KijiModelContainer modelContainer,
      final boolean productionReady,
      final String message
  ) throws IOException {
    // CSON: ParameterNumberCheck

    // ArtifactFile and dependencies must be specified
    Preconditions.checkNotNull(artifactFile);
    Preconditions.checkNotNull(dependencies);

    // If the version is not specified, then fetch the latest version given the
    // artifact name. Increment by 0.0.1 ELSE check if row exists and if so, throw exception
    final ArtifactName versionedArtifact;
    if (!artifact.isVersionSpecified()) {
      versionedArtifact = new ArtifactName(artifact.getName(), fetchNextVersion(artifact));
    } else {
      versionedArtifact = artifact;
    }

    // Start assembling artifact file
    final File outputFile = File.createTempFile("final_artifact", ".war");
    outputFile.deleteOnExit();
    dependencies.add(artifactFile);
    mArtifactPackager.generateArtifact(outputFile, dependencies);

    // Reserve a new row.
    reserveNewRow(versionedArtifact);

    // Upload artifact.
    final String location;
    try {
      location = mUploader.uploadArtifact(versionedArtifact, mCurrentBaseStorageURI, outputFile);
    } catch (final IOException uploadIoe) {
      // Upon exception, erase previously written model repository entry.
      deleteRow(versionedArtifact);
      // Rethrow upload exception.
      throw uploadIoe;
    } finally {
      outputFile.delete();
    }

    // Put entry to the model repository table.
    writeRow(versionedArtifact, modelContainer, location, productionReady, message);
  }

  /**
   * Deploys a new model from existing artifact package.
   *
   * @param artifact name of the model being deployed.
   * @param sourceArtifact name of the the existing model
   *        whose package contains the code to associate with this deployment
   *        This is specified if artifactFile and dependencies are null.
   * @param modelContainer configuration.
   * @param productionReady is true iff model is ready for scoring.
   * @param message (optional) latest update message of the model.
   * @throws IOException if model cannot be deployed.
   */
  // CSOFF: ParameterNumberCheck
  public void deployModelContainer(
      final ArtifactName artifact,
      final ArtifactName sourceArtifact,
      final KijiModelContainer modelContainer,
      final boolean productionReady,
      final String message
  ) throws IOException {
    // CSON: ParameterNumberCheck

    // Source version must be specified.
    Preconditions.checkArgument(
        sourceArtifact.isVersionSpecified(),
        "Source artifact must specify version.");

    // If the version is not specified, then fetch the latest version given the
    // artifact name. Increment by 0.0.1 ELSE check if row exists and if so, throw exception
    final ArtifactName versionedArtifact;
    if (!artifact.isVersionSpecified()) {
      versionedArtifact = new ArtifactName(artifact.getName(), fetchNextVersion(artifact));
    } else {
      versionedArtifact = artifact;
    }

    // Reserve a new row.
    reserveNewRow(versionedArtifact);

    // Acquire appropriate existing artifact's location
    final ModelContainer existingArtifact = getModelContainer(sourceArtifact);
    Preconditions.checkNotNull(
        existingArtifact.getLocation(), String.format(
            "Could not ascertain model location field of specified existing artifact: %s",
            sourceArtifact));
    final String location = existingArtifact.getLocation();

    // Put entry to the model repository table.
    writeRow(versionedArtifact, modelContainer, location, productionReady, message);
  }

  /**
   * Update the production readiness flag of a model in the model repository table.
   * And sets a message.
   *
   * @param artifact name that identifies this model.
   * @param version of this particular instance of a model.
   * @param productionReady is true iff model is ready for scoring
   * @param message (optional) latest update message of the model
   * @throws IOException if model can not be updated
   */
  public void setProductionReady(
      final ArtifactName artifact,
      final ProtocolVersion version,
      final boolean productionReady,
      final String message) throws IOException {
    // Put entry to the model repository table.
    final EntityId eid = mKijiTable.getEntityId(
        artifact.getName(),
        artifact.getVersion().toCanonicalString());
    final AtomicKijiPutter putter = mKijiTable.getWriterFactory().openAtomicPutter();
    try {
      putter.begin(eid);
      putter.put(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.PRODUCTION_READY_KEY,
          productionReady);
      if (null != message) {
        putter.put(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.MESSAGES_KEY, message);
      }
      // Check that row exists and "model:uploaded" flag is true.
      if (!putter.checkAndCommit(ModelContainer.MODEL_REPO_FAMILY,
          ModelContainer.UPLOADED_KEY,
          true)) {
        throw new IllegalArgumentException(
            String.format("Model %s-%s does not exist.",
                artifact.getName(),
                artifact.getVersion().toCanonicalString()));
      }
    } finally {
      putter.close();
    }
  }

  /**
   * Fetches the next version of the given model by finding the last version
   * of the deployed model and adding 1 to the revision. For example, if the
   * last known version was 1.0.0 then the next version will be 1.0.1.
   *
   * @param artifact name of the model.
   * @throws IOException if there is a problem fetching version info.
   * @return the next version of the model given by the artifact name.
   */
  private ProtocolVersion fetchNextVersion(final ArtifactName artifact)
      throws IOException {

    final KijiTableReader reader = mKijiTable.openTableReader();
    try {
      final KijiRowScanner scanner = reader.getScanner(KijiDataRequest.create("model"));

      ProtocolVersion currentVersion = ProtocolVersion.parse("0.0.0");
      // Find the highest version.
      for (KijiRowData row : scanner) {
        // Consider only rows with entity IDs corresponding to the artifact in question.
        final String name = row.getEntityId().getComponentByIndex(0);
        if (!name.equals(artifact.getName())) {
          continue;
        }

        ProtocolVersion version = ProtocolVersion.parse(row.getEntityId()
            .getComponentByIndex(1).toString());
        if (version.compareTo(currentVersion) > 0) {
          currentVersion = version;
        }
      }
      scanner.close();

      return ProtocolVersion.
          parse(String.format("%d.%d.%d", currentVersion.getMajorVersion(),
              currentVersion.getMinorVersion(), currentVersion.getRevision() + 1));
    } finally {
      reader.close();
    }
  }

  /**
   * Returns a model from the model repository.
   *
   * @param artifact name of the model
   * @return a single artifact.
   * @throws IOException if there is an exception fetching data.
   */
  public ModelContainer getModelContainer(final ArtifactName artifact) throws IOException {
    return getModelContainer(artifact, null);
  }

  /**
   * Returns requested fields from the model from the model repository.
   *
   * @param artifact name of the model
   * @param fields requested by the user; null returns all fields
   * @return a single artifact.
   * @throws IOException if there is an exception fetching data.
   */
  public ModelContainer getModelContainer(
      final ArtifactName artifact,
      Set<String> fields
  ) throws IOException {
    if (null == fields) {
      fields = Sets.newHashSet(
          ModelContainer.MODEL_CONTAINER_KEY,
          ModelContainer.LOCATION_KEY,
          ModelContainer.PRODUCTION_READY_KEY,
          ModelContainer.MESSAGES_KEY);
    }
    final KijiDataRequest dataRequest = KijiDataRequest.create("model");
    final KijiTableReader reader = mKijiTable.openTableReader();
    try {
      final EntityId eid = mKijiTable.getEntityId(artifact.getName(),
          artifact.getVersion().toCanonicalString());
      final KijiRowData returnRow = reader.get(eid, dataRequest);
      return new ModelContainer(returnRow, fields, mCurrentBaseStorageURI);
    } finally {
      reader.close();
    }
  }

  /**
   * Returns rows corresponding to models that are uploaded.
   *
   * @param scanner over rows in the model repository table.
   * @return a list of rows corresponding to uploaded models.
   * @throws IOException if there is an error accessing the row scanner.
   */
  private static List<KijiRowData> getUploadedModels(
      KijiRowScanner scanner
  ) throws IOException {
    return getUploadedModels(scanner, false);
  }

  /**
   * Returns rows corresponding to models that are uploaded and (optionally) production-ready.
   *
   * @param scanner over rows in the model repository table.
   * @param productionReadyOnly if returned rows should correspond to only production-ready models.
   * @return a list of rows corresponding to uploaded (and optionally production-ready) models.
   * @throws IOException if there is an error accessing the row scanner.
   */
  private static List<KijiRowData> getUploadedModels(
      KijiRowScanner scanner,
      boolean productionReadyOnly
      ) throws IOException {
    final List<KijiRowData> validUploadedModels = Lists.newArrayList();
    for (KijiRowData row: scanner) {
      final Boolean isUploaded = row.getMostRecentValue(
          ModelContainer.MODEL_REPO_FAMILY, ModelContainer.UPLOADED_KEY);

      boolean isProduction = false;
      if (row.containsColumn(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.PRODUCTION_READY_KEY)
          && Boolean.TRUE == row.getMostRecentValue(
              ModelContainer.MODEL_REPO_FAMILY, ModelContainer.PRODUCTION_READY_KEY)) {
        isProduction = true;
      }

      if (isUploaded && (!productionReadyOnly || isProduction)) {
        validUploadedModels.add(row);
      }
    }
    return validUploadedModels;
  }

  /**
   * Check that every model in the model repository table is associated with a valid model location
   * in the model repository, i.e. that a valid model artifact is found at the model location.
   *
   * @param download set to true allows the method to download and validate the artifact file.
   * @return List of exceptions of inconsistent model locations.
   * @throws IOException if model repository table can not be properly read
   *         or if base model repository URL is malformed
   *         or if a temporary file can not be allocated for downloading model artifacts.
   */
  public List<Exception> checkModelLocations(final boolean download) throws IOException {
    final List<Exception> issues = Lists.newArrayList();

    // Read model repository table and validate each model location url.
    final KijiTableReader reader = mKijiTable.openTableReader();
    final KijiDataRequestBuilder dataRequestBuilder = KijiDataRequest.builder();
    dataRequestBuilder.addColumns(dataRequestBuilder
        .newColumnsDef()
        .withMaxVersions(Integer.MAX_VALUE)
        .add(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.LOCATION_KEY)
        .add(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.UPLOADED_KEY));
    // Filter out all incomplete models.
    final KijiRowScanner scanner = reader.getScanner(dataRequestBuilder.build());
    try {
      for (KijiRowData row : getUploadedModels(scanner)) {
        // Proper construction of ModelArtifact requires UPLOADED_KEY.
        final ModelContainer model = new ModelContainer(row,
            Sets.newHashSet(ModelContainer.LOCATION_KEY), mCurrentBaseStorageURI);
        issues.addAll(model.checkModelLocation(download));
      }
    } finally {
      scanner.close();
      reader.close();
    }
    return issues;
  }

  /**
   * Acquires a set of models containing requested fields from the model repository table.
   *
   * @param fields requested by the user; null returns all fields
   * @param maxVersions maximum versions of a cell to return
   * @param productionReadyOnly when true returns models whose latest production_ready flag is true.
   * @return a set of models from the model repository table.
   * @throws IOException when table can not be properly scanned.
   */
  public Set<ModelContainer> getModelContainers(
      Set<String> fields,
      final int maxVersions,
      final boolean productionReadyOnly
  ) throws IOException {
    Preconditions.checkArgument(maxVersions >= 0);

    final Set<ModelContainer> setOfModels = Sets.newLinkedHashSet();
    final KijiTableReader reader = mKijiTable.openTableReader();
    final KijiDataRequestBuilder dataRequestBuilder = KijiDataRequest.builder();
    // We want to get every existing model in the table.
    // Every model has an uploaded flag so request the model:uploaded column.
    final ColumnsDef columns = dataRequestBuilder
        .newColumnsDef()
        .withMaxVersions(maxVersions)
        .add(new KijiColumnName(ModelContainer.MODEL_REPO_FAMILY, ModelContainer.UPLOADED_KEY));
    // Add all other fields requested by the user.
    if (null == fields) {
      fields = Sets.newHashSet(
          ModelContainer.MODEL_CONTAINER_KEY,
          ModelContainer.LOCATION_KEY,
          ModelContainer.PRODUCTION_READY_KEY,
          ModelContainer.MESSAGES_KEY);
    }
    for (final String field : fields) {
      columns.add(new KijiColumnName(ModelContainer.MODEL_REPO_FAMILY, field));
    }
    dataRequestBuilder.addColumns(columns);

    // Gather all rows and emit.
    final KijiRowScanner scanner = reader.getScanner(dataRequestBuilder.build());
    try {
      for (final KijiRowData row : getUploadedModels(scanner, productionReadyOnly)) {
        setOfModels.add(new ModelContainer(row, fields, mCurrentBaseStorageURI));
      }
    } finally {
      scanner.close();
      reader.close();
    }
    return setOfModels;
  }
}
