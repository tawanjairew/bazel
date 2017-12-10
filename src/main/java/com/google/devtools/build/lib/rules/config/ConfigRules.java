// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.config;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.Builder;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.RuleSet;
import com.google.devtools.build.lib.rules.core.CoreRules;

/**
 * Set of rules to specify or manipulate configuration settings.
 */
public final class ConfigRules implements RuleSet {
  public static final ConfigRules INSTANCE = new ConfigRules();

  private ConfigRules() {
    // Use the static INSTANCE field instead.
  }

  @Override
  public void init(Builder builder) {
    builder.addRuleDefinition(new ConfigRuleClasses.ConfigBaseRule());
    builder.addRuleDefinition(new ConfigRuleClasses.ConfigSettingRule());
    builder.addConfig(ConfigFeatureFlagConfiguration.Options.class,
        new ConfigFeatureFlagConfiguration.Loader());

    builder.addRuleDefinition(new ConfigRuleClasses.ConfigFeatureFlagRule());
    builder.addSkylarkAccessibleTopLevels("config_common", new ConfigSkylarkCommon());
  }

  @Override
  public ImmutableList<RuleSet> requires() {
    return ImmutableList.of(CoreRules.INSTANCE);
  }
}
