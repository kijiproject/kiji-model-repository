/**
 * (c) Copyright 2014 WibiData, Inc.
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

import java.io.File;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.common.flags.Flag;
import org.kiji.modelrepo.ModelContainer;
import org.kiji.modelrepo.avro.KijiModelContainer;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiURI;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.tools.BaseTool;
import org.kiji.schema.util.ProtocolVersion;
import org.kiji.schema.util.ToJson;
import org.kiji.scoring.lib.JpmmlScoreFunction;

/**
 * Implementation of the pmml model repository tool.
 * This tool will create a model container for a Pmml model family.
 *
 * Create a new model container file from an existing PMML trained model:
 * <pre>
 *   kiji model-repo pmml \
 *       --table=kiji://.env/default/table \
 *       --model-file=file:///path/to/pmml \
 *       --model-name=pmmlModelName \
 *       --model-version=0.1.0 \
 *       --predictor-column=model:predictor \
 *       --result-column=model:result \
 *       --result-record-name=MyScore \
 *       --model-container=/home/me/new-model-container
 * </pre>
 *
 * Create a new model container file from an existing PMML trained model without validating input:
 * <pre>
 *   kiji model-repo pmml \
 *       --table=kiji://.env/default/table \
 *       --model-file=file:///path/to/pmml \
 *       --model-name=pmmlModelName \
 *       --model-version=0.1.0 \
 *       --predictor-column=model:predictor \
 *       --result-column=model:result \
 *       --result-record-name=MyScore \
 *       --model-container=/home/me/new-model-container \
 *       --validate=false
 * </pre>
 */
public final class PmmlModelRepoTool extends BaseTool implements KijiModelRepoTool {
  private static final Logger LOG = LoggerFactory.getLogger(PmmlModelRepoTool.class);

  @Flag(
      name = "table",
      usage = "URI of the Kiji table containing the desired model predictors and results."
  )
  private String mTableURIFlag = null;
  private KijiURI mTableURI = null;

  @Flag(
      name = "model-file",
      usage = "Path to the trained PMML model file."
  )
  private String mModelFileFlag = null;
  private Path mModelFile = null;

  @Flag(
      name = "model-name",
      usage = "Name of the model in the trained PMML model file."
  )
  private String mModelNameFlag = null;

  @Flag(
      name = "model-version",
      usage = "Version of the model. This will be used to identify the deployed model."
  )
  private String mModelVersionFlag = null;
  private ProtocolVersion mModelVersion = null;

  @Flag(
      name = "predictor-column",
      usage = "Name of the column containing predictor records in the form \"family:column\"."
  )
  private String mPredictorColumnFlag = null;
  private KijiColumnName mPredictorColumn = null;

  @Flag(
      name = "result-column",
      usage = "Name of the column to write result records to in the form \"family:column\"."
  )
  private String mResultColumnFlag = null;
  private KijiColumnName mResultColumn = null;

  @Flag(
      name = "result-record-name",
      usage = "Name of the Avro record that will contain results."
  )
  private String mResultRecordNameFlag = null;

  @Flag(
      name = "model-container",
      usage = "Path to write out the resulting model file. Defaults to ./<model-name>.json"
  )
  private String mModelContainerFlag = null;
  private File mModelContainer = null;

  @Flag(
      name = "validate",
      usage = "Validate the provided values against the provided Kiji table. Default is true."
  )
  private boolean mValidateFlag = true;

  @Override
  protected void validateFlags() throws Exception {
    super.validateFlags();

    // Check for required arguments.
    final StringBuilder errorMessage = new StringBuilder();
    if (mTableURIFlag == null) {
      errorMessage.append(" --table");
    }
    if (mModelFileFlag == null) {
      errorMessage.append(" --model-file");
    }
    if (mModelNameFlag == null) {
      errorMessage.append(" --model-name");
    }
    if (mModelVersionFlag == null) {
      errorMessage.append(" --model-version");
    }
    if (mPredictorColumnFlag == null) {
      errorMessage.append(" --predictor-column");
    }
    if (mResultColumnFlag == null) {
      errorMessage.append(" --result-column");
    }
    if (mResultRecordNameFlag == null) {
      errorMessage.append(" --result-record-name");
    }

    if (errorMessage.length() > 0) {
      exitWithFormattedErrorMessage("Missing arguments:%s", errorMessage.toString());
    }

    // Populate rich types.
    mTableURI = KijiURI.newBuilder(mTableURIFlag).build();
    mModelFile = new Path(mModelFileFlag);
    mModelVersion = ProtocolVersion.parse(mModelVersionFlag);
    mPredictorColumn = new KijiColumnName(mPredictorColumnFlag);
    mResultColumn = new KijiColumnName(mResultColumnFlag);
    mModelContainer = (mModelContainerFlag == null)
        ? new File(String.format("%s.json", mModelNameFlag))
        : new File(mModelContainerFlag);

    if (mValidateFlag) {
      Preconditions.checkNotNull(getConf());
      final FileSystem fileSystem = mModelFile.getFileSystem(getConf());

      // Check that the model file exists.
      Preconditions.checkArgument(
          fileSystem.exists(mModelFile) && !fileSystem.isDirectory(mModelFile),
          String.format("%s is not a file.", mModelFile.toString())
      );

      // Check that the model container output path doesn't exist.
      Preconditions.checkArgument(
          !mModelContainer.exists(),
          String.format("Output location %s already exists.", mModelContainer.getAbsolutePath())
      );

      final Kiji kiji = Kiji.Factory.open(mTableURI);
      try {
        // Check that the table exists.
        Preconditions.checkArgument(
            kiji.getMetaTable().tableExists(mTableURI.getTable()),
            String.format("%s does not exist.", mTableURI.toString())
        );

        final KijiTable table = kiji.openTable(mTableURI.getTable());
        try {
          final KijiTableLayout layout = table.getLayout();

          // Check that the predictor column exists.
          Preconditions.checkArgument(
              layout.getColumnNames().contains(mPredictorColumn),
              String.format(
                  "Table %s is missing the provided predictor column %s.",
                  mTableURI.toString(),
                  mPredictorColumn.getName()
              )
          );

          // Check that the result column exists.
          Preconditions.checkArgument(
              layout.getColumnNames().contains(mResultColumn),
              String.format(
                  "Table %s is missing the provided result column %s.",
                  mTableURI.toString(),
                  mResultColumn.getName()
              )
          );
        } finally {
          table.release();
        }
      } finally {
        kiji.release();
      }
    }
  }

  @Override
  public String getModelRepoToolName() {
    return "pmml";
  }

  @Override
  public String getName() {
    return MODEL_REPO_TOOL_BASE + getModelRepoToolName();
  }

  @Override
  public String getDescription() {
    return
        "Usage:\n"
        + "    kiji model-repo pmml "
        + "--table=<kiji://.env/default/table> "
        + "--model-file=<file:///path/to/pmml> "
        + "--model-name=<pmmlModelName> "
        + "--model-version=<0.0.1> "
        + "--predictor-column=<family:qualifier> "
        + "--result-column=<family:qualifier> "
        + "--result-record-name=<MyAvroResultRecord> "
        + "[--model-container=</path/to/write/new/model-container/to>] "
        + "[--validate=<True>] "
        + "\n"
        + "Example:\n"
        + "  Create a new model container file from an existing PMML trained model:\n"
        + "    kiji model-repo pmml \\\n"
        + "        --table=kiji://.env/default/table \\\n"
        + "        --model-file=file:///path/to/pmml \\\n"
        + "        --model-name=pmmlModelName \\\n"
        + "        --model-version=0.1.0 \\\n"
        + "        --predictor-column=model:predictor \\\n"
        + "        --result-column=model:result \\\n"
        + "        --result-record-name=MyScore \\\n"
        + "        --model-container=/home/me/new-model-container\n"
        + "  Create a new model container file without validating input:\n"
        + "    kiji model-repo pmml \\\n"
        + "        --table=kiji://.env/default/table \\\n"
        + "        --model-file=file:///path/to/pmml \\\n"
        + "        --model-name=pmmlModelName \\\n"
        + "        --model-version=0.1.0 \\\n"
        + "        --predictor-column=model:predictor \\\n"
        + "        --result-column=model:result \\\n"
        + "        --result-record-name=MyScore \\\n"
        + "        --model-container=/home/me/new-model-container \\\n"
        + "        --validate=false\n";
  }

  @Override
  public String getCategory() {
    return MODEL_REPO_TOOL_CATEGORY;
  }

  // Currently unchecked errors:
  // - Compatibility issues with existing registered reader/writer schemas.
  // - Provided name doesn't match any models in the specified pmml file.
  @Override
  protected int run(List<String> nonFlagArgs) throws Exception {
    // Generate the score function parameters.
    final Map<String, String> parameters = JpmmlScoreFunction.parameters(
        mModelFile.toString(),
        mModelNameFlag,
        mPredictorColumn,
        mResultRecordNameFlag
    );

    // Create the model container.
    final KijiModelContainer modelContainer = ModelContainer.createAvroModelContainer(
        mModelNameFlag,
        mModelVersion,
        mTableURI,
        mResultColumn,
        JpmmlScoreFunction.class,
        parameters
    );

    // Write the model container to the specified location.
    final PrintWriter writer = new PrintWriter(mModelContainer, "UTF-8");
    try {
      writer.println(ToJson.toJsonString(modelContainer));
    } finally {
      writer.close();
    }
    LOG.info(
        "Generated {} from pmml file {}.",
        mModelContainer.getAbsolutePath(),
        mModelFile.toString()
    );

    return 0;
  }
}
