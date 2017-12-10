// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.syntax;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.util.Preconditions;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Global constants and support for global namespaces of runtime functions.
 */
public final class Runtime {

  private Runtime() {}

  @SkylarkSignature(name = "True", returnType = Boolean.class,
      doc = "Literal for the boolean true.")
  private static final Boolean TRUE = true;

  @SkylarkSignature(name = "False", returnType = Boolean.class,
      doc = "Literal for the boolean false.")
  private static final Boolean FALSE = false;

  /** There should be only one instance of this type to allow "== None" tests. */
  @SkylarkModule(
    name = "NoneType",
    documented = false,
    doc = "Unit type, containing the unique value None."
  )
  @Immutable
  public static final class NoneType implements SkylarkValue {
    private NoneType() {}

    @Override
    public String toString() {
      return "None";
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("None");
    }
  }

  /** Marker for unbound variables in cases where neither Java null nor Skylark None is suitable. */
  @Immutable
  public static final class UnboundMarker implements SkylarkValue {
    private UnboundMarker() {}

    @Override
    public String toString() {
      return "<unbound>";
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<unbound>");
    }
  }

  @SkylarkSignature(name = "<unbound>", returnType = UnboundMarker.class, documented = false,
      doc = "Marker for unbound values in cases where neither Skylark None nor Java null can do.")
  public static final UnboundMarker UNBOUND = new UnboundMarker();

  @SkylarkSignature(name = "None", returnType = NoneType.class,
      doc = "Literal for the None value.")
  public static final NoneType NONE = new NoneType();

  @SkylarkSignature(name = "PACKAGE_NAME", returnType = String.class,
      doc = "<b>Deprecated. Use <a href=\"native.html#package_name\">package_name()</a> "
          + "instead.</b> The name of the package being evaluated. "
          + "For example, in the BUILD file <code>some/package/BUILD</code>, its value "
          + "will be <code>some/package</code>. "
          + "If the BUILD file calls a function defined in a .bzl file, PACKAGE_NAME will "
          + "match the caller BUILD file package. "
          + "In .bzl files, do not access PACKAGE_NAME at the file-level (outside of functions), "
          + "either directly or by calling a function at the file-level that accesses "
          + "PACKAGE_NAME (PACKAGE_NAME is only defined during BUILD file evaluation)."
          + "Here is an example of a .bzl file:<br>"
          + "<pre class=language-python>"
          + "# a = PACKAGE_NAME  # not allowed outside functions\n"
          + "def extension():\n"
          + "  return PACKAGE_NAME</pre>"
          + "In this case, <code>extension()</code> can be called from a BUILD file (even "
          + "indirectly), but not in a file-level expression in the .bzl file. "
          + "When implementing a rule, use <a href=\"ctx.html#label\">ctx.label</a> to know where "
          + "the rule comes from. ")
  public static final String PKG_NAME = "PACKAGE_NAME";

  @SkylarkSignature(name = "REPOSITORY_NAME", returnType = String.class,
      doc = "<b>Deprecated. Use <a href=\"native.html#repository_name\">repository_name()</a> "
          + "instead.</b> The name of the repository the rule or build extension is called from. "
          + "For example, in packages that are called into existence by the WORKSPACE stanza "
          + "<code>local_repository(name='local', path=...)</code> it will be set to "
          + "<code>@local</code>. In packages in the main repository, it will be empty. "
          + "It can only be accessed in functions (transitively) called from BUILD files, i.e. "
          + "it follows the same restrictions as <a href=\"#PACKAGE_NAME\">PACKAGE_NAME</a>")
  public static final String REPOSITORY_NAME = "REPOSITORY_NAME";

  /**
   * Set up a given environment for supported class methods.
   */
  static Environment setupConstants(Environment env) {
    // In Python 2.x, True and False are global values and can be redefined by the user.
    // In Python 3.x, they are keywords. We implement them as values, for the sake of
    // simplicity. We define them as Boolean objects.
    return env.setup("False", FALSE).setup("True", TRUE).setup("None", NONE);
  }


  /** Global registry of functions associated to a class or namespace */
  private static final Map<Class<?>, Map<String, BaseFunction>> functions = new HashMap<>();

  /**
   * Registers a function with namespace to this global environment.
   */
  public static void registerFunction(Class<?> nameSpace, BaseFunction function) {
    Preconditions.checkNotNull(nameSpace);
    Preconditions.checkArgument(nameSpace.equals(getCanonicalRepresentation(nameSpace)));
    Preconditions.checkArgument(
        getCanonicalRepresentation(function.getObjectType()).equals(nameSpace));
    functions.computeIfAbsent(nameSpace, k -> new HashMap<String, BaseFunction>());
    functions.get(nameSpace).put(function.getName(), function);
  }

  /**
   * Returns the canonical representation of the given class, i.e. the super class for which any
   * functions were registered.
   *
   * <p>Currently, this is only necessary for mapping the different subclasses of {@link
   * java.util.Map} to the interface.
   */
  // TODO(bazel-team): make everything a SkylarkValue, and remove this function.
  public static Class<?> getCanonicalRepresentation(Class<?> clazz) {
    if (String.class.isAssignableFrom(clazz)) {
      return MethodLibrary.StringModule.class;
    }
    return EvalUtils.getSkylarkType(clazz);
  }


  static Map<String, BaseFunction> getNamespaceFunctions(Class<?> nameSpace) {
    return functions.get(getCanonicalRepresentation(nameSpace));
  }

  /**
   * Registers global fields with SkylarkSignature into the specified Environment.
   * @param env the Environment into which to register fields.
   * @param moduleClass the Class object containing globals.
   */
  public static void registerModuleGlobals(Environment env, Class<?> moduleClass) {
    try {
      if (moduleClass.isAnnotationPresent(SkylarkModule.class)) {
        env.setup(
            moduleClass.getAnnotation(SkylarkModule.class).name(),
            moduleClass.getConstructor().newInstance());
      }
      for (Field field : moduleClass.getDeclaredFields()) {
        if (field.isAnnotationPresent(SkylarkSignature.class)) {
          // Fields in Skylark modules are sometimes private.
          // Nevertheless they have to be annotated with SkylarkSignature.
          field.setAccessible(true);
          SkylarkSignature annotation = field.getAnnotation(SkylarkSignature.class);
          Object value = field.get(null);
          // Ignore function factories and non-global functions
          if (!(value instanceof BuiltinFunction.Factory
              || (value instanceof BaseFunction
                  && !annotation.objectType().equals(Object.class)))) {
            env.setup(annotation.name(), value);
          }
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Returns the function of the namespace of the given name or null of it does not exists.
   */
  public static BaseFunction getFunction(Class<?> nameSpace, String name) {
    Map<String, BaseFunction> nameSpaceFunctions = getNamespaceFunctions(nameSpace);
    return nameSpaceFunctions != null ? nameSpaceFunctions.get(name) : null;
  }

  /**
   * Returns the function names registered with the namespace.
   */
  public static Set<String> getFunctionNames(Class<?> nameSpace) {
    Map<String, BaseFunction> nameSpaceFunctions = getNamespaceFunctions(nameSpace);
    return nameSpaceFunctions != null ? nameSpaceFunctions.keySet() : ImmutableSet.of();
  }

  static void setupMethodEnvironment(
      Environment env, Iterable<BaseFunction> functions) {
    for (BaseFunction function : functions) {
      env.setup(function.getName(), function);
    }
  }
}
