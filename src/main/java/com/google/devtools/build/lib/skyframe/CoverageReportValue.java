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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.skyframe.LegacySkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * A SkyValue to store the coverage report Action and Artifacts.
 */
public class CoverageReportValue extends ActionLookupValue {

  // There should only ever be one CoverageReportValue value in the graph.
  public static final ArtifactOwner ARTIFACT_OWNER = new CoverageReportKey();
  static final SkyKey SKY_KEY = LegacySkyKey.create(SkyFunctions.COVERAGE_REPORT, ARTIFACT_OWNER);

  CoverageReportValue(
      ActionKeyContext actionKeyContext,
      ImmutableList<ActionAnalysisMetadata> coverageReportActions,
      boolean removeActionsAfterEvaluation) {
    super(actionKeyContext, coverageReportActions, removeActionsAfterEvaluation);
  }

  private static class CoverageReportKey extends ActionLookupKey {
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
