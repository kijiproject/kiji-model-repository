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

import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;

import org.kiji.schema.tools.BaseTool;

/**
 * The base implementation of the model repository tool suite. This will respond to
 * <tt>bin/kiji model-repo</tt> and in the absence of any further arguments, will print the
 * list of available tools within the model repository context.
 *
 * Tools belonging to the model repository suite must subclass and implement the
 * {@link KijiModelRepoTool} interface.
 *
 */
public class BaseModelRepoTool extends BaseTool {

  private List<String> mSubtoolArgs = Lists.newArrayList();

  @Override
  public final String getCategory() {
    return "Model Repository";
  }

  @Override
  public String getDescription() {
    return "Model Repository Tool Suite";
  }

  @Override
  public final String getName() {
    return "model-repo";
  }

  @Override
  protected final int run(List<String> toolArgs) throws Exception {
    String subToolName = toolArgs.get(0);
    KijiModelRepoTool subTool = KijiModelRepoToolLauncher.getModelRepoTool(subToolName);
    if (subTool == null) {
      getPrintStream().println("Unknown tool " + subToolName);
      printHelp();
      return FAILURE;
    } else {
      // Set the configuration for the subtool
      Configuration conf = getConf();
      if (conf == null) {
        conf = new Configuration();
        setConf(conf);
      }
      conf = HBaseConfiguration.addHbaseResources(conf);
      // Should not be any Configuration set yet for the subtool.
      Preconditions.checkArgument(
          null == subTool.getConf(),
          String.format(
              "We assume that the sub tool %s does not have any Configuration set in its "
                + "constructor.  Please set any sub-tool-specific configuration within toolMain.",
              subToolName
          ));
      subTool.setConf(conf);

      return subTool.toolMain(mSubtoolArgs);
    }
  }

  @Override
  public int toolMain(List<String> args) throws Exception {
    // The first argument is the name of the sub-tool to run and the
    // remaining args will be passed into the sub-tool as its arguments.
    if (args.isEmpty()) {
      printHelp();
      return FAILURE;
    } else {
      mSubtoolArgs = args.subList(1, args.size());
      return super.toolMain(args.subList(0, 1));
    }
  }

  /**
   * Prints a simple help message outlining the various tools belonging to the model
   * repository suite. All model repository tools are implementations of the
   * {@link KijiModelRepoTool}
   *
   */
  private void printHelp() {
    getPrintStream().println("The list of available model repository tools:");
    for (KijiModelRepoTool tool : KijiModelRepoToolLauncher.getModelRepoTools()) {
      getPrintStream().println(tool.getName() + " - " + tool.getDescription());
    }
  }
}
