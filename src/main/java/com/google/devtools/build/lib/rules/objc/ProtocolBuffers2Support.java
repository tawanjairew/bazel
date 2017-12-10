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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * Support for generating Objective C proto static libraries that registers actions which generate
 * and compile the Objective C protos by using the deprecated ProtocolBuffers2 library and compiler.
 *
 * <p>Methods on this class can be called in any order without impacting the result.
 */
final class ProtocolBuffers2Support {

  private static final String UNIQUE_DIRECTORY_NAME = "_generated_protos";

  private final RuleContext ruleContext;
  private final ProtoAttributes attributes;

  /**
   * Creates a new proto support for the ProtocolBuffers2 library.
   *
   * @param ruleContext context this proto library is constructed in
   */
  public ProtocolBuffers2Support(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
    this.attributes = new ProtoAttributes(ruleContext);
  }

  /**
   * Register the proto generation actions. These actions generate the ObjC/CPP code to be compiled
   * by this rule.
   */
  public ProtocolBuffers2Support registerGenerationActions() throws InterruptedException {
    ruleContext.registerAction(
        FileWriteAction.create(
            ruleContext,
            getProtoInputsFile(),
            getProtoInputsFileContents(
                attributes.filterWellKnownProtos(attributes.getProtoFiles())),
            false));

    ruleContext.registerAction(
        ObjcRuleClasses.spawnOnDarwinActionBuilder()
            .setMnemonic("GenObjcPB2Protos")
            .addInput(attributes.getProtoCompiler())
            .addInputs(attributes.getProtoCompilerSupport())
            .addInput(getProtoInputsFile())
            .addTransitiveInputs(attributes.getProtoFiles())
            .addInputs(attributes.getOptionsFile().asSet())
            .addOutputs(getGeneratedProtoOutputs(getHeaderExtension()))
            .addOutputs(getGeneratedProtoOutputs(getSourceExtension()))
            .setExecutable(PathFragment.create("/usr/bin/python"))
            .addCommandLine(getGenerationCommandLine())
            .build(ruleContext));
    return this;
  }

  /**
   * Registers the actions that will compile the generated code.
   */
  public ProtocolBuffers2Support registerCompilationActions()
      throws RuleErrorException, InterruptedException {
    CompilationSupport compilationSupport =
        new CompilationSupport.Builder()
            .setRuleContext(ruleContext)
            .doNotUseDeps()
            .doNotUsePch()
            .build();

    compilationSupport.registerCompileAndArchiveActions(getCommon());
    return this;
  }

  /** Adds the generated files to the set of files to be output when this rule is built. */
  public ProtocolBuffers2Support addFilesToBuild(NestedSetBuilder<Artifact> filesToBuild)
      throws InterruptedException {
    filesToBuild
        .addAll(getGeneratedProtoOutputs(getHeaderExtension()))
        .addAll(getGeneratedProtoOutputs(getSourceExtension()))
        .addAll(getCompilationArtifacts().getArchive().asSet());
    return this;
  }

  /** Returns the ObjcProvider for this target. */
  public ObjcProvider getObjcProvider() {
    return getCommon().getObjcProvider();
  }

  private String getHeaderExtension() {
    return ".pb" + (attributes.usesObjcHeaderNames() ? "objc.h" : ".h");
  }

  private String getSourceExtension() {
    return ".pb.m";
  }

  private ObjcCommon getCommon() {
    return new ObjcCommon.Builder(ruleContext)
        .setIntermediateArtifacts(new IntermediateArtifacts(ruleContext, ""))
        .setHasModuleMap()
        .setCompilationArtifacts(getCompilationArtifacts())
        .addIncludes(getIncludes())
        .addDepObjcProviders(
            ruleContext.getPrerequisites(
                ObjcRuleClasses.PROTO_LIB_ATTR, Mode.TARGET, ObjcProvider.SKYLARK_CONSTRUCTOR))
        .build();
  }

  private CompilationArtifacts getCompilationArtifacts() {
    Iterable<Artifact> generatedSources = getGeneratedProtoOutputs(getSourceExtension());
    return new CompilationArtifacts.Builder()
        .setIntermediateArtifacts(new IntermediateArtifacts(ruleContext, ""))
        .addAdditionalHdrs(getGeneratedProtoOutputs(getHeaderExtension()))
        .addAdditionalHdrs(generatedSources)
        .addNonArcSrcs(generatedSources)
        .build();
  }

  private Artifact getProtoInputsFile() {
    return ruleContext.getUniqueDirectoryArtifact(
        "_protos", "_proto_input_files", ruleContext.getConfiguration().getGenfilesDirectory());
  }

  private String getProtoInputsFileContents(Iterable<Artifact> outputProtos) {
    // Sort the file names to make the remote action key independent of the precise deps structure.
    // compile_protos.py will sort the input list anyway.
    Iterable<Artifact> sorted = Ordering.natural().immutableSortedCopy(outputProtos);
    return Artifact.joinExecPaths("\n", sorted);
  }

  private CustomCommandLine getGenerationCommandLine() {
    CustomCommandLine.Builder commandLineBuilder =
        new CustomCommandLine.Builder()
            .addExecPath(attributes.getProtoCompiler())
            .add("--input-file-list")
            .addExecPath(getProtoInputsFile())
            .add("--output-dir")
            .addDynamicString(getWorkspaceRelativeOutputDir().getSafePathString())
            .add("--working-dir")
            .add(".");

    if (attributes.getOptionsFile().isPresent()) {
      commandLineBuilder
          .add("--compiler-options-path")
          .addExecPath(attributes.getOptionsFile().get());
    }

    if (attributes.usesObjcHeaderNames()) {
      commandLineBuilder.add("--use-objc-header-names");
    }
    return commandLineBuilder.build();
  }

  public ImmutableSet<PathFragment> getIncludes() {
    ImmutableSet.Builder<PathFragment> searchPathEntriesBuilder =
        new ImmutableSet.Builder<PathFragment>().add(getWorkspaceRelativeOutputDir());

    if (attributes.needsPerProtoIncludes()) {
      PathFragment generatedProtoDir = PathFragment.create(
          getWorkspaceRelativeOutputDir(), ruleContext.getLabel().getPackageFragment());

      searchPathEntriesBuilder
          .add(generatedProtoDir)
          .addAll(
              Iterables.transform(
                  getGeneratedProtoOutputs(getHeaderExtension()),
                  input -> input.getExecPath().getParentDirectory()));
    }

    return searchPathEntriesBuilder.build();
  }

  private PathFragment getWorkspaceRelativeOutputDir() {
    // Generate sources in a package-and-rule-scoped directory; adds both the
    // package-and-rule-scoped directory and the header-containing-directory to the include path
    // of dependers.
    PathFragment rootRelativeOutputDir = ruleContext.getUniqueDirectory(UNIQUE_DIRECTORY_NAME);

    return PathFragment.create(
        ruleContext.getBinOrGenfilesDirectory().getExecPath(), rootRelativeOutputDir);
  }

  private Iterable<Artifact> getGeneratedProtoOutputs(String extension) {
    ImmutableList.Builder<Artifact> builder = new ImmutableList.Builder<>();
    for (Artifact protoFile : attributes.filterWellKnownProtos(attributes.getProtoFiles())) {
      String protoFileName = FileSystemUtils.removeExtension(protoFile.getFilename());
      String generatedOutputName = attributes.getGeneratedProtoFilename(protoFileName, false);

      PathFragment generatedFilePath = PathFragment.create(
          protoFile.getRootRelativePath().getParentDirectory(),
          PathFragment.create(generatedOutputName));

      PathFragment outputFile = FileSystemUtils.appendExtension(generatedFilePath, extension);

      if (outputFile != null) {
        builder.add(
            ruleContext.getUniqueDirectoryArtifact(
                UNIQUE_DIRECTORY_NAME, outputFile, ruleContext.getBinOrGenfilesDirectory()));
      }
    }
    return builder.build();
  }
}
