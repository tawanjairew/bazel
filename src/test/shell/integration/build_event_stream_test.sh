#!/bin/bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
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
#
# An end-to-end test that Bazel's experimental UI produces reasonable output.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

#### SETUP #############################################################

set -e

function set_up() {
  mkdir -p pkg
  touch pkg/somesourcefile
  cat > pkg/true.sh <<'EOF'
#!/bin/sh
[ -n "${XML_OUTPUT_FILE}" ] && touch "${XML_OUTPUT_FILE}"
exit 0
EOF
  chmod 755 pkg/true.sh
  cat > pkg/false.sh <<'EOF'
#!/bin/sh
[ -n "${XML_OUTPUT_FILE}" ] && touch "${XML_OUTPUT_FILE}"
exit 1
EOF
  chmod 755 pkg/false.sh
  cat > pkg/slowtest.sh <<EOF
#!/bin/sh
sleep 1
exit 0
EOF
  chmod 755 pkg/slowtest.sh
  touch pkg/sourcefileA pkg/sourcefileB pkg/sourcefileC
  cat > pkg/BUILD <<EOF
exports_files(["somesourcefile"])
sh_test(
  name = "true",
  srcs = ["true.sh"],
  size = "small",
)
sh_test(
  name = "slow",
  srcs = ["slowtest.sh"],
)
test_suite(
  name = "suite",
  tests = ["true"],
)
sh_test(
  name = "flaky",
  srcs = ["false.sh"],
  flaky = True,
)
genrule(
  name = "fails_to_build",
  outs = ["fails_to_build.txt"],
  cmd = "false",
  executable = 1,
)
sh_test(
  name = "test_that_fails_to_build",
  srcs = [":fails_to_build"],
)
genrule(
  name = "output_files_and_tags",
  outs = ["out1.txt"],
  cmd = "echo foo > \\"\$@\\"",
  tags = ["tag1", "tag2"]
)
action_listener(
    name = "listener",
    mnemonics = ["Genrule"],
    extra_actions = [":extra"],
    visibility = ["//visibility:public"],
)
extra_action(
   name = "extra",
   cmd = "echo Hello World",
)
filegroup(
  name = "innergroup",
  srcs = ["sourcefileA", "sourcefileB"],
)
filegroup(
  name = "outergroup",
  srcs = ["sourcefileC", ":innergroup"],
)
genrule(
  name = "not_a_test",
  outs = ["not_a_test.txt"],
  cmd = "touch \$@",
)
EOF
cat > simpleaspect.bzl <<EOF
def _simple_aspect_impl(target, ctx):
    for orig_out in ctx.rule.attr.outs:
        aspect_out = ctx.actions.declare_file(orig_out.name + ".aspect")
        ctx.actions.write(
            output=aspect_out,
            content = "Hello from aspect")
    return struct(output_groups={
        "aspect-out" : depset([aspect_out]) })

simple_aspect = aspect(implementation=_simple_aspect_impl)
EOF
cat > failingaspect.bzl <<EOF
def _failing_aspect_impl(target, ctx):
    for orig_out in ctx.rule.attr.outs:
        aspect_out = ctx.actions.declare_file(orig_out.name + ".aspect")
        ctx.actions.run_shell(
            inputs = [],
            outputs = [aspect_out],
            command = "false",
        )
    return struct(output_groups={
        "aspect-out" : depset([aspect_out]) })

failing_aspect = aspect(implementation=_failing_aspect_impl)
EOF
touch BUILD
cat > sample_workspace_status <<EOF
#!/bin/sh
echo SAMPLE_WORKSPACE_STATUS workspace_status_value
EOF
chmod  755 sample_workspace_status
mkdir -p visibility/hidden
cat > visibility/hidden/BUILD <<EOF
genrule(
    name = "hello",
    outs = ["hello.txt"],
    cmd = "echo Hello World > \$@",
)
EOF
cat > visibility/BUILD <<EOF
genrule(
    name = "cannotsee",
    outs = ["cannotsee.txt"],
    srcs = ["//visibility/hidden:hello"],
    cmd = "cp \$< \$@",
)
EOF
mkdir -p failingtool
cat > failingtool/BUILD <<'EOF'
genrule(
    name = "tool",
    outs = ["tool.sh"],
    cmd = "false",
)
genrule(
    name = "usestool",
    outs = ["out.txt"],
    tools = [":tool"],
    cmd = "$(location :tool) > $@",
)
EOF
}

#### TESTS #############################################################

function test_basic() {
  # Basic properties of the event stream
  # - a completed target explicity requested should be reported
  # - after success the stream should close naturally, without any
  #   reports about aborted events.
  # - the command line is reported
  # - the target_kind is reported
  # - for single-configuration builds, there is precisely one configuration
  #   event reported; also make variables are shown
  bazel test --build_event_text_file=$TEST_log --tool_tag=MyFancyTool pkg:true \
    || fail "bazel test failed"
  expect_log 'pkg:true'
  # Command line
  expect_log 'args: "test"'
  expect_log 'args: "--build_event_text_file='
  expect_log 'args: "pkg:true"'
  # Options parsed
  expect_log 'tool_tag: "MyFancyTool"'
  # Build Finished
  expect_log 'build_finished'
  expect_log 'SUCCESS'
  expect_log 'finish_time'
  expect_not_log 'aborted'
  # target kind for the sh_test
  expect_log 'target_kind:.*sh'
  # test size should be reported
  expect_log 'test_size: SMALL'
  # configuration reported with make variables
  expect_log_once '^configuration '
  expect_log 'key: "TARGET_CPU"'
}

function test_target_information_early() {
  # Verify that certain information is present in the log as part of
  # the TargetConfigured event (verifying that it comes at least before
  # the first TargetCompleted event, which is fine, if we only ask for
  # a single target).
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log '^completed'
  ed $TEST_log <<'EOF'
1
/^completed/+1,$d
a
...[cut here]
.
w
q
EOF
  expect_log 'target_kind:.*sh'
  expect_log 'test_size: SMALL'

  bazel build --verbose_failures --build_event_text_file=$TEST_log \
    pkg:output_files_and_tags || fail "bazel build failed"
  expect_log '^completed'
  ed $TEST_log <<'EOF'
1
/^completed/+1,$d
a
...[cut here]
.
w
q
EOF
  expect_log 'tag1'
  expect_log 'tag2'
}

function test_workspace_status() {
  bazel test --build_event_text_file=$TEST_log \
     --workspace_status_command=sample_workspace_status pkg:true \
    || fail "bazel test failed"
  expect_log_once '^workspace_status'
  expect_log 'key.*SAMPLE_WORKSPACE_STATUS'
  expect_log 'value.*workspace_status_value'
}

function test_suite() {
  # ...same true when running a test suite containing that test
  bazel test --build_event_text_file=$TEST_log pkg:suite \
    || fail "bazel test failed"
  expect_log 'pkg:true'
  expect_not_log 'aborted'
}

function test_test_summary() {
  # Requesting a test, we expect
  # - precisely one test summary (for the single test we run)
  # - that is properly chained (no additional progress events)
  # - the correct overall status being reported
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log_once '^test_summary '
  expect_not_log 'aborted'
  expect_log 'status.*PASSED'
  expect_not_log 'status.*FAILED'
  expect_not_log 'status.*FLAKY'
}

function test_test_inidivual_results() {
  # Requesting a test, we expect
  # - precisely one test summary (for the single test we run)
  # - that is properly chained (no additional progress events)
  bazel test --build_event_text_file=$TEST_log \
    --runs_per_test=2 pkg:true \
    || fail "bazel test failed"
  expect_log '^test_result'
  expect_log 'run.*1'
  expect_log 'status.*PASSED'
  expect_log_once '^test_summary '
  expect_not_log 'aborted'
}

function test_test_attempts() {
  # Run a failing test declared as flaky.
  # We expect to see 3 attempts to happen, and also find the 3 xml files
  # mentioned in the stream.
  # Moreover, as the test consistently fails, we expect the overall status
  # to be reported as failure.
  (bazel test --build_event_text_file=$TEST_log pkg:flaky \
      && fail "test failure expected" ) || true
  expect_log 'attempt.*1$'
  expect_log 'attempt.*2$'
  expect_log 'attempt.*3$'
  expect_log_once '^test_summary '
  expect_log 'status.*FAILED'
  expect_not_log 'status.*PASSED'
  expect_not_log 'status.*FLAKY'
  expect_not_log 'aborted'
  expect_log '^test_result'
  expect_log 'test_action_output'
  expect_log 'flaky/.*_1.xml'
  expect_log 'flaky/.*_2.xml'
  expect_log 'flaky/.*test.xml'
  expect_log 'name:.*test.log'
  expect_log 'name:.*test.xml'
  expect_log 'name:.*TESTS_FAILED'
  expect_not_log 'name:.*SUCCESS'
}

function test_test_runtime() {
  bazel test --build_event_text_file=$TEST_log pkg:slow \
    || fail "bazel test failed"
  expect_log 'pkg:slow'
  expect_log '^test_result'
  expect_log 'test_attempt_duration_millis.*[1-9]'
  expect_log 'build_finished'
  expect_log 'SUCCESS'
  expect_log 'finish_time'
  expect_not_log 'aborted'
}

function test_test_start_times() {
  # Verify that the start time of a test is reported, regardless whether
  # it was cached or not.
  bazel clean --expunge
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log 'test_attempt_start_millis_epoch.*[1-9]'
  expect_not_log 'cached_locally'
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log 'test_attempt_start_millis_epoch.*[1-9]'
  expect_log 'cached_locally.*true'
}

function test_test_attempts_multi_runs() {
  # Sanity check on individual test attempts. Even in more complicated
  # situations, with some test rerun and some not, all events are properly
  # announced by the test actions (and not chained into the progress events).
  ( bazel test --build_event_text_file=$TEST_log \
    --runs_per_test=2 pkg:true pkg:flaky \
    && fail "test failure expected" ) || true
  expect_log 'run.*1'
  expect_log 'attempt.*2'
  expect_not_log 'aborted'
}

function test_test_attempts_multi_runs_flake_detection() {
  # Sanity check on individual test attempts. Even in more complicated
  # situations, with some test rerun and some not, all events are properly
  # announced by the test actions (and not chained into the progress events).
  ( bazel test --build_event_text_file=$TEST_log \
    --runs_per_test=2 --runs_per_test_detects_flakes pkg:true pkg:flaky \
    && fail "test failure expected" ) || true
  expect_log 'run.*1'
  expect_log 'attempt.*2'
  expect_not_log 'aborted'
}

function test_cached_test_results() {
  # Verify that both, clean and cached test results are reported correctly,
  # including the appropriate reference to log files.
  bazel clean --expunge
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "Clean testing pkg:true failed"
  expect_log '^test_result'
  expect_log 'name:.*test.log'
  expect_log 'name:.*test.xml'
  expect_not_log 'cached_locally'
  expect_not_log 'aborted'
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "Second testing of pkg:true failed"
  expect_log '^test_result'
  expect_log 'name:.*test.log'
  expect_log 'name:.*test.xml'
  expect_log 'cached_locally'
  expect_not_log 'aborted'
}

function test_target_complete() {
  bazel build --verbose_failures --build_event_text_file=$TEST_log \
  pkg:output_files_and_tags || fail "bazel build failed"
  expect_log 'output_group'
  expect_log 'out1.txt'
  expect_log 'tag1'
  expect_log 'tag2'
}

function test_extra_action() {
  # verify that normal successful actions are not reported, but extra actions
  # are
  bazel build --build_event_text_file=$TEST_log \
    pkg:output_files_and_tags || fail "bazel build failed"
  expect_not_log '^action'
  bazel build --build_event_text_file=$TEST_log \
    --experimental_action_listener=pkg:listener \
    pkg:output_files_and_tags || fail "bazel build with listener failed"
  expect_log '^action'
}

function test_action_ids() {
  bazel build --build_event_text_file=$TEST_log \
    --experimental_action_listener=pkg:listener \
    pkg:output_files_and_tags || fail "bazel build with listener failed"
  expect_log '^action'

  # Action ids should contain label and configuration if those exist.
  # Assumes action_completed id is 6 lines long
  for id_line in $(grep -n 'action_completed {' $TEST_log | cut -f 1 -d :)
  do
    sed -n "$id_line,$((id_line+6))p" $TEST_log > "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*primary_output: .*' "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*label: .*' "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*configuration.*' "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*id: .*' "$TEST_TMPDIR/event_id.txt"
  done
}

function test_aspect_artifacts() {
  bazel build --build_event_text_file=$TEST_log \
    --aspects=simpleaspect.bzl%simple_aspect \
    --output_groups=aspect-out \
    pkg:output_files_and_tags || fail "bazel build failed"
  expect_log 'aspect.*simple_aspect'
  expect_log 'name.*aspect-out'
  expect_log 'name.*out1.txt.aspect'
  expect_not_log 'aborted'
  count=`grep '^configured' "${TEST_log}" | wc -l`
  [ "${count}" -eq 2 ] || fail "Expected 2 configured events, found $count."
}

function test_failing_aspect() {
  (bazel build --build_event_text_file=$TEST_log \
    --aspects=failingaspect.bzl%failing_aspect \
    --output_groups=aspect-out \
    pkg:output_files_and_tags && fail "expected failure") || true
  expect_log 'aspect.*failing_aspect'
  expect_log '^finished'
}

function test_build_only() {
  # When building but not testing a test, there won't be a test summary
  # (as nothing was tested), so it should not be announced.
  # Still, no event should only be chained in by progress events.
  bazel build --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel build failed"
  expect_not_log 'aborted'
  expect_not_log 'test_summary '
  # Build Finished
  expect_log 'build_finished'
  expect_log 'finish_time'
  expect_log 'SUCCESS'
}

function test_query() {
  # Verify that at least a minimally meaningful event stream is generated
  # for non-build. In particular, we expect bazel not to crash.
  bazel query --build_event_text_file=$TEST_log 'tests(//...)' \
    || fail "bazel query failed"
  expect_log '^started'
  expect_log 'command: "query"'
  expect_log 'args: "--build_event_text_file='
  expect_log 'build_finished'
  expect_not_log 'aborted'
  # For query, we also expect the full output to be contained in the protocol,
  # as well as a proper finished event.
  expect_log '//pkg:true'
  expect_log '//pkg:slow'
  expect_log '^finished'
  expect_log 'name: "SUCCESS"'
}

function test_command_whitelisting() {
  # We expect the "help" command to not generate a build-event stream,
  # but the "build" command to do.
  bazel shutdown
  rm -f bep.txt
  bazel help --build_event_text_file=bep.txt || fail "bazel help failed"
  ( [ -f bep.txt ] && fail "bazel help generated a build-event file" ) || :
  bazel version --build_event_text_file=bep.txt || fail "bazel help failed"
  ( [ -f bep.txt ] && fail "bazel version generated a build-event file" ) || :
  bazel build --build_event_text_file=bep.txt //pkg:true \
      || fail "bazel build failed"
  [ -f bep.txt ] || fail "build did not generate requested build-event file"
  bazel shutdown
}

function test_multiple_transports() {
  # Verifies usage of multiple build event transports at the same time
    outdir=$(mktemp -d ${TEST_TMPDIR}/bazel.XXXXXXXX)
    bazel test \
      --build_event_text_file=${outdir}/test_multiple_transports.txt \
      --build_event_binary_file=${outdir}/test_multiple_transports.bin \
      --build_event_json_file=${outdir}/test_multiple_transports.json \
      pkg:suite || fail "bazel test failed"
  [ -f ${outdir}/test_multiple_transports.txt ] || fail "Missing expected file test_multiple_transports.txt"
  [ -f ${outdir}/test_multiple_transports.bin ] || fail "Missing expected file test_multiple_transports.bin"
  [ -f ${outdir}/test_multiple_transports.json ] || fail "Missing expected file test_multiple_transports.bin"
}

function test_basic_json() {
  # Verify that the json transport writes json files
  bazel test --build_event_json_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  # check for some typical fragments that would be encoded differently in the
  # proto text format.
  expect_log '"started"'
  expect_log '"id"'
  expect_log '"children" *: *\['
  expect_log '"overallSuccess": *true'
}

function test_root_cause_early() {
  (bazel build --build_event_text_file=$TEST_log \
         pkg:fails_to_build && fail "build failure expected") || true
  # We expect precisely one action being reported (the failed one) and
  # precisely on report on a completed target; moreover, the action has
  # to be reported first.
  expect_log_once '^action'
  expect_log_once '^completed'
  expect_not_log 'success'
  local naction=`grep -n '^action' $TEST_log | cut -f 1 -d :`
  local ncomplete=`grep -n '^completed' $TEST_log | cut -f 1 -d :`
  [ $naction -lt $ncomplete ] \
      || fail "failed action not before compelted target"
}

function test_action_conf() {
  # Verify that the expected configurations for actions are reported.
  # The example contains a configuration transition (from building for
  # target to building for host). As the action fails, we expect the
  # configuration of the action to be reported as well.
  (bazel build --build_event_text_file=$TEST_log \
         -k failingtool/... && fail "build failure expected") || true
  count=`grep '^configuration' "${TEST_log}" | wc -l`
  [ "${count}" -eq 2 ] || fail "Expected 2 configurations, found $count."
}

function test_loading_failure() {
  # Verify that if loading fails, this is properly reported as the
  # reason for the target expansion event not resulting in targets
  # being expanded.
  (bazel build --build_event_text_file=$TEST_log \
         //does/not/exist && fail "build failure expected") || true
  expect_log_once '^progress '
  expect_log_once 'aborted'
  expect_log_once 'reason: LOADING_FAILURE'
  expect_log 'description.*BUILD file not found on package path'
  expect_not_log 'expanded'
}

function test_visibility_failure() {
  bazel shutdown
  (bazel build --build_event_text_file=$TEST_log \
         //visibility:cannotsee && fail "build failure expected") || true
  expect_log_once 'reason: ANALYSIS_FAILURE'
  expect_log_once '^aborted'

  # The same should hold true, if the server has already analyzed the target
  (bazel build --build_event_text_file=$TEST_log \
         //visibility:cannotsee && fail "build failure expected") || true
  expect_log_once 'reason: ANALYSIS_FAILURE'
  expect_log_once '^aborted'
}

function test_loading_failure_keep_going() {
  (bazel build --build_event_text_file=$TEST_log \
         -k //does/not/exist && fail "build failure expected") || true
  expect_log_once 'aborted'
  expect_log_once 'reason: LOADING_FAILURE'
  expect_log_once '^expanded'
  expect_log 'description.*BUILD file not found on package path'
}

# TODO(aehlig): readd, once we stop reporting the important artifacts
#               for every target completion
#
# function test_artifact_dedup() {
#   bazel build --build_event_text_file=$TEST_log \
#       pkg:innergroup pkg:outergroup \
#   || fail "bazel build failed"
#   expect_log_once "name.*sourcefileA"
#   expect_log_once "name.*sourcefileB"
#   expect_log_once "name.*sourcefileC"
#   expect_not_log 'aborted'
# }

function test_stdout_stderr_reported() {
  # Verify that bazel's stdout/stderr is included in the build event stream.

  # Make sure we generate enough output on stderr
  bazel clean --expunge
  bazel test --build_event_text_file=$TEST_log --curses=no \
        pkg:slow 2>stderr.log || fail "slowtest failed"
  # Take a line that is likely not the output of an action (possibly reported
  # independently in the stream) and still characteristic enough to not occur
  # in the stream by accident. Taking the first line mentioning the test name
  # is likely some form of progress report.
  sample_line=`cat stderr.log | grep 'slow' | head -1 | tr '[]' '..'`
  echo "Sample regexp of stderr: ${sample_line}"
  expect_log "stderr.*$sample_line"
}

function test_srcfiles() {
  # Even if the build target is a source file, the stream should be correctly
  # and bazel shouldn't crash.
    bazel build --build_event_text_file=$TEST_log \
          pkg:somesourcefile || fail "build failed"
  expect_log 'SUCCESS'
  expect_log_once '^configuration'
  expect_not_log 'aborted'
}

function test_test_fails_to_build() {
  (bazel test --build_event_text_file=$TEST_log \
         pkg:test_that_fails_to_build && fail "test failure expected") || true
  expect_not_log '^test_summary'
  expect_log 'last_message: true'
  expect_log 'BUILD_FAILURE'
}

function test_no_tests_found() {
  (bazel test --build_event_text_file=$TEST_log \
         pkg:not_a_test && fail "failure expected") || true
  expect_not_log '^test_summary'
  expect_log 'last_message: true'
  expect_log 'NO_TESTS_FOUND'
}

function test_no_tests_found_build_failure() {
  (bazel test -k --build_event_text_file=$TEST_log \
         pkg:not_a_test pkg:fails_to_build && fail "failure expected") || true
  expect_not_log '^test_summary'
  expect_log 'last_message: true'
  expect_log 'yet testing was requested'
  expect_log 'BUILD_FAILURE'
}

run_suite "Integration tests for the build event stream"
