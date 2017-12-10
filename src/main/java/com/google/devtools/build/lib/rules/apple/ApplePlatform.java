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

package com.google.devtools.build.lib.rules.apple;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.skyframe.serialization.EnumCodec;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import java.util.HashMap;
import java.util.Locale;
import javax.annotation.Nullable;

/** An enum that can be used to distinguish between various apple platforms. */
@SkylarkModule(
  name = "apple_platform",
  category = SkylarkModuleCategory.NONE,
  doc = "Corresponds to Xcode's notion of a platform as would be found in "
      + "<code>Xcode.app/Contents/Developer/Platforms</code>. Each platform represents an Apple "
      + "platform type (such as iOS or tvOS) combined with one or more related CPU "
      + "architectures. For example, the iOS simulator platform supports <code>x86_64</code> and "
      + "<code>i386</code> architectures.<p>"
      + "Specific instances of this type can be retrieved from the fields of the "
      + "<a href='apple_common.html#platform'>apple_common.platform</a> struct:<br><ul>"
      + "<li><code>apple_common.platform.ios_device</code></li>"
      + "<li><code>apple_common.platform.ios_simulator</code></li>"
      + "<li><code>apple_common.platform.macos</code></li>"
      + "<li><code>apple_common.platform.tvos_device</code></li>"
      + "<li><code>apple_common.platform.tvos_simulator</code></li>"
      + "<li><code>apple_common.platform.watchos_device</code></li>"
      + "<li><code>apple_common.platform.watchos_simulator</code></li>"
      + "</ul><p>"
      + "More commonly, however, the <a href='apple.html'>apple</a> configuration fragment has "
      + "fields/methods that allow rules to determine the platform for which a target is being "
      + "built.<p>"
      + "Example:<br>"
      + "<pre class='language-python'>\n"
      + "p = apple_common.platform.ios_device\n"
      + "print(p.name_in_plist)  # 'iPhoneOS'\n"
      + "</pre>"
)
@Immutable
public enum ApplePlatform implements SkylarkValue {
  IOS_DEVICE("ios_device", "iPhoneOS", PlatformType.IOS, true),
  IOS_SIMULATOR("ios_simulator", "iPhoneSimulator", PlatformType.IOS, false),
  MACOS("macos", "MacOSX", PlatformType.MACOS, true),
  TVOS_DEVICE("tvos_device", "AppleTVOS", PlatformType.TVOS, true),
  TVOS_SIMULATOR("tvos_simulator", "AppleTVSimulator", PlatformType.TVOS, false),
  WATCHOS_DEVICE("watchos_device", "WatchOS", PlatformType.WATCHOS, true),
  WATCHOS_SIMULATOR("watchos_simulator", "WatchSimulator", PlatformType.WATCHOS, false);

  private static final ImmutableSet<String> IOS_SIMULATOR_TARGET_CPUS =
      ImmutableSet.of("ios_x86_64", "ios_i386");
  private static final ImmutableSet<String> IOS_DEVICE_TARGET_CPUS =
      ImmutableSet.of("ios_armv6", "ios_arm64", "ios_armv7", "ios_armv7s");
  private static final ImmutableSet<String> WATCHOS_SIMULATOR_TARGET_CPUS =
      ImmutableSet.of("watchos_i386");
  private static final ImmutableSet<String> WATCHOS_DEVICE_TARGET_CPUS =
      ImmutableSet.of("watchos_armv7k");
  private static final ImmutableSet<String> TVOS_SIMULATOR_TARGET_CPUS =
      ImmutableSet.of("tvos_x86_64");
  private static final ImmutableSet<String> TVOS_DEVICE_TARGET_CPUS =
      ImmutableSet.of("tvos_arm64");
  private static final ImmutableSet<String> MACOS_TARGET_CPUS =
      ImmutableSet.of("darwin_x86_64");

  private static final ImmutableSet<String> BIT_32_TARGET_CPUS =
      ImmutableSet.of("ios_i386", "ios_armv7", "ios_armv7s", "watchos_i386", "watchos_armv7k");

  private final String skylarkKey;
  private final String nameInPlist;
  private final PlatformType platformType;
  private final boolean isDevice;

  ApplePlatform(
      String skylarkKey, String nameInPlist, PlatformType platformType, boolean isDevice) {
    this.skylarkKey = skylarkKey;
    this.nameInPlist = Preconditions.checkNotNull(nameInPlist);
    this.platformType = platformType;
    this.isDevice = isDevice;
  }

  /** Returns the platform type of this platform. */
  @SkylarkCallable(
    name = "platform_type",
    doc = "Returns the platform type of this platform.",
    structField = true
  )
  public PlatformType getType() {
    return platformType;
  }

  /**
   * Returns true if this platform is a device platform, or false if this is a simulator platform.
   */
  @SkylarkCallable(
    name = "is_device",
    doc = "Returns <code>True</code> if this platform is a device platform or <code>False</code> "
        + "if it is a simulator platform.",
    structField = true
  )
  public boolean isDevice() {
    return isDevice;
  }

  /**
   * Returns the name of the "platform" as it appears in the CFBundleSupportedPlatforms plist
   * setting.
   */
  @SkylarkCallable(name = "name_in_plist", structField = true,
    doc = "The name of the platform as it appears in the <code>CFBundleSupportedPlatforms</code> "
        + "entry of an Info.plist file and in Xcode's platforms directory, without the extension "
        + "(for example, <code>iPhoneOS</code> or <code>iPhoneSimulator</code>).<br>"
        + "This name, when converted to lowercase (e.g., <code>iphoneos</code>, "
        + "<code>iphonesimulator</code>), can be passed to Xcode's command-line tools like "
        + "<code>ibtool</code> and <code>actool</code> when they expect a platform name.")
  public String getNameInPlist() {
    return nameInPlist;
  }

  /**
   * Returns the name of the "platform" as it appears in the plist when it appears in all-lowercase.
   */
  public String getLowerCaseNameInPlist() {
    return nameInPlist.toLowerCase(Locale.US);
  }

  @Nullable
  private static ApplePlatform forTargetCpuNullable(String targetCpu) {
    if (IOS_SIMULATOR_TARGET_CPUS.contains(targetCpu)) {
      return IOS_SIMULATOR;
    } else if (IOS_DEVICE_TARGET_CPUS.contains(targetCpu)) {
      return IOS_DEVICE;
    } else if (WATCHOS_SIMULATOR_TARGET_CPUS.contains(targetCpu)) {
      return WATCHOS_SIMULATOR;
    } else if (WATCHOS_DEVICE_TARGET_CPUS.contains(targetCpu)) {
      return WATCHOS_DEVICE;
    } else if (TVOS_SIMULATOR_TARGET_CPUS.contains(targetCpu)) {
      return TVOS_SIMULATOR;
    } else if (TVOS_DEVICE_TARGET_CPUS.contains(targetCpu)) {
      return TVOS_DEVICE;
    } else if (MACOS_TARGET_CPUS.contains(targetCpu)) {
      return MACOS;
    } else {
      return null;
    }
  }

  /**
   * Returns true if the platform for the given target cpu and platform type is a known 32-bit
   * architecture.
   *
   * @param platformType platform type that the given cpu value is implied for
   * @param arch architecture representation, such as 'arm64'
   */
  public static boolean is32Bit(PlatformType platformType, String arch) {
    return BIT_32_TARGET_CPUS.contains(cpuStringForTarget(platformType, arch));
  }

  /**
   * Returns the platform cpu string for the given target cpu and platform type.
   *
   * @param platformType platform type that the given cpu value is implied for
   * @param arch architecture representation, such as 'arm64'
   */
  public static String cpuStringForTarget(PlatformType platformType, String arch) {
    switch (platformType) {
      case MACOS:
        return String.format("darwin_%s", arch);
      default:
        return String.format("%s_%s", platformType.toString(), arch);
    }
  }

  /**
   * Returns the platform for the given target cpu and platform type.
   *
   * @param platformType platform type that the given cpu value is implied for
   * @param arch architecture representation, such as 'arm64'
   * @throws IllegalArgumentException if there is no valid apple platform for the given target cpu
   */
  public static ApplePlatform forTarget(PlatformType platformType, String arch) {
    return forTargetCpu(cpuStringForTarget(platformType, arch));
  }

  /**
   * Returns the platform for the given target cpu.
   *
   * @param targetCpu cpu value with platform type prefix, such as 'ios_arm64'
   * @throws IllegalArgumentException if there is no valid apple platform for the given target cpu
   */
  public static ApplePlatform forTargetCpu(String targetCpu) {
    ApplePlatform platform = forTargetCpuNullable(targetCpu);
    if (platform != null) {
      return platform;
    } else {
      throw new IllegalArgumentException(
          "No supported apple platform registered for target cpu " + targetCpu);
    }
  }

  /**
   * Returns true if the given target cpu is an apple platform.
   */
  public static boolean isApplePlatform(String targetCpu) {
    return forTargetCpuNullable(targetCpu) != null;
  }

  /** Returns a Skylark struct that contains the instances of this enum. */
  public static Info getSkylarkStruct() {
    Provider constructor = new NativeProvider<Info>(Info.class, "platforms") {};
    HashMap<String, Object> fields = new HashMap<>();
    for (ApplePlatform type : values()) {
      fields.put(type.skylarkKey, type);
    }
    return SkylarkInfo.fromMap(constructor, fields, Location.BUILTIN);
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append(toString());
  }

  /**
   * Value used to describe Apple platform "type". A {@link ApplePlatform} is implied from a
   * platform type (for example, watchOS) together with a cpu value (for example, armv7).
   */
  // TODO(cparsons): Use these values in static retrieval methods in this class.
  @SkylarkModule(
    name = "apple_platform_type",
    category = SkylarkModuleCategory.NONE,
    doc = "Describes an Apple \"platform type\", such as iOS, macOS, tvOS, or watchOS. This is "
        + "distinct from a \"platform\", which is the platform type combined with one or more CPU "
        + "architectures.<p>"
        + "Specific instances of this type can be retrieved by accessing the fields of the "
        + "<a href='apple_common.html#platform_type'>apple_common.platform_type</a>:<br><ul>"
        + "<li><code>apple_common.platform_type.ios</code></li>"
        + "<li><code>apple_common.platform_type.macos</code></li>"
        + "<li><code>apple_common.platform_type.tvos</code></li>"
        + "<li><code>apple_common.platform_type.watchos</code></li>"
        + "</ul><p>"
        + "Likewise, the platform type of an existing platform value can be retrieved using its "
        + "<code>platform_type</code> field.<p>"
        + "Platform types can be converted to a lowercase string (e.g., <code>ios</code> or "
        + "<code>macos</code>) using the <a href='globals.html#str'>str</a> function."
  )
  @Immutable
  public enum PlatformType implements SkylarkValue {
    IOS("ios"),
    WATCHOS("watchos"),
    TVOS("tvos"),
    MACOS("macos");

    /**
     * The key used to access the enum value as a field in the Skylark apple_common.platform_type
     * struct.
     */
    private final String skylarkKey;

    PlatformType(String skylarkKey) {
      this.skylarkKey = skylarkKey;
    }

    @Override
    public String toString() {
      return name().toLowerCase();
    }

    /**
     * Returns the {@link PlatformType} with given name (case insensitive).
     *
     * @throws IllegalArgumentException if the name does not match a valid platform type.
     */
    public static PlatformType fromString(String name) {
      for (PlatformType platformType : PlatformType.values()) {
        if (name.equalsIgnoreCase(platformType.toString())) {
          return platformType;
        }
      }
      throw new IllegalArgumentException(String.format("Unsupported platform type \"%s\"", name));
    }

    /** Returns a Skylark struct that contains the instances of this enum. */
    public static Info getSkylarkStruct() {
      Provider constructor = new NativeProvider<Info>(Info.class, "platform_types") {};
      HashMap<String, Object> fields = new HashMap<>();
      for (PlatformType type : values()) {
        fields.put(type.skylarkKey, type);
      }
      return SkylarkInfo.fromMap(constructor, fields, Location.BUILTIN);
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append(toString());
    }

    static final EnumCodec<PlatformType> CODEC = new EnumCodec<>(PlatformType.class);
  }
}
