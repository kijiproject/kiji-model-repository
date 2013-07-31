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
    final String kijiArg = String.format("--kiji=%s",localKiji.getURI().toString());
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
    final String kijiArg = String.format("--kiji=%s",localKiji.getURI().toString());
    try {
      runTool(new InitModelRepoTool(), kijiArg, baseRepoUrl);
      Assert.fail("Installation succeeded when it should have failed.");
    } catch(IOException ioe) {
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
