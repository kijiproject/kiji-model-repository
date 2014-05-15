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

package org.kiji.modelrepo.tools;

import static org.junit.Assert.assertEquals;

import java.io.File;

import com.google.common.io.Files;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.kiji.modelrepo.KijiModelRepository;
import org.kiji.modelrepo.ModelContainer;
import org.kiji.schema.EntityId;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.KijiTableWriter;
import org.kiji.schema.tools.BaseTool;
import org.kiji.schema.tools.KijiToolTest;

public class TestUpdateModelRepoTool extends KijiToolTest {

  private Kiji mKiji = null;
  private File mTempDir = null;

  @Before
  public void setupModelRepo() throws Exception {
    mKiji = createTestKiji();
    mTempDir = Files.createTempDir();
    mTempDir.deleteOnExit();
    KijiModelRepository.install(mKiji, mTempDir.toURI());
  }

  @Test
  public void testUpdateProductionFlag() throws Exception {
    // Set up model repository table manually.
    final KijiTable table = mKiji.openTable(KijiModelRepository.MODEL_REPO_TABLE_NAME);
    try {
      final KijiTableWriter writer = table.openTableWriter();
      try {
        KijiTableReader reader = table.openTableReader();
        try {
          final EntityId eid1 = table.getEntityId("org.kiji.fake.project", "1.0.0");
          writer.put(eid1, ModelContainer.MODEL_REPO_FAMILY,
              ModelContainer.PRODUCTION_READY_KEY, false);
          writer.put(eid1, ModelContainer.MODEL_REPO_FAMILY,
              ModelContainer.UPLOADED_KEY, true);

          // Before update.
          final KijiRowData row =
              reader.get(eid1, KijiDataRequest.create(ModelContainer.MODEL_REPO_FAMILY,
                  ModelContainer.PRODUCTION_READY_KEY));
          assertEquals(false, row.getMostRecentValue(ModelContainer.MODEL_REPO_FAMILY,
                  ModelContainer.PRODUCTION_READY_KEY));
          assertEquals(null, row.getMostRecentValue(ModelContainer.MODEL_REPO_FAMILY,
                  ModelContainer.MESSAGES_KEY));

          // Run update model repository.
          final int status = runTool(
              new UpdateModelRepoTool(),
              "--kiji=" + mKiji.getURI().toString(),
              "org.kiji.fake.project-1.0.0",
              "--message=Hello world");
          assertEquals(BaseTool.SUCCESS, status);

          // Confirm update.
          final KijiRowData updatedRow =
              reader.get(eid1, KijiDataRequest.create(ModelContainer.MODEL_REPO_FAMILY));
          assertEquals(
              true, updatedRow.getMostRecentValue(
                  ModelContainer.MODEL_REPO_FAMILY,
                  ModelContainer.PRODUCTION_READY_KEY));
          assertEquals(
              "Hello world", updatedRow.getMostRecentValue(
                  ModelContainer.MODEL_REPO_FAMILY,
                  ModelContainer.MESSAGES_KEY).toString());
        } finally {
          reader.close();
        }
      } finally {
        writer.close();
      }
    } finally {
      table.release();
    }
  }

  @Test
  public void testUpdateNonExistentModel() throws Exception {
    // Run update model repository.
    try {
      runTool(new UpdateModelRepoTool(), new String[] {
          "--kiji=" + mKiji.getURI().toString(),
          "org.kiji.fake.project-1.0.0", });
      Assert.fail("This update should have failed since model doesn't exist.");
    } catch (final Exception e) {
      assertEquals("Model org.kiji.fake.project-1.0.0 does not exist.",
          e.getMessage());
    }
  }
}
