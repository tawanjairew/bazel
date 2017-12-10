// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.android;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg;
import com.google.devtools.build.lib.analysis.actions.ParamFileInfo;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.android.AndroidConfiguration.AndroidAaptVersion;
import com.google.devtools.build.lib.util.OS;
import java.util.Collections;
import java.util.List;

/**
 * Builder for creating resource shrinker actions.
 */
public class ResourceShrinkerActionBuilder {
  private AndroidAaptVersion targetAaptVersion;
  private Artifact resourceFilesZip;
  private Artifact shrunkJar;
  private Artifact proguardMapping;
  private ResourceContainer primaryResources;
  private ResourceDependencies dependencyResources;
  private Artifact resourceApkOut;
  private Artifact shrunkResourcesOut;
  private Artifact logOut;

  private final RuleContext ruleContext;
  private final SpawnAction.Builder spawnActionBuilder;
  private final AndroidSdkProvider sdk;

  private List<String> uncompressedExtensions = Collections.emptyList();
  private ResourceFilterFactory resourceFilterFactory;

  /** @param ruleContext The RuleContext of the owning rule. */
  public ResourceShrinkerActionBuilder(RuleContext ruleContext) throws RuleErrorException {
    this.ruleContext = ruleContext;
    this.spawnActionBuilder = new SpawnAction.Builder();
    this.sdk = AndroidSdkProvider.fromRuleContext(ruleContext);
    this.resourceFilterFactory = ResourceFilterFactory.empty();
  }

  public ResourceShrinkerActionBuilder setUncompressedExtensions(
      List<String> uncompressedExtensions) {
    this.uncompressedExtensions = uncompressedExtensions;
    return this;
  }

  /** @param resourceFilterFactory The filters to apply to the resources. */
  public ResourceShrinkerActionBuilder setResourceFilterFactory(
      ResourceFilterFactory resourceFilterFactory) {
    this.resourceFilterFactory = resourceFilterFactory;
    return this;
  }

  /**
   * @param resourceFilesZip A zip file containing the merged assets and resources to be shrunk.
   */
  public ResourceShrinkerActionBuilder withResourceFiles(Artifact resourceFilesZip) {
    this.resourceFilesZip = resourceFilesZip;
    return this;
  }

  /**
   * @param shrunkJar The deploy jar of the rule after a dead code removal Proguard pass.
   */
  public ResourceShrinkerActionBuilder withShrunkJar(Artifact shrunkJar) {
    this.shrunkJar = shrunkJar;
    return this;
  }

  /**
   * @param proguardMapping The Proguard mapping between obfuscated and original code.
   */
  public ResourceShrinkerActionBuilder withProguardMapping(Artifact proguardMapping) {
    this.proguardMapping = proguardMapping;
    return this;
  }

  /**
   * @param primary The fully processed {@link ResourceContainer} of the resources to be shrunk.
   *     Must contain both an R.txt and merged manifest.
   */
  public ResourceShrinkerActionBuilder withPrimary(ResourceContainer primary) {
    checkNotNull(primary);
    checkNotNull(primary.getManifest());
    checkNotNull(primary.getRTxt());
    this.primaryResources = primary;
    return this;
  }

  /**
   * @param resourceDeps The full dependency tree of {@link ResourceContainer}s.
   */
  public ResourceShrinkerActionBuilder withDependencies(ResourceDependencies resourceDeps) {
    this.dependencyResources = resourceDeps;
    return this;
  }

  /**
   * @param resourceApkOut The location to write the shrunk resource ap_ package.
   */
  public ResourceShrinkerActionBuilder setResourceApkOut(Artifact resourceApkOut) {
    this.resourceApkOut = resourceApkOut;
    return this;
  }

  /**
   * @param shrunkResourcesOut The location to write the shrunk resource files zip.
   */
  public ResourceShrinkerActionBuilder setShrunkResourcesOut(Artifact shrunkResourcesOut) {
    this.shrunkResourcesOut = shrunkResourcesOut;
    return this;
  }

  /**
   * @param logOut The location to write the shrinker log.
   */
  public ResourceShrinkerActionBuilder setLogOut(Artifact logOut) {
    this.logOut = logOut;
    return this;
  }

  /**
   * @param androidAaptVersion The aapt version to target with this action.
   */
  public ResourceShrinkerActionBuilder setTargetAaptVersion(AndroidAaptVersion androidAaptVersion) {
    this.targetAaptVersion = androidAaptVersion;
    return this;
  }

  public Artifact build() throws RuleErrorException {
    ImmutableList.Builder<Artifact> inputs = ImmutableList.builder();
    ImmutableList.Builder<Artifact> outputs = ImmutableList.builder();

    CustomCommandLine.Builder commandLine = new CustomCommandLine.Builder();

    // Set the busybox tool.
    FilesToRunProvider aapt;

    if (targetAaptVersion == AndroidAaptVersion.AAPT2) {
      aapt = sdk.getAapt2();
      commandLine.add("--tool").add("SHRINK_AAPT2").add("--");
      commandLine.addExecPath("--aapt2", aapt.getExecutable());
    } else {
      aapt = sdk.getAapt();
      commandLine.add("--tool").add("SHRINK").add("--");
      commandLine.addExecPath("--aapt", aapt.getExecutable());
    }

    commandLine.addExecPath("--annotationJar", sdk.getAnnotationsJar());
    inputs.add(sdk.getAnnotationsJar());

    commandLine.addExecPath("--androidJar", sdk.getAndroidJar());
    inputs.add(sdk.getAndroidJar());

    if (!uncompressedExtensions.isEmpty()) {
      commandLine.addAll(
          "--uncompressedExtensions", VectorArg.join(",").each(uncompressedExtensions));
    }
    if (ruleContext.getConfiguration().getCompilationMode() != CompilationMode.OPT) {
      commandLine.add("--debug");
    }
    if (resourceFilterFactory.hasConfigurationFilters()) {
      commandLine.add("--resourceConfigs", resourceFilterFactory.getConfigurationFilterString());
    }

    checkNotNull(resourceFilesZip);
    checkNotNull(shrunkJar);
    checkNotNull(proguardMapping);
    checkNotNull(primaryResources);
    checkNotNull(primaryResources.getRTxt());
    checkNotNull(primaryResources.getManifest());
    checkNotNull(resourceApkOut);

    commandLine.addExecPath("--resources", resourceFilesZip);
    inputs.add(resourceFilesZip);

    commandLine.addExecPath("--shrunkJar", shrunkJar);
    inputs.add(shrunkJar);

    commandLine.addExecPath("--proguardMapping", proguardMapping);
    inputs.add(proguardMapping);

    commandLine.addExecPath("--rTxt", primaryResources.getRTxt());
    inputs.add(primaryResources.getRTxt());

    commandLine.addExecPath("--primaryManifest", primaryResources.getManifest());
    inputs.add(primaryResources.getManifest());

    ImmutableList<Artifact> dependencyManifests = getManifests(dependencyResources);
    if (!dependencyManifests.isEmpty()) {
      commandLine.addExecPaths("--dependencyManifest", dependencyManifests);
      inputs.addAll(dependencyManifests);
    }

    ImmutableList<String> resourcePackages =
        getResourcePackages(primaryResources, dependencyResources);
    commandLine.addAll("--resourcePackages", VectorArg.join(",").each(resourcePackages));

    commandLine.addExecPath("--shrunkResourceApk", resourceApkOut);
    outputs.add(resourceApkOut);

    commandLine.addExecPath("--shrunkResources", shrunkResourcesOut);
    outputs.add(shrunkResourcesOut);

    commandLine.addExecPath("--log", logOut);
    outputs.add(logOut);

    ParamFileInfo.Builder paramFileInfo = ParamFileInfo.builder(ParameterFileType.SHELL_QUOTED);
    // Some flags (e.g. --mainData) may specify lists (or lists of lists) separated by special
    // characters (colon, semicolon, hashmark, ampersand) that don't work on Windows, and quoting
    // semantics are very complicated (more so than in Bash), so let's just always use a parameter
    // file.
    // TODO(laszlocsomor), TODO(corysmith): restructure the Android BusyBux's flags by deprecating
    // list-type and list-of-list-type flags that use such problematic separators in favor of
    // multi-value flags (to remove one level of listing) and by changing all list separators to a
    // platform-safe character (= comma).
    paramFileInfo.setUseAlways(OS.getCurrent() == OS.WINDOWS);

    ruleContext.registerAction(
        spawnActionBuilder
            .useDefaultShellEnvironment()
            .addTool(aapt)
            .addInputs(inputs.build())
            .addOutputs(outputs.build())
            .addCommandLine(commandLine.build(), paramFileInfo.build())
            .setExecutable(
                ruleContext.getExecutablePrerequisite("$android_resources_busybox", Mode.HOST))
            .setProgressMessage("Shrinking resources for %s", ruleContext.getLabel())
            .setMnemonic("ResourceShrinker")
            .build(ruleContext));

    return resourceApkOut;
  }

  private ImmutableList<Artifact> getManifests(ResourceDependencies resourceDependencies) {
    ImmutableList.Builder<Artifact> manifests = ImmutableList.builder();
    for (ResourceContainer resources : resourceDependencies.getResourceContainers()) {
      if (resources.getManifest() != null) {
        manifests.add(resources.getManifest());
      }
    }
    return manifests.build();
  }

  private ImmutableList<String> getResourcePackages(
      ResourceContainer primaryResources, ResourceDependencies resourceDependencies) {
    ImmutableList.Builder<String> resourcePackages = ImmutableList.builder();
    resourcePackages.add(primaryResources.getJavaPackage());
    for (ResourceContainer resources : resourceDependencies.getResourceContainers()) {
      resourcePackages.add(resources.getJavaPackage());
    }
    return resourcePackages.build();
  }
}

