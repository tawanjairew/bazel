// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.skyframe.LegacySkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * Value that stores the workspace status artifacts and their generating action. There should be
 * only one of these values in the graph at any time.
 */
// TODO(bazel-team): This seems to be superfluous now, but it cannot be removed without making
// PrecomputedValue public instead of package-private
public class WorkspaceStatusValue extends ActionLookupValue {
  private final Artifact stableArtifact;
  private final Artifact volatileArtifact;

  // There should only ever be one BuildInfo value in the graph.
  static final ArtifactOwner ARTIFACT_OWNER = new BuildInfoKey();
  public static final SkyKey SKY_KEY = LegacySkyKey.create(SkyFunctions.BUILD_INFO, ARTIFACT_OWNER);

  WorkspaceStatusValue(
      ActionKeyContext actionKeyContext,
      Artifact stableArtifact,
      Artifact volatileArtifact,
      WorkspaceStatusAction action,
      boolean removeActionAfterEvaluation) {
    super(actionKeyContext, action, removeActionAfterEvaluation);
    this.stableArtifact = stableArtifact;
    this.volatileArtifact = volatileArtifact;
  }

  public Artifact getStableArtifact() {
    return stableArtifact;
  }

  public Artifact getVolatileArtifact() {
    return volatileArtifact;
  }

  private static class BuildInfoKey extends ActionLookupKey {
    @Override
    protected SkyFunctionName getType() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected SkyKey getSkyKeyInternal() {
      return SKY_KEY;
    }
  }
}
