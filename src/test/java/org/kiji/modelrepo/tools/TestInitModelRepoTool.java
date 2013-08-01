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

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;

import org.kiji.modelrepo.KijiModelRepository;
import org.kiji.schema.Kiji;
import org.kiji.schema.avro.TableLayoutDesc;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.layout.KijiTableLayouts;
import org.kiji.schema.tools.BaseTool;
import org.kiji.schema.tools.KijiToolTest;

public class TestInitModelRepoTool extends KijiToolTest {

  @Test
  public void testShouldInstallModelRepo() throws Exception {
    final Kiji localKiji = getKiji();
    final String baseRepoUrl = "http://someHost:1234/releases";
    final String kijiArg = String.format("--kiji=%s", localKiji.getURI().toString());
    final int returnCode = runTool(new InitModelRepoTool(), kijiArg, baseRepoUrl);
    Assert.assertTrue(localKiji.getTableNames()
        .contains(KijiModelRepository.MODEL_REPO_TABLE_NAME));
    Assert.assertEquals(BaseTool.SUCCESS, returnCode);
  }

  @Test
  public void testShouldFailIfTableExists() throws Exception {
    final Kiji localKiji = getKiji();
    final KijiTableLayout layout = KijiTableLayouts.getTableLayout(KijiTableLayouts.FOO_TEST);
    final TableLayoutDesc desc = layout.getDesc();
    desc.setName(KijiModelRepository.MODEL_REPO_TABLE_NAME);
    localKiji.createTable(desc);
    final String baseRepoUrl = "http://someHost:1234/releases";
    final String kijiArg = String.format("--kiji=%s", localKiji.getURI().toString());
    try {
      runTool(new InitModelRepoTool(), kijiArg, baseRepoUrl);
      Assert.fail("Installation succeeded when it should have failed.");
    } catch (IOException ioe) {
    }

    Assert.assertTrue(localKiji.getTableNames()
        .contains(KijiModelRepository.MODEL_REPO_TABLE_NAME));
  }

  @Test
  public void testShouldWorkIfTryingToInstallTwice() throws Exception {
    testShouldInstallModelRepo();
    testShouldInstallModelRepo();
  }
}
