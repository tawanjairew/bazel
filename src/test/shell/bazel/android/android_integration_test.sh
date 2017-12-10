#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# For these tests to run do the following:
#
#   1. Install an Android SDK from https://developer.android.com
#   2. Set the $ANDROID_HOME environment variable
#   3. Uncomment the line in WORKSPACE containing android_sdk_repository
#
# Note that if the environment is not set up as above android_integration_test
# will silently be ignored and will be shown as passing.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }


function create_android_binary() {
  mkdir -p java/bazel
  cat > java/bazel/BUILD <<EOF
android_library(
    name = "lib",
    srcs = ["Lib.java"],
)
android_binary(
    name = "bin",
    srcs = ["MainActivity.java"],
    manifest = "AndroidManifest.xml",
    deps = [":lib"],
)
EOF

  cat > java/bazel/AndroidManifest.xml <<EOF
  <manifest package="bazel.android" />
EOF

  cat > java/bazel/Lib.java <<EOF
package bazel;

public class Lib {
  public static String message() {
    return "Hello Lib";
  }
}
EOF

  cat > java/bazel/MainActivity.java <<EOF
package bazel;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }
}
EOF
}

function test_sdk_library_deps() {
  create_new_workspace
  setup_android_sdk_support

  mkdir -p java/a
  cat > java/a/BUILD<<EOF
android_library(
    name = "a",
    deps = ["@androidsdk//com.android.support:mediarouter-v7-24.0.0"],
)
EOF

  bazel build --nobuild //java/a:a || fail "build failed"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/1928.
function test_empty_tree_artifact_action_inputs_mount_empty_directories() {
  create_new_workspace
  setup_android_sdk_support
  cat > AndroidManifest.xml <<EOF
<manifest package="com.test"/>
EOF
  mkdir res
  zip test.aar AndroidManifest.xml res/
  cat > BUILD <<EOF
aar_import(
  name = "test",
  aar = "test.aar",
)
EOF
  # Building aar_import invokes the AndroidResourceProcessingAction with a
  # TreeArtifact of the AAR resources as the input. Since there are no
  # resources, the Bazel sandbox should create an empty directory. If the
  # directory is not created, the action thinks that its inputs do not exist and
  # crashes.
  bazel build :test
}

function test_nonempty_aar_resources_tree_artifact() {
  create_new_workspace
  setup_android_sdk_support
  cat > AndroidManifest.xml <<EOF
<manifest package="com.test"/>
EOF
  mkdir -p res/values
  cat > res/values/values.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:android="http://schemas.android.com/apk/res/android">
</resources>
EOF
  zip test.aar AndroidManifest.xml res/values/values.xml
  cat > BUILD <<EOF
aar_import(
  name = "test",
  aar = "test.aar",
)
EOF
  bazel build :test
}

function test_android_sdk_repository_path_from_environment() {
  create_new_workspace
  setup_android_sdk_support
  # Overwrite WORKSPACE that was created by setup_android_sdk_support with one
  # that does not set the path attribute of android_sdk_repository.
  cat > WORKSPACE <<EOF
android_sdk_repository(
    name = "androidsdk",
)
EOF
  ANDROID_HOME=$ANDROID_SDK bazel build @androidsdk//:files || fail \
    "android_sdk_repository failed to build with \$ANDROID_HOME instead of " \
    "path"
}

function test_android_sdk_repository_no_path_or_android_home() {
  create_new_workspace
  cat > WORKSPACE <<EOF
android_sdk_repository(
    name = "androidsdk",
    api_level = 25,
)
EOF
  bazel build @androidsdk//:files >& $TEST_log && fail "Should have failed"
  expect_log "Either the path attribute of android_sdk_repository"
}

# Check that the build succeeds if an android_sdk is specified with --android_sdk
function test_specifying_android_sdk_flag() {
  create_new_workspace
  setup_android_sdk_support
  create_android_binary
  cat > WORKSPACE <<EOF
android_sdk_repository(
    name = "a",
)
EOF
  ANDROID_HOME=$ANDROID_SDK bazel build --android_sdk=@a//:sdk-24 \
    //java/bazel:bin || fail "build with --android_sdk failed"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/2621.
function test_android_sdk_repository_returns_null_if_env_vars_missing() {
  create_new_workspace
  setup_android_sdk_support
  ANDROID_HOME=/does_not_exist_1 bazel build @androidsdk//:files || \
    fail "Build failed"
  sed -i -e 's/path =/#path =/g' WORKSPACE
  ANDROID_HOME=/does_not_exist_2 bazel build @androidsdk//:files && \
    fail "Build should have failed"
  ANDROID_HOME=$ANDROID_SDK bazel build @androidsdk//:files || "Build failed"
}

if [[ ! -d "${TEST_SRCDIR}/androidsdk" ]]; then
  echo "Not running Android tests due to lack of an Android SDK."
  exit 0
fi

run_suite "Android integration tests"
