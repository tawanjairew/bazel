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

package com.google.devtools.build.java.turbine.javac;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.devtools.build.buildjar.javac.plugins.dependency.StrictJavaDepsPlugin;
import com.google.devtools.build.java.turbine.javac.JavacTurbineCompileResult.Status;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.CacheFSInfo;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.tools.StandardLocation;

/** Performs a javac-based turbine compilation given a {@link JavacTurbineCompileRequest}. */
public class JavacTurbineCompiler {

  static JavacTurbineCompileResult compile(JavacTurbineCompileRequest request) throws IOException {

    Map<String, byte[]> files = new LinkedHashMap<>();
    Status status;
    StringWriter sw = new StringWriter();
    Context context = new Context();

    try (PrintWriter pw = new PrintWriter(sw)) {
      setupContext(context, request.strictJavaDepsPlugin(), request.transitivePlugin());
      CacheFSInfo.preRegister(context);
      try (FileSystem fs = Jimfs.newFileSystem(Configuration.forCurrentPlatform());
          JavacFileManager fm = new JavacFileManager(new Context(), false, UTF_8)) {
        JavacTask task =
            JavacTool.create()
                .getTask(
                    pw,
                    fm,
                    null /*diagnostics*/,
                    request.javacOptions(),
                    ImmutableList.of() /*classes*/,
                    fm.getJavaFileObjectsFromPaths(request.sources()),
                    context);

        Path classes = fs.getPath("classes");
        Files.createDirectories(classes);
        Path sources = fs.getPath("sources");
        Files.createDirectories(sources);

        fm.setContext(context);
        fm.setLocationFromPaths(StandardLocation.SOURCE_PATH, Collections.<Path>emptyList());
        fm.setLocationFromPaths(StandardLocation.CLASS_PATH, request.classPath());
        fm.setLocationFromPaths(StandardLocation.PLATFORM_CLASS_PATH, request.bootClassPath());
        fm.setLocationFromPaths(
            StandardLocation.ANNOTATION_PROCESSOR_PATH, request.processorClassPath());
        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, ImmutableList.of(classes));
        fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, ImmutableList.of(sources));

        status = task.call() ? Status.OK : Status.ERROR;

        // collect class output
        Files.walkFileTree(
            classes,
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                  throws IOException {
                files.put(classes.relativize(path).toString(), Files.readAllBytes(path));
                return FileVisitResult.CONTINUE;
              }
            });

      } catch (Throwable t) {
        t.printStackTrace(pw);
        status = Status.ERROR;
      }
    }

    return new JavacTurbineCompileResult(ImmutableMap.copyOf(files), status, sw, context);
  }

  static void setupContext(
      Context context, @Nullable StrictJavaDepsPlugin sjd, JavacTransitive transitive) {
    JavacTurbineJavaCompiler.preRegister(context, sjd, transitive);
  }
}
