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

import org.kiji.common.flags.Flag;
import org.kiji.modelrepo.KijiModelRepository;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiURI;
import org.kiji.schema.tools.BaseTool;

/**
 * Implementation of the model repository uninstallation tool. This will remove an existing model
 * repository table in the specified instance.
 *
 * Delete the model repository in an instance:
 * <pre>
 *   kiji model-repo delete --kiji=kiji://.env/default
 * </pre>
 */
public final class DeleteModelRepoTool extends BaseTool implements KijiModelRepoTool {

  @Flag(name = "kiji", usage = "Name of the KIJI instance housing the model repository.")
  private String mInstanceName = null;

  private KijiURI mInstanceURI = null;

  /** The default instance to use for housing the model repo table and meta information. **/
  private static final String DEFAULT_INSTANCE_NAME = "kiji://.env/default";

  @Override
  protected void validateFlags() throws Exception {
    super.validateFlags();
    if (mInstanceName == null) {
      mInstanceName = DEFAULT_INSTANCE_NAME;
    }
    mInstanceURI = KijiURI.newBuilder(mInstanceName).build();
  }

  @Override
  public String getUsageString() {
    return
        "Usage:\n"
        + "    kiji model-repo delete --kiji=<kiji-uri>\n"
        + "\n"
        + "Example:\n"
        + "  Delete the model repository in an instance:\n"
        + "    kiji model-repo delete --kiji=kiji://.env/default\n";
  }

  @Override
  public String getName() {
    return MODEL_REPO_TOOL_BASE + getModelRepoToolName();
  }

  @Override
  public String getCategory() {
    return MODEL_REPO_TOOL_CATEGORY;
  }

  @Override
  public String getModelRepoToolName() {
    return "delete";
  }

  @Override
  public String getDescription() {
    return "Removes existing model repository.";
  }

  @Override
  protected int run(List<String> nonFlagArgs) throws Exception {
    Preconditions.checkArgument(nonFlagArgs.size() == 0,
        "This tool does not accept unnamed arguments: ", nonFlagArgs.toString());
    Kiji kijiInstance = Kiji.Factory.open(mInstanceURI);
    try {
      KijiModelRepository.delete(kijiInstance);
    } finally {
      kijiInstance.release();
    }
    return SUCCESS;
  }
}
