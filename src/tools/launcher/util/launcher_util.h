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

#ifndef BAZEL_SRC_TOOLS_LAUNCHER_UTIL_LAUNCHER_UTIL_H_
#define BAZEL_SRC_TOOLS_LAUNCHER_UTIL_LAUNCHER_UTIL_H_

#define PRINTF_ATTRIBUTE(string_index, first_to_check)

#include <string>

namespace bazel {
namespace launcher {

std::string GetLastErrorString();

// Prints the specified error message and exits nonzero.
__declspec(noreturn) void die(const char* format, ...) PRINTF_ATTRIBUTE(1, 2);

// Prints the specified error message.
void PrintError(const char* format, ...) PRINTF_ATTRIBUTE(1, 2);

// Strip the .exe extension from binary path.
//
// On Windows, if the binary path is foo/bar/bin.exe then return foo/bar/bin
std::string GetBinaryPathWithoutExtension(const std::string& binary);

// Add exectuable extension to binary path
//
// On Windows, if the binary path is foo/bar/bin then return foo/bar/bin.exe
std::string GetBinaryPathWithExtension(const std::string& binary);

// Escape a command line argument.
//
// If the argument has space, then we quote it.
// Escape \ to \\
// Escape " to \"
std::string GetEscapedArgument(const std::string& argument);

// Convert a path to an absolute Windows path with \\?\ prefix.
// This method will print an error and exit if it cannot convert the path.
std::wstring AsAbsoluteWindowsPath(const char* path);

// Check if a file exists at a given path.
bool DoesFilePathExist(const char* path);

// Check if a directory exists at a given path.
bool DoesDirectoryPathExist(const char* path);

// Delete a file at a given path.
bool DeleteFileByPath(const char* path);

// Get the value of a specific environment variable
//
// Return true if succeeded and the result is stored in buffer.
// Return false if the environment variable doesn't exist or the value is empty.
bool GetEnv(const std::string& env_name, std::string* buffer);

// Set the value of a specific environment variable
//
// Return true if succeeded, otherwise false.
bool SetEnv(const std::string& env_name, const std::string& value);

// Return a random string with a given length.
// The string consists of a-zA-Z0-9
std::string GetRandomStr(size_t len);

}  // namespace launcher
}  // namespace bazel

#endif  // BAZEL_SRC_TOOLS_LAUNCHER_UTIL_LAUNCHER_UTIL_H_
