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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.List;

import com.google.common.collect.Lists;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.modelrepo.KijiModelRepository;
import org.kiji.modelrepo.TestUtils;
import org.kiji.schema.KijiClientTest;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiURI;
import org.kiji.schema.layout.KijiTableLayouts;
import org.kiji.schema.tools.BaseTool;
import org.kiji.scoring.KijiFreshnessManager;
import org.kiji.scoring.lib.server.ScoringServerScoreFunction;

public class TestFreshenerModelRepoTool extends KijiClientTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestFreshenerModelRepoTool.class);

  /** Horizontal ruler to delimit CLI outputs in logs. */
  private static final String RULER =
      "--------------------------------------------------------------------------------";

  /** Output of the CLI tool, as bytes. */
  private ByteArrayOutputStream mToolOutputBytes = new ByteArrayOutputStream();

  /** Output of the CLI tool, as a single string. */
  private String mToolOutputStr;

  /** Output of the CLI tool, as an array of lines. */
  private String[] mToolOutputLines;

  private int runTool(BaseTool tool, String...arguments) throws Exception {
    mToolOutputBytes.reset();
    final PrintStream pstream = new PrintStream(mToolOutputBytes);
    tool.setPrintStream(pstream);
    tool.setConf(getConf());
    try {
      LOG.info("Running tool: '{}' with parameters {}", tool.getName(), arguments);
      return tool.toolMain(Lists.newArrayList(arguments));
    } finally {
      pstream.flush();
      pstream.close();

      mToolOutputStr = Bytes.toString(mToolOutputBytes.toByteArray());
      LOG.info("Captured output for tool: '{}' with parameters {}:\n{}\n{}{}\n",
          tool.getName(), arguments,
          RULER, mToolOutputStr, RULER);
      mToolOutputLines = mToolOutputStr.split("\n");
    }
  }

  //------------------------------------------------------------------------------------------------

  private static final String MODEL_CONTAINER_TEMPLATE =
        "{"
      + "  \"model_name\": \"name\","
      + "  \"model_version\": \"1.0.0\","
      + "  \"scoring_parameters\": {"
      + "    \"score_function_class\": \"foo.bar.ScoreFn\","
      + "    \"parameters\": {},"
      + "    \"table_uri\": \"%s\","
      + "    \"attached_column\": \"%s\""
      + "  },"
      + "  \"record_version\": \"model_container-0.1.0\""
      + "}";

  private File buildModelContainerJson(
      final String table,
      final String column
  ) throws IOException {
    final String json = String.format(MODEL_CONTAINER_TEMPLATE,
        KijiURI.newBuilder(getKiji().getURI()).withTableName(table).build(), column);
    final File tempFile = new File(getLocalTempDir(), "model_container.json");
    assertTrue(tempFile.createNewFile());
    final PrintWriter pw = new PrintWriter(new FileWriter(tempFile));
    try {
      pw.print(json);
    } finally {
      pw.close();
    }
    return tempFile;
  }

  private List<String> getBaselineArgs() throws IOException {
    final File containerFile = buildModelContainerJson("table", "info:out");
    return Lists.newArrayList(
        "--model-container=" + containerFile.getAbsolutePath(),
        "--message=Uploading Artifact",
        "--kiji=" + getKiji().getURI().toString());
  }

  private static String makeDependencyString(List<File> inputDeps) {
    StringBuilder builder = new StringBuilder();
    for (File f : inputDeps) {
      builder.append(f.getAbsolutePath());
      builder.append(":");
    }
    return builder.toString();
  }

  private static final String TABLE_LAYOUT = "org/kiji/modelrepo/layouts/table.json";

  @Before
  public void setup() throws Exception {
    KijiModelRepository.install(getKiji(), getLocalTempDir().toURI());
    getKiji().createTable(KijiTableLayouts.getLayout(TABLE_LAYOUT));

    final List<File> dependencies = TestUtils.getDependencies(5);
    final File artifactJar = TestUtils.createFakeJar("artifact");
    final String artifactName = "org.kiji.test.sample_model";
    final List<String> args = Lists.newArrayList();
    args.add(artifactName);
    args.add(artifactJar.getAbsolutePath());
    args.add("--deps=" + makeDependencyString(dependencies));
    args.add("--deps-resolver=raw");
    args.add("--production-ready");
    args.addAll(getBaselineArgs());
    runTool(new DeployModelRepoTool(), args.toArray(new String[8]));

    // Fake the scoring server base URL because it is irrelevant.
    getKiji().getSystemTable().putValue(
        ScoringServerScoreFunction.SCORING_SERVER_BASE_URL_SYSTEM_KEY,
        new byte[1]);
  }

  @Test
  public void testAttach() throws Exception {
    assertEquals(BaseTool.SUCCESS, runTool(new FreshenerModelRepoTool(),
        getKiji().getURI().toString(),
        "org.kiji.test.sample_model-0.0.1",
        "org.kiji.scoring.lib.AlwaysFreshen"));

    final KijiFreshnessManager manager = KijiFreshnessManager.create(getKiji());
    try {
      assertNotNull(manager.retrieveFreshenerRecord("table", new KijiColumnName("info:out")));
    } finally {
      manager.close();
    }
  }
}
