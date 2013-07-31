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
import org.kiji.schema.tools.BaseTool;

/**
 * The deploy tool uploads the model lifecycle and coordinates to the model repository.
 */
public class ModelRepoDeployTool extends BaseTool implements KijiModelRepoTool {
  /** Maven artifact name: groupId.artifactId. */
  private String mMavenArtifact = null;

  @Flag(name="model-lifecycle-dir", usage="Path to model lifecycle directory.")
  private String mDir = null;

  @Flag(name="definition", usage="Path to model definition.")
  private String mDefinition = null;

  @Flag(name="environment", usage="Path to model environment.")
  private String mEnvironment = null;

  @Flag(name="version", usage="Model lifecycle version.")
  private String mVersion = null;

  @Flag(name="production_ready", usage="Is the model lifecycle production ready.")
  private boolean mProductionReady = false;

  @Flag(name="message", usage="Update message for this deployment.")
  private String mMessage = "";

  /** {@inheritDoc} */
  @Override
  public String getCategory() {
    return "Model Repository";
  }

  /** {@inheritDoc} */
  @Override
  public String getDescription() {
    return "Creates a new model repository.";
  }

  /** {@inheritDoc} */
  @Override
  public String getName() {
    return getModelRepoToolName();
  }

  /** {@inheritDoc} */
  @Override
  public String getModelRepoToolName() {
    return "deploy";
  }

  /** {@inheritDoc} */
  @Override
  protected int run(List<String> arg0) throws Exception {
    // Validate parameters.
    Preconditions.checkArgument(arg0.size() != 1, "There should be exactly one maven artifact.");
    mMavenArtifact = arg0.get(0);
    Preconditions.checkNotNull(mDir, "Path to model lifecycle directory must be specified.");
    Preconditions.checkNotNull(mDefinition, "Path to model definition must be specified.");
    Preconditions.checkNotNull(mEnvironment, "Path to model environment must be specified.");
    Preconditions.checkNotNull(mVersion, "Model version must be specified.");

    return FAILURE;
  }
}
