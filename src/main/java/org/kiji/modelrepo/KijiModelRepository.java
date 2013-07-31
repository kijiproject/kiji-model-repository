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
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.schema.Kiji;
import org.kiji.schema.KijiMetaTable;
import org.kiji.schema.KijiTable;
import org.kiji.schema.avro.TableLayoutDesc;
import org.kiji.schema.layout.KijiTableLayout;

/**
 *
 * A class providing an API to install and access the model repository Kiji table.
 *
 * Used by CLI tools to read and write to the model repository along with other downstream
 * clients such as the scoring server to fetch information from the repository.
 *
 */
public final class KijiModelRepository implements Closeable {

  /** The default table name to use for housing model lifecycle metadata information. **/
  public static final String MODEL_REPO_TABLE_NAME = "model_repo";

  /** The path to the layout for the table in our resources. */
  private static final String TABLE_LAYOUT_BASE_PKG = "/org/kiji/modelrepo/layouts";

  /** The HBaseKijiTable managed by the KijiModelRepository. */
  private final KijiTable mKijiTable;

  /** The associated Kiji metatable associated with the KijiModelRepository. */
  private KijiMetaTable mKijiMetaTable;

  private int mCurrentModelRepoVersion = 0;

  /**
   * The latest layout version. Defined by looking at all the
   * json files org.kiji.modelrepo.layouts
   **/
  private static int mLatestLayoutVersion = 0;

  /** The latest layout (by id) org.kiji.modelrepo.layouts/*.json. **/
  private static KijiTableLayout mLatestLayout = null;

  private static final String REPO_VERSION_KEY = "kiji.model_repo.version";
  private static final String REPO_BASE_URL_KEY = "kiji.model_repo.base_repo_url";

  private static final String REPO_LAYOUT_VERSION_PREFIX="MR-";

  private static final Logger LOG = LoggerFactory.getLogger(KijiModelRepository.class);

  static {
    /*
     * Go through all the JSON files in org/kiji/modelrepo/layouts and store each
     */
    try {
      final URL packageUrl = KijiModelRepository.class.getResource(TABLE_LAYOUT_BASE_PKG);
      if (packageUrl != null) {
        final File fParent = new File(packageUrl.toURI());
        for (File layoutFile : fParent.listFiles(new JsonFileFilter())) {
          final FileInputStream fis = new FileInputStream(layoutFile);
          final KijiTableLayout layout =
              KijiTableLayout.createFromEffectiveJson(fis);
          String tableLayoutId = layout.getDesc().getLayoutId();
          String modelRepoLayoutVersion = tableLayoutId
              .substring(REPO_LAYOUT_VERSION_PREFIX.length());
          final int modelRepoLayoutId = Integer.parseInt(modelRepoLayoutVersion);
          if (modelRepoLayoutId > mLatestLayoutVersion) {
            mLatestLayoutVersion = modelRepoLayoutId;
            mLatestLayout = layout;
          }
        }
      }
    } catch (IOException ioe) {
      LOG.error("Error opening file ", ioe);
    } catch (URISyntaxException use) {
      LOG.error("Error opening file ", use);
    }
  }

  /**
   * Simple FileFilter that filters for JSON files.
   *
   */
  private static class JsonFileFilter implements FilenameFilter {

    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(".json");
    }
  }

  /**
   * Opens a KijiModelRepository for a given kiji using the default model repository table name.
   * This method should be matched with a call to {@link #close}.
   *
   * @param kiji The kiji instance to use.
   * @return An opened KijiModelRepository.
   * @throws IOException If there is an error opening the table or the table is not a valid
   *         model repository table.
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
    if (!isModelRepoTable(kiji)) {
      throw new IOException(MODEL_REPO_TABLE_NAME + " is not a valid model repository table.");
    }

    mKijiTable = kiji.openTable(MODEL_REPO_TABLE_NAME);
    mKijiMetaTable = kiji.getMetaTable();

    mCurrentModelRepoVersion = getCurrentVersion(mKijiMetaTable);
  }

  @Override
  public void close() throws IOException {
    mKijiTable.release();
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
    } else if (isModelRepoTable(kiji)) {
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
   * Retrieves the latest model repository version.
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
    if(fromVersion != mLatestLayoutVersion) {
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

    if (mLatestLayout == null) {
      throw new IOException("Unable to upgrade. Latest layout information is null.");
    }

    if (isModelRepoTable(kiji)) {
      int fromVersion = getCurrentVersion(kiji.getMetaTable());
      doUpgrade(kiji, fromVersion);
    } else {
      LOG.error(MODEL_REPO_TABLE_NAME + " is not a valid model repository table.");
    }
  }

  /**
   * Removes the model repository.
   *
   * @param kiji is the instance in which the repo resides.
   */
  public static void remove(Kiji kiji) {

  }

  /**
   * Determines whether or not the model repository table is a valid table (For safety).
   *
   * @param kiji is the Kiji instance in which the model repository resides.
   * @return whether or not the model repository table is valid.
   * @throws IOException if there is an exception reading any information from the Kiji
   *         instance or the metadata table.
   */
  private static boolean isModelRepoTable(Kiji kiji) throws IOException {
    // Checks the instance metadata table for the model repo keys and that the kiji instance has
    // a model repository.
    boolean isModelRepo = kiji.getTableNames().contains(MODEL_REPO_TABLE_NAME);

    try
    {
      kiji.getMetaTable().getValue(MODEL_REPO_TABLE_NAME, REPO_BASE_URL_KEY);
      kiji.getMetaTable().getValue(MODEL_REPO_TABLE_NAME, REPO_VERSION_KEY);
      isModelRepo = isModelRepo && true;
    } catch(IOException ioe) {
      // Means that the key doesn't exist (or something else bad happened).
      // TODO: Once SCHEMA-507 is patched to return null on getValue() not existing, then
      // we can change this OR if an exists() method is added on the MetaTable intf.
      isModelRepo = false;
    }

    return isModelRepo;
  }
}
