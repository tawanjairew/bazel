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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RegisteredToolchainsFunction} and {@link RegisteredToolchainsValue}. */
@RunWith(JUnit4.class)
public class RegisteredToolchainsFunctionTest extends ToolchainTestCase {

  @Test
  public void testRegisteredToolchains() throws Exception {
    // Request the toolchains.
    SkyKey toolchainsKey = RegisteredToolchainsValue.key(targetConfig);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(toolchainsKey).isNotNull();

    RegisteredToolchainsValue value = result.get(toolchainsKey);
    // We have two registered toolchains, and two default for c++
    assertThat(value.registeredToolchains()).hasSize(4);

    assertThat(value.registeredToolchains().stream().anyMatch(toolchain ->
        (toolchain.toolchainType().equals(testToolchainType))
            && toolchain.execConstraints().contains(linuxConstraint)
            && toolchain.targetConstraints().contains(macConstraint)
            && toolchain.toolchainLabel().equals(makeLabel("//toolchain:test_toolchain_1")))).isTrue();

    assertThat(value.registeredToolchains().stream().anyMatch(toolchain ->
        (toolchain.toolchainType().equals(testToolchainType))
            && toolchain.execConstraints().contains(macConstraint)
            && toolchain.targetConstraints().contains(linuxConstraint)
            && toolchain.toolchainLabel().equals(makeLabel("//toolchain:test_toolchain_2")))).isTrue();
  }

  @Test
  public void testRegisteredToolchains_flagOverride() throws Exception {

    // Add an extra toolchain.
    scratch.file(
        "extra/BUILD",
        "load('//toolchain:toolchain_def.bzl', 'test_toolchain')",
        "toolchain(",
        "    name = 'extra_toolchain',",
        "    toolchain_type = '//toolchain:test_toolchain',",
        "    exec_compatible_with = ['//constraints:linux'],",
        "    target_compatible_with = ['//constraints:linux'],",
        "    toolchain = ':extra_toolchain_impl')",
        "test_toolchain(",
        "  name='extra_toolchain_impl',",
        "  data = 'extra')");

    rewriteWorkspace("register_toolchains('//toolchain:toolchain_1')");
    useConfiguration("--extra_toolchains=//extra:extra_toolchain");

    SkyKey toolchainsKey = RegisteredToolchainsValue.key(targetConfig);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_toolchains flag is first in the list.
    assertToolchainLabels(result.get(toolchainsKey))
        .containsAllOf(
            makeLabel("//extra:extra_toolchain_impl"), makeLabel("//toolchain:test_toolchain_1"))
        .inOrder();
  }

  @Test
  public void testRegisteredToolchains_notToolchain() throws Exception {
    rewriteWorkspace("register_toolchains(", "    '//error:not_a_toolchain')");
    scratch.file("error/BUILD", "filegroup(name = 'not_a_toolchain')");

    // Request the toolchains.
    SkyKey toolchainsKey = RegisteredToolchainsValue.key(targetConfig);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(toolchainsKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "invalid registered toolchain '//error:not_a_toolchain': "
                + "target does not provide the DeclaredToolchainInfo provider");
  }

  @Test
  public void testRegisteredToolchains_reload() throws Exception {
    rewriteWorkspace("register_toolchains('//toolchain:toolchain_1')");

    SkyKey toolchainsKey = RegisteredToolchainsValue.key(targetConfig);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey))
        .contains(makeLabel("//toolchain:test_toolchain_1"));

    // Re-write the WORKSPACE.
    rewriteWorkspace("register_toolchains('//toolchain:toolchain_2')");

    toolchainsKey = RegisteredToolchainsValue.key(targetConfig);
    result = requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey))
        .contains(makeLabel("//toolchain:test_toolchain_2"));
  }

  @Test
  public void testRegisteredToolchainsValue_equalsAndHashCode() {
    DeclaredToolchainInfo toolchain1 =
        DeclaredToolchainInfo.create(
            makeLabel("//test:toolchain"),
            ImmutableList.of(),
            ImmutableList.of(),
            makeLabel("//test/toolchain_impl_1"));
    DeclaredToolchainInfo toolchain2 =
        DeclaredToolchainInfo.create(
            makeLabel("//test:toolchain"),
            ImmutableList.of(),
            ImmutableList.of(),
            makeLabel("//test/toolchain_impl_2"));

    new EqualsTester()
        .addEqualityGroup(
            RegisteredToolchainsValue.create(ImmutableList.of(toolchain1, toolchain2)),
            RegisteredToolchainsValue.create(ImmutableList.of(toolchain1, toolchain2)))
        .addEqualityGroup(
            RegisteredToolchainsValue.create(ImmutableList.of(toolchain1)),
            RegisteredToolchainsValue.create(ImmutableList.of(toolchain2)),
            RegisteredToolchainsValue.create(ImmutableList.of(toolchain2, toolchain1)));
  }
}
