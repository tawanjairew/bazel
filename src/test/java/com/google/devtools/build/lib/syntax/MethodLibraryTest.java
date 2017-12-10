// Copyright 2006 The Bazel Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.util.EvaluationTestCase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for MethodLibrary.
 */
@RunWith(JUnit4.class)
public class MethodLibraryTest extends EvaluationTestCase {

  private static final String LINE_SEPARATOR = System.lineSeparator();

  @Before
  public final void setFailFast() throws Exception {
    setFailFast(true);
  }

  @Test
  public void testStackTraceLocation() throws Exception {
    new SkylarkTest()
        .testIfErrorContains(
            "Traceback (most recent call last):"
                + LINE_SEPARATOR
                + "\tFile \"\", line 8"
                + LINE_SEPARATOR
                + "\t\tfoo()"
                + LINE_SEPARATOR
                + "\tFile \"\", line 2, in foo"
                + LINE_SEPARATOR
                + "\t\tbar(1)"
                + LINE_SEPARATOR
                + "\tFile \"\", line 7, in bar"
                + LINE_SEPARATOR
                + "\t\t\"test\".index(x)",
            "def foo():",
            "  bar(1)",
            "def bar(x):",
            "  if x == 1:",
            "    a = x",
            "    b = 2",
            "    'test'.index(x)",
            "foo()");
  }

  @Test
  public void testStackTraceWithIf() throws Exception {
    new SkylarkTest()
        .testIfErrorContains(
            "File \"\", line 5"
                + LINE_SEPARATOR
                + "\t\tfoo()"
                + LINE_SEPARATOR
                + "\tFile \"\", line 3, in foo"
                + LINE_SEPARATOR
                + "\t\ts[0]",
            "def foo():",
            "  s = depset()",
            "  if s[0] == 1:",
            "    x = 1",
            "foo()");
  }

  @Test
  public void testStackTraceWithAugmentedAssignment() throws Exception {
    new SkylarkTest()
        .testIfErrorContains(
            "File \"\", line 4"
                + LINE_SEPARATOR
                + "\t\tfoo()"
                + LINE_SEPARATOR
                + "\tFile \"\", line 3, in foo"
                + LINE_SEPARATOR
                + "\t\ts += \"2\""
                + LINE_SEPARATOR
                + "unsupported operand type(s) for +: 'int' and 'string'",
            "def foo():",
            "  s = 1",
            "  s += '2'",
            "foo()");
  }

  @Test
  public void testStackTraceSkipBuiltInOnly() throws Exception {
    // The error message should not include the stack trace when there is
    // only one built-in function.
    new BothModesTest()
        .testIfExactError(
            "argument 'sub' has type 'int', but should be 'string'\n"
                + "in call to builtin method string.index(sub, start, end)",
            "'test'.index(1)");
  }

  @Test
  public void testStackTrace() throws Exception {
    // Unlike SkylarintegrationTests#testStackTraceErrorInFunction(), this test
    // has neither a BUILD nor a bzl file.
    new SkylarkTest()
        .testIfExactError(
            "Traceback (most recent call last):"
                + LINE_SEPARATOR
                + "\tFile \"\", line 6"
                + LINE_SEPARATOR
                + "\t\tfoo()"
                + LINE_SEPARATOR
                + "\tFile \"\", line 2, in foo"
                + LINE_SEPARATOR
                + "\t\tbar(1)"
                + LINE_SEPARATOR
                + "\tFile \"\", line 5, in bar"
                + LINE_SEPARATOR
                + "\t\t\"test\".index(x)"
                + LINE_SEPARATOR
                + "argument 'sub' has type 'int', but should be 'string'\n"
                + "in call to builtin method string.index(sub, start, end)",
            "def foo():",
            "  bar(1)",
            "def bar(x):",
            "  if 1 == 1:",
            "    'test'.index(x)",
            "foo()");
  }

  @Test
  public void testBuiltinFunctionErrorMessage() throws Exception {
    new BothModesTest()
        .testIfErrorContains("substring \"z\" not found in \"abc\"", "'abc'.index('z')")
        .testIfErrorContains(
            "argument 'sub' has type 'int', but should be 'string'\n"
                + "in call to builtin method string.startswith(sub, start, end)",
            "'test'.startswith(1)")
        .testIfErrorContains(
            "expected value of type 'list(object)' for parameter args in dict(), "
                + "but got \"a\" (string)",
            "dict('a')");
  }

  @Test
  public void testHasAttr() throws Exception {
    new SkylarkTest()
        .testStatement("hasattr(depset(), 'union')", Boolean.TRUE)
        .testStatement("hasattr('test', 'count')", Boolean.TRUE)
        .testStatement("hasattr(dict(a = 1, b = 2), 'items')", Boolean.TRUE)
        .testStatement("hasattr({}, 'items')", Boolean.TRUE);
  }

  @Test
  public void testGetAttrMissingField() throws Exception {
    new SkylarkTest()
        .testIfExactError(
            "object of type 'string' has no attribute \"not_there\"",
            "getattr('a string', 'not_there')")
        .testStatement("getattr('a string', 'not_there', 'use this')", "use this")
        .testStatement("getattr('a string', 'not there', None)", Runtime.NONE);
  }

  @Test
  public void testGetAttrWithMethods() throws Exception {
    String msg =
        "object of type 'string' has no attribute \"count\", however, "
            + "a method of that name exists";
    new SkylarkTest()
        .testIfExactError(msg, "getattr('a string', 'count')")
        .testStatement("getattr('a string', 'count', 'default')", "default");
  }

  @Test
  public void testDir() throws Exception {
    new SkylarkTest()
        .testStatement(
            "str(dir({}))",
            "[\"clear\", \"get\", \"items\", \"keys\","
                + " \"pop\", \"popitem\", \"setdefault\", \"update\", \"values\"]");
  }

  @Test
  public void testBoolean() throws Exception {
    new BothModesTest().testStatement("False", Boolean.FALSE).testStatement("True", Boolean.TRUE);
  }

  @Test
  public void testBooleanUnsupportedOperationFails() throws Exception {
    new BothModesTest()
        .testIfErrorContains("unsupported operand type(s) for +: 'bool' and 'bool'", "True + True");
  }

  @Test
  public void testPyStringJoin() throws Exception {
    new BothModesTest().testStatement("'-'.join(['a', 'b', 'c'])", "a-b-c");
  }

  @Test
  public void testPyStringGlobalJoin() throws Exception {
    new BothModesTest()
        .testIfErrorContains("name 'join' is not defined", "join(' ', ['a', 'b', 'c'])")
        .testStatement("' '.join(['a', 'b', 'c'])", "a b c");
  }

  @Test
  public void testPyStringJoinCompr() throws Exception {
    new BothModesTest()
        .testStatement("''.join([(x + '*') for x in ['a', 'b', 'c']])", "a*b*c*")
        .testStatement(
            "''.join([(y + '*' + z + '|') " + "for y in ['a', 'b', 'c'] for z in ['d', 'e']])",
            "a*d|a*e|b*d|b*e|c*d|c*e|");
  }

  @Test
  public void testPyStringLower() throws Exception {
    new BothModesTest().testStatement("'Blah Blah'.lower()", "blah blah");
  }

  @Test
  public void testPyStringUpper() throws Exception {
    new BothModesTest()
        .testStatement("'ein bier'.upper()", "EIN BIER")
        .testStatement("''.upper()", "");
  }

  @Test
  public void testPyStringReplace() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.replace('a', 'e')", "benene")
        .testStatement("'banana'.replace('a', '$()')", "b$()n$()n$()")
        .testStatement("'banana'.replace('a', '$')", "b$n$n$")
        .testStatement("'banana'.replace('a', '\\\\')", "b\\n\\n\\")
        .testStatement("'b$()n$()n$()'.replace('$()', '$($())')", "b$($())n$($())n$($())")
        .testStatement("'b\\\\n\\\\n\\\\'.replace('\\\\', '$()')", "b$()n$()n$()");
  }

  @Test
  public void testPyStringReplace2() throws Exception {
    new BothModesTest().testStatement("'banana'.replace('a', 'e', 2)", "benena");
  }

  @Test
  public void testPyStringTitle() throws Exception {
    new BothModesTest()
        .testStatement("'this is a very simple test'.title()", "This Is A Very Simple Test");
    new BothModesTest()
        .testStatement("'Do We Keep Capital Letters?'.title()", "Do We Keep Capital Letters?");
    new BothModesTest()
        .testStatement(
            "'this isn\\'t just an ol\\' apostrophe test'.title()",
            "This Isn'T Just An Ol' Apostrophe Test");
    new BothModesTest()
        .testStatement(
            "'Let us test crazy characters: _bla.exe//foo:bla(test$class)'.title()",
            "Let Us Test Crazy Characters: _Bla.Exe//Foo:Bla(Test$Class)");
    new BothModesTest().testStatement("'any germans here? äöü'.title()", "Any Germans Here? Äöü");
    new BothModesTest()
        .testStatement(
            "'WE HAve tO lOWERCASE soMEthING heRE, AI?'.title()",
            "We Have To Lowercase Something Here, Ai?");
    new BothModesTest()
        .testStatement("'wh4t ab0ut s0me numb3rs'.title()", "Wh4T Ab0Ut S0Me Numb3Rs");
  }

  @Test
  public void testCapitalize() throws Exception {
    new BothModesTest()
        .testStatement("'hello world'.capitalize()", "Hello world")
        .testStatement("'HELLO WORLD'.capitalize()", "Hello world")
        .testStatement("''.capitalize()", "")
        .testStatement("'12 lower UPPER 34'.capitalize()", "12 lower upper 34");
  }

  @Test
  public void testPyStringRfind() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.rfind('na')", 4)
        .testStatement("'banana'.rfind('na', 3, 1)", -1)
        .testStatement("'aaaa'.rfind('a', 1, 1)", -1)
        .testStatement("'aaaa'.rfind('a', 1, 50)", 3)
        .testStatement("'aaaa'.rfind('aaaaa')", -1)
        .testStatement("'abababa'.rfind('ab', 1)", 4)
        .testStatement("'abababa'.rfind('ab', 0)", 4)
        .testStatement("'abababa'.rfind('ab', -1)", -1)
        .testStatement("'abababa'.rfind('ab', -2)", -1)
        .testStatement("'abababa'.rfind('ab', -3)", 4)
        .testStatement("'abababa'.rfind('ab', 0, 1)", -1)
        .testStatement("'abababa'.rfind('ab', 0, 2)", 0)
        .testStatement("'abababa'.rfind('ab', -1000)", 4)
        .testStatement("'abababa'.rfind('ab', 1000)", -1)
        .testStatement("''.rfind('a', 1)", -1);
  }

  @Test
  public void testPyStringFind() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.find('na')", 2)
        .testStatement("'banana'.find('na', 3, 1)", -1)
        .testStatement("'aaaa'.find('a', 1, 1)", -1)
        .testStatement("'aaaa'.find('a', 1, 50)", 1)
        .testStatement("'aaaa'.find('aaaaa')", -1)
        .testStatement("'abababa'.find('ab', 1)", 2)
        .testStatement("'abababa'.find('ab', 0)", 0)
        .testStatement("'abababa'.find('ab', -1)", -1)
        .testStatement("'abababa'.find('ab', -2)", -1)
        .testStatement("'abababa'.find('ab', -3)", 4)
        .testStatement("'abababa'.find('ab', 0, 1)", -1)
        .testStatement("'abababa'.find('ab', 0, 2)", 0)
        .testStatement("'abababa'.find('ab', -1000)", 0)
        .testStatement("'abababa'.find('ab', 1000)", -1)
        .testStatement("''.find('a', 1)", -1);
  }

  @Test
  public void testPyStringIndex() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.index('na')", 2)
        .testStatement("'abababa'.index('ab', 1)", 2)
        .testIfErrorContains("substring \"foo\" not found in \"banana\"", "'banana'.index('foo')");
  }

  @Test
  public void testPyStringRIndex() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.rindex('na')", 4)
        .testStatement("'abababa'.rindex('ab', 1)", 4)
        .testIfErrorContains("substring \"foo\" not found in \"banana\"", "'banana'.rindex('foo')");
  }

  @Test
  public void testPyStringEndswith() throws Exception {
    new BothModesTest()
        .testStatement("'Apricot'.endswith('cot')", true)
        .testStatement("'a'.endswith('')", true)
        .testStatement("''.endswith('')", true)
        .testStatement("'Apricot'.endswith('co')", false)
        .testStatement("'Apricot'.endswith('co', -1)", false)
        .testStatement("'abcd'.endswith('c', -2, -1)", true)
        .testStatement("'abcd'.endswith('c', 1, 8)", false)
        .testStatement("'abcd'.endswith('d', 1, 8)", true);
  }

  @Test
  public void testPyStringStartswith() throws Exception {
    new BothModesTest()
        .testStatement("'Apricot'.startswith('Apr')", true)
        .testStatement("'Apricot'.startswith('A')", true)
        .testStatement("'Apricot'.startswith('')", true)
        .testStatement("'Apricot'.startswith('z')", false)
        .testStatement("''.startswith('')", true)
        .testStatement("''.startswith('a')", false);
  }

  @Test
  public void testPySubstring() throws Exception {
    new BothModesTest()
        .testStatement("'012345678'[0:-1]", "01234567")
        .testStatement("'012345678'[2:4]", "23")
        .testStatement("'012345678'[-5:-3]", "45")
        .testStatement("'012345678'[2:2]", "")
        .testStatement("'012345678'[2:]", "2345678")
        .testStatement("'012345678'[:3]", "012")
        .testStatement("'012345678'[-1:]", "8")
        .testStatement("'012345678'[:]", "012345678")
        .testStatement("'012345678'[-1:2]", "")
        .testStatement("'012345678'[4:2]", "");
  }

  @Test
  public void testReversedWithInvalidTypes() throws Exception {
    new BothModesTest()
        .testIfExactError("type 'NoneType' is not iterable", "reversed(None)")
        .testIfExactError("type 'int' is not iterable", "reversed(1)")
        .testIfExactError(
            "Argument to reversed() must be a sequence, not a dictionary.", "reversed({1: 3})");
    new SkylarkTest()
        .testIfExactError(
            "Argument to reversed() must be a sequence, not a depset.", "reversed(depset([1]))");
  }

  @Test
  public void testReversedWithLists() throws Exception {
    new BothModesTest()
        .testEval("reversed([])", "[]")
        .testEval("reversed([1])", "[1]")
        .testEval("reversed([1, 2, 3, 4, 5])", "[5, 4, 3, 2, 1]")
        .testEval("reversed([[1, 2], 3, 4, [5]])", "[[5], 4, 3, [1, 2]]")
        .testEval("reversed([1, 1, 1, 1, 2])", "[2, 1, 1, 1, 1]");
  }

  @Test
  public void testReversedWithStrings() throws Exception {
    new BothModesTest()
        .testEval("reversed('')", "[]")
        .testEval("reversed('a')", "['a']")
        .testEval("reversed('abc')", "['c', 'b', 'a']")
        .testEval("reversed('__test  ')", "[' ', ' ', 't', 's', 'e', 't', '_', '_']")
        .testEval("reversed('bbb')", "['b', 'b', 'b']");
  }

  @Test
  public void testReversedNoSideEffects() throws Exception {
    new SkylarkTest()
        .testEval(
            "def foo():\n"
                + "  x = ['a', 'b']\n"
                + "  y = reversed(x)\n"
                + "  y += ['c']\n"
                + "  return x\n"
                + "foo()",
            "['a', 'b']");
  }

  @Test
  public void testEquivalenceOfReversedAndSlice() throws Exception {
    String[] data = new String[] {"[]", "[1]", "[1, 2, 3]"};
    for (String toBeReversed : data) {
      new BothModesTest()
          .testEval(
              String.format("reversed(%s)", toBeReversed), String.format("%s[::-1]", toBeReversed));
    }
  }

  @Test
  public void testListSort() throws Exception {
    new BothModesTest()
        .testEval("sorted([0,1,2,3])", "[0, 1, 2, 3]")
        .testEval("sorted([])", "[]")
        .testEval("sorted([3, 2, 1, 0])", "[0, 1, 2, 3]")
        .testEval("sorted([[1], [], [2], [1, 2]])", "[[], [1], [1, 2], [2]]")
        .testEval("sorted([True, False, True])", "[False, True, True]")
        .testEval("sorted(['a','x','b','z'])", "[\"a\", \"b\", \"x\", \"z\"]")
        .testEval("sorted({1: True, 5: True, 4: False})", "[1, 4, 5]")
        .testEval("sorted(depset([1, 5, 4]))", "[1, 4, 5]")
        .testIfExactError("Cannot compare function with function", "sorted([sorted, sorted])");
  }

  @Test
  public void testDictionaryCopy() throws Exception {
    new BothModesTest()
        .setUp("x = {1 : 2}", "y = dict(x)")
        .testEval("x[1] == 2 and y[1] == 2", "True");
  }

  @Test
  public void testDictionaryCopyKeyCollision() throws Exception {
    new BothModesTest()
        .setUp("x = {'test' : 2}", "y = dict(x, test = 3)")
        .testEval("y['test']", "3");
  }

  @Test
  public void testDictionaryKeyNotFound() throws Exception {
    new BothModesTest()
        .testIfErrorContains("key \"0\" not found in dictionary", "{}['0']")
        .testIfErrorContains("key 0 not found in dictionary", "{'0': 1, 2: 3, 4: 5}[0]");
  }

  @Test
  public void testListAccessBadIndex() throws Exception {
    new BothModesTest()
        .testIfErrorContains("indices must be integers, not string", "[[1], [2]]['a']");
  }

  @Test
  public void testDictionaryAccess() throws Exception {
    new BothModesTest()
        .testEval("{1: ['foo']}[1]", "['foo']")
        .testStatement("{'4': 8}['4']", 8)
        .testStatement("{'a': 'aa', 'b': 'bb', 'c': 'cc'}['b']", "bb");
  }

  @Test
  public void testDictionaryVariableAccess() throws Exception {
    new BothModesTest().setUp("d = {'a' : 1}", "a = d['a']\n").testLookup("a", 1);
  }

  @Test
  public void testStringIndexing() throws Exception {
    new BothModesTest()
        .testStatement("'somestring'[0]", "s")
        .testStatement("'somestring'[1]", "o")
        .testStatement("'somestring'[4]", "s")
        .testStatement("'somestring'[9]", "g")
        .testStatement("'somestring'[-1]", "g")
        .testStatement("'somestring'[-2]", "n")
        .testStatement("'somestring'[-10]", "s");
  }

  @Test
  public void testStringIndexingOutOfRange() throws Exception {
    new BothModesTest()
        .testIfErrorContains("index out of range", "'abcdef'[10]")
        .testIfErrorContains("index out of range", "'abcdef'[-11]")
        .testIfErrorContains("index out of range", "'abcdef'[42]");
  }

  @Test
  public void testStringSlice() throws Exception {
    new BothModesTest()
        .testStatement("'0123'[0:-1]", "012")
        .testStatement("'012345'[2:4]", "23")
        .testStatement("'012345'[-2:-1]", "4")
        .testStatement("''[1:2]", "")
        .testStatement("'012'[1:0]", "")
        .testStatement("'0123'[-10:10]", "0123");
  }

  @Test
  public void testStringSlice_WrongType() throws Exception {
    new BothModesTest()
        .testIfExactError("slice start must be an integer, not 'a'", "'123'['a'::]")
        .testIfExactError("slice end must be an integer, not 'b'", "'123'[:'b':]");
  }

  @Test
  public void testStringSliceStep() throws Exception {
    new BothModesTest()
        .testStatement("'01234'[::1]", "01234")
        .testStatement("'01234'[1::1]", "1234")
        .testStatement("'01234'[:2:1]", "01")
        .testStatement("'01234'[1:3:1]", "12")
        .testStatement("'01234'[-4:-2:1]", "12")
        .testStatement("'01234'[-10:10:1]", "01234")
        .testStatement("'01234'[::42]", "0");
  }

  @Test
  public void testStringSliceStep_EmptyString() throws Exception {
    new BothModesTest().testStatement("''[::1]", "").testStatement("''[::-1]", "");
  }

  @Test
  public void testStringSliceStep_SkipValues() throws Exception {
    new BothModesTest()
        .testStatement("'0123456'[::3]", "036")
        .testStatement("'01234567'[1:7:3]", "14");
  }

  @Test
  public void testStringSliceStep_Negative() throws Exception {
    new BothModesTest()
        .testStatement("'01234'[::-1]", "43210")
        .testStatement("'01234'[4::-1]", "43210")
        .testStatement("'01234'[:0:-1]", "4321")
        .testStatement("'01234'[3:1:-1]", "32")
        .testStatement("'01234'[::-2]", "420")
        .testStatement("'01234'[::-10]", "4");
  }

  @Test
  public void testStringSliceStep_WrongOrder() throws Exception {
    new BothModesTest().testStatement("'123'[3:1:1]", "").testStatement("'123'[1:3:-1]", "");
  }

  @Test
  public void testStringSliceStep_InvalidStep() throws Exception {
    String msg = "slice step cannot be zero";
    new BothModesTest()
        .testIfExactError(msg, "'123'[::0]")
        .testIfExactError(msg, "'123'[1::0]")
        .testIfExactError(msg, "'123'[:3:0]")
        .testIfExactError(msg, "'123'[1:3:0]");
  }

  @Test
  public void testDictionaryCreation() throws Exception {
    String expected = "{'a': 1, 'b': 2, 'c': 3}";

    new BothModesTest()
        .testEval("dict([('a', 1), ('b', 2), ('c', 3)])", expected)
        .testEval("dict(a = 1, b = 2, c = 3)", expected)
        .testEval("dict([('a', 1)], b = 2, c = 3)", expected);
  }

  @Test
  public void testDictionaryCreationInnerLists() throws Exception {
    new BothModesTest().testEval("dict([[1, 2], [3, 4]], a = 5)", "{1: 2, 3: 4, 'a': 5}");
  }

  @Test
  public void testDictionaryCreationEmpty() throws Exception {
    new BothModesTest().testEval("dict()", "{}").testEval("dict([])", "{}");
  }

  @Test
  public void testDictionaryCreationDifferentKeyTypes() throws Exception {
    String expected = "{'a': 1, 2: 3}";

    new BothModesTest()
        .testEval("dict([('a', 1), (2, 3)])", expected)
        .testEval("dict([(2, 3)], a = 1)", expected);
  }

  @Test
  public void testDictionaryCreationKeyCollision() throws Exception {
    String expected = "{'a': 1, 'b': 2, 'c': 3}";

    new BothModesTest()
        .testEval("dict([('a', 42), ('b', 2), ('a', 1), ('c', 3)])", expected)
        .testEval("dict([('a', 42)], a = 1, b = 2, c = 3)", expected);
    new SkylarkTest().testEval("dict([('a', 42)], **{'a': 1, 'b': 2, 'c': 3})", expected);
  }

  @Test
  public void testDictionaryCreationInvalidPositional() throws Exception {
    new BothModesTest()
        .testIfErrorContains(
            "expected value of type 'list(object)' for parameter args in dict(), "
                + "but got \"a\" (string)",
            "dict('a')")
        .testIfErrorContains("cannot convert item #0 to a sequence", "dict(['a'])")
        .testIfErrorContains("cannot convert item #0 to a sequence", "dict([('a')])")
        .testIfErrorContains("too many (3) positional arguments", "dict((3,4), (3,2), (1,2))")
        .testIfErrorContains(
            "item #0 has length 3, but exactly two elements are required",
            "dict([('a', 'b', 'c')])");
  }

  @Test
  public void testDictionaryValues() throws Exception {
    new BothModesTest()
        .testEval("{1: 'foo'}.values()", "['foo']")
        .testEval("{}.values()", "[]")
        .testEval("{True: 3, False: 5}.values()", "[3, 5]")
        .testEval("{'a': 5, 'c': 2, 'b': 4, 'd': 3}.values()", "[5, 2, 4, 3]");
    // sorted by keys
  }

  @Test
  public void testDictionaryKeys() throws Exception {
    new BothModesTest()
        .testEval("{1: 'foo'}.keys()", "[1]")
        .testEval("{}.keys()", "[]")
        .testEval("{True: 3, False: 5}.keys()", "[True, False]")
        .testEval(
            "{1:'a', 2:'b', 6:'c', 0:'d', 5:'e', 4:'f', 3:'g'}.keys()", "[1, 2, 6, 0, 5, 4, 3]");
  }

  @Test
  public void testDictionaryGet() throws Exception {
    new BuildTest()
        .testStatement("{1: 'foo'}.get(1)", "foo")
        .testStatement("{1: 'foo'}.get(2)", Runtime.NONE)
        .testStatement("{1: 'foo'}.get(2, 'a')", "a")
        .testStatement("{1: 'foo'}.get(2, default='a')", "a")
        .testStatement("{1: 'foo'}.get(2, default=None)", Runtime.NONE);
  }

  @Test
  public void testDictionaryItems() throws Exception {
    new BothModesTest()
        .testEval("{'a': 'foo'}.items()", "[('a', 'foo')]")
        .testEval("{}.items()", "[]")
        .testEval("{1: 3, 2: 5}.items()", "[(1, 3), (2, 5)]")
        .testEval("{'a': 5, 'c': 2, 'b': 4}.items()", "[('a', 5), ('c', 2), ('b', 4)]");
  }

  @Test
  public void testDictionaryClear() throws Exception {
    new SkylarkTest()
        .testEval(
            "d = {1: 'foo', 2: 'bar', 3: 'baz'}\n"
                + "len(d) == 3 or fail('clear 1')\n"
                + "d.clear() == None or fail('clear 2')\n"
                + "d",
            "{}");
  }

  @Test
  public void testDictionaryPop() throws Exception {
    new SkylarkTest()
        .testIfErrorContains(
            "KeyError: 1",
            "d = {1: 'foo', 2: 'bar', 3: 'baz'}\n"
                + "len(d) == 3 or fail('pop 1')\n"
                + "d.pop(2) == 'bar' or fail('pop 2')\n"
                + "d.pop(3, 'quux') == 'baz' or fail('pop 3a')\n"
                + "d.pop(3, 'quux') == 'quux' or fail('pop 3b')\n"
                + "d.pop(1) == 'foo' or fail('pop 1')\n"
                + "d == {} or fail('pop 0')\n"
                + "d.pop(1)");
  }

  @Test
  public void testDictionaryPopItem() throws Exception {
    new SkylarkTest()
        .testIfErrorContains(
            "popitem(): dictionary is empty",
            "d = {2: 'bar', 3: 'baz', 1: 'foo'}\n"
                + "len(d) == 3 or fail('popitem 0')\n"
                + "d.popitem() == (2, 'bar') or fail('popitem 2')\n"
                + "d.popitem() == (3, 'baz') or fail('popitem 3')\n"
                + "d.popitem() == (1, 'foo') or fail('popitem 1')\n"
                + "d == {} or fail('popitem 4')\n"
                + "d.popitem()");
  }

  @Test
  public void testDictionaryUpdate() throws Exception {
    new BothModesTest()
        .setUp("foo = {'a': 2}")
        .testEval("foo.update({'b': 4}); foo", "{'a': 2, 'b': 4}");
    new BothModesTest()
        .setUp("foo = {'a': 2}")
        .testEval("foo.update({'a': 3, 'b': 4}); foo", "{'a': 3, 'b': 4}");
  }

  @Test
  public void testDictionarySetDefault() throws Exception {
    new SkylarkTest()
        .testEval(
            "d = {2: 'bar', 1: 'foo'}\n"
                + "len(d) == 2 or fail('setdefault 0')\n"
                + "d.setdefault(1, 'a') == 'foo' or fail('setdefault 1')\n"
                + "d.setdefault(2) == 'bar' or fail('setdefault 2')\n"
                + "d.setdefault(3) == None or fail('setdefault 3')\n"
                + "d.setdefault(4, 'b') == 'b' or fail('setdefault 4')\n"
                + "d",
            "{1: 'foo', 2: 'bar', 3: None, 4: 'b'}");
  }

  @Test
  public void testListIndexMethod() throws Exception {
    new BothModesTest()
        .testStatement("['a', 'b', 'c'].index('a')", 0)
        .testStatement("['a', 'b', 'c'].index('b')", 1)
        .testStatement("['a', 'b', 'c'].index('c')", 2)
        .testStatement("[2, 4, 6].index(4)", 1)
        .testStatement("[2, 4, 6].index(4)", 1)
        .testStatement("[0, 1, [1]].index([1])", 2)
        .testIfErrorContains("item \"a\" not found in list", "[1, 2].index('a')")
        .testIfErrorContains("item 0 not found in list", "[].index(0)");
  }

  @Test
  public void testListIndex() throws Exception {
    new BothModesTest()
        .testStatement("['a', 'b', 'c', 'd'][0]", "a")
        .testStatement("['a', 'b', 'c', 'd'][1]", "b")
        .testStatement("['a', 'b', 'c', 'd'][-1]", "d")
        .testStatement("['a', 'b', 'c', 'd'][-2]", "c")
        .testStatement("[0, 1, 2][-3]", 0)
        .testStatement("[0, 1, 2][-2]", 1)
        .testStatement("[0, 1, 2][-1]", 2)
        .testStatement("[0, 1, 2][0]", 0);
  }

  @Test
  public void testListIndexOutOfRange() throws Exception {
    new BothModesTest()
        .testIfErrorContains(
            "index out of range (index is 3, but sequence has 3 elements)", "[0, 1, 2][3]")
        .testIfErrorContains(
            "index out of range (index is -4, but sequence has 3 elements)", "[0, 1, 2][-4]")
        .testIfErrorContains(
            "index out of range (index is -2, but sequence has 1 elements)", "[0][-2]")
        .testIfErrorContains(
            "index out of range (index is 1, but sequence has 1 elements)", "[0][1]")
        .testIfErrorContains(
            "index out of range (index is 1, but sequence has 0 elements)", "[][1]");
  }

  @Test
  public void testHash() throws Exception {
    // We specify the same string hashing algorithm as String.hashCode().
    new SkylarkTest()
        .testStatement("hash('skylark')", "skylark".hashCode())
        .testStatement("hash('google')", "google".hashCode())
        .testIfErrorContains(
            "argument 'value' has type 'NoneType', but should be 'string'\n"
                + "in call to builtin function hash(value)",
            "hash(None)");
  }

  @Test
  public void testRange() throws Exception {
    new BothModesTest()
        .testStatement("str(range(5))", "[0, 1, 2, 3, 4]")
        .testStatement("str(range(0))", "[]")
        .testStatement("str(range(1))", "[0]")
        .testStatement("str(range(-2))", "[]")
        .testStatement("str(range(-3, 2))", "[-3, -2, -1, 0, 1]")
        .testStatement("str(range(3, 2))", "[]")
        .testStatement("str(range(3, 3))", "[]")
        .testStatement("str(range(3, 4))", "[3]")
        .testStatement("str(range(3, 5))", "[3, 4]")
        .testStatement("str(range(-3, 5, 2))", "[-3, -1, 1, 3]")
        .testStatement("str(range(-3, 6, 2))", "[-3, -1, 1, 3, 5]")
        .testStatement("str(range(5, 0, -1))", "[5, 4, 3, 2, 1]")
        .testStatement("str(range(5, 0, -10))", "[5]")
        .testStatement("str(range(0, -3, -2))", "[0, -2]")
        .testIfErrorContains("step cannot be 0", "range(2, 3, 0)");
  }

  @Test
  public void testEnumerate() throws Exception {
    new BothModesTest()
        .testStatement("str(enumerate([]))", "[]")
        .testStatement("str(enumerate([5]))", "[(0, 5)]")
        .testStatement("str(enumerate([5, 3]))", "[(0, 5), (1, 3)]")
        .testStatement("str(enumerate(['a', 'b', 'c']))", "[(0, \"a\"), (1, \"b\"), (2, \"c\")]")
        .testStatement("str(enumerate(['a']) + [(1, 'b')])", "[(0, \"a\"), (1, \"b\")]");
  }

  @Test
  public void testEnumerateBadArg() throws Exception {
    new BothModesTest()
        .testIfErrorContains(
            "argument 'list' has type 'string', but should be 'sequence'\n"
                + "in call to builtin function enumerate(list)",
            "enumerate('a')");
  }

  @Test
  public void testPyListAppend() throws Exception {
    new BuildTest()
        .setUp("FOO = ['a', 'b']", "FOO.insert(0, 'c')")
        .testLookup("FOO", MutableList.of(env, "c", "a", "b"))
        .setUp("FOO.insert(1, 'd')")
        .testLookup("FOO", MutableList.of(env, "c", "d", "a", "b"))
        .setUp("FOO.insert(4, 'e')")
        .testLookup("FOO", MutableList.of(env, "c", "d", "a", "b", "e"))
        .setUp("FOO.insert(-10, 'f')")
        .testLookup("FOO", MutableList.of(env, "f", "c", "d", "a", "b", "e"))
        .setUp("FOO.insert(10, 'g')")
        .testLookup("FOO", MutableList.of(env, "f", "c", "d", "a", "b", "e", "g"))
        .testIfErrorContains("type 'tuple' has no method insert(int)", "(1, 2).insert(3)");
  }

  @Test
  public void testPyListInsert() throws Exception {
    new BuildTest()
        .setUp("FOO = ['a', 'b']", "FOO.append('c')")
        .testLookup("FOO", MutableList.of(env, "a", "b", "c"))
        .testIfErrorContains("type 'tuple' has no method append(int)", "(1, 2).append(3)");
  }

  @Test
  public void testPyListExtend() throws Exception {
    new BuildTest()
        .setUp("FOO = ['a', 'b']", "FOO.extend(['c', 'd'])", "FOO.extend(('e', 'f'))")
        .testLookup("FOO", MutableList.of(env, "a", "b", "c", "d", "e", "f"))
        .testIfErrorContains("type 'tuple' has no method extend(list)", "(1, 2).extend([3, 4])")
        .testIfErrorContains(
            "argument 'items' has type 'int', but should be 'sequence'\n"
                + "in call to builtin method list.extend(items)",
            "[1, 2].extend(3)");
  }

  @Test
  public void testListRemove() throws Exception {
    new BothModesTest()
        .setUp("foo = ['a', 'b', 'c', 'b']", "foo.remove('b')")
        .testLookup("foo", MutableList.of(env, "a", "c", "b"))
        .setUp("foo.remove('c')")
        .testLookup("foo", MutableList.of(env, "a", "b"))
        .setUp("foo.remove('a')")
        .testLookup("foo", MutableList.of(env, "b"))
        .setUp("foo.remove('b')")
        .testLookup("foo", MutableList.of(env))
        .testIfErrorContains("item 3 not found in list", "[1, 2].remove(3)");

    new BothModesTest()
        .testIfErrorContains("type 'tuple' has no method remove(int)", "(1, 2).remove(3)");
  }

  @Test
  public void testListPop() throws Exception {
    new BothModesTest()
        .setUp("li = [2, 3, 4]; ret = li.pop()")
        .testLookup("li", MutableList.of(env, 2, 3))
        .testLookup("ret", 4);
    new BothModesTest()
        .setUp("li = [2, 3, 4]; ret = li.pop(-2)")
        .testLookup("li", MutableList.of(env, 2, 4))
        .testLookup("ret", 3);
    new BothModesTest()
        .setUp("li = [2, 3, 4]; ret = li.pop(1)")
        .testLookup("li", MutableList.of(env, 2, 4))
        .testLookup("ret", 3);
    new BothModesTest()
        .testIfErrorContains(
            "index out of range (index is 3, but sequence has 2 elements)", "[1, 2].pop(3)");

    new BothModesTest().testIfErrorContains("type 'tuple' has no method pop()", "(1, 2).pop()");
  }

  @Test
  public void testReassignmentOfPrimitivesNotForbiddenByCoreLanguage() throws Exception {
    new BuildTest()
        .setUp("cc_binary = (['hello.cc'])")
        .testIfErrorContains(
            "'list' object is not callable",
            "cc_binary(name = 'hello', srcs=['hello.cc'], malloc = '//base:system_malloc')");
  }

  @Test
  public void testLenOnString() throws Exception {
    new BothModesTest().testStatement("len('abc')", 3);
  }

  @Test
  public void testLenOnList() throws Exception {
    new BothModesTest().testStatement("len([1,2,3])", 3);
  }

  @Test
  public void testLenOnDict() throws Exception {
    new BothModesTest().testStatement("len({'a' : 1, 'b' : 2})", 2);
  }

  @Test
  public void testLenOnBadType() throws Exception {
    new BothModesTest().testIfErrorContains("int is not iterable", "len(1)");
  }

  @Test
  public void testIndexOnFunction() throws Exception {
    new BothModesTest()
        .testIfErrorContains("type 'function' has no operator [](int)", "len[1]")
        .testIfErrorContains("type 'function' has no operator [:](int, int, int)", "len[1:4]");
  }

  @Test
  public void testBool() throws Exception {
    new BothModesTest()
        .testStatement("bool(1)", Boolean.TRUE)
        .testStatement("bool(0)", Boolean.FALSE)
        .testStatement("bool([1, 2])", Boolean.TRUE)
        .testStatement("bool([])", Boolean.FALSE)
        .testStatement("bool(None)", Boolean.FALSE);
  }

  @Test
  public void testStr() throws Exception {
    new BothModesTest()
        .testStatement("str(1)", "1")
        .testStatement("str(-2)", "-2")
        .testStatement("str([1, 2])", "[1, 2]")
        .testStatement("str(True)", "True")
        .testStatement("str(False)", "False")
        .testStatement("str(None)", "None")
        .testStatement("str(str)", "<built-in function str>");
  }

  @Test
  public void testInt() throws Exception {
    new BothModesTest()
        .testStatement("int('1')", 1)
        .testStatement("int('-1234')", -1234)
        .testIfErrorContains("invalid literal for int() with base 10: \"1.5\"", "int('1.5')")
        .testIfErrorContains("invalid literal for int() with base 10: \"ab\"", "int('ab')")
        .testStatement("int(42)", 42)
        .testStatement("int(-1)", -1)
        .testStatement("int(True)", 1)
        .testStatement("int(False)", 0)
        .testIfErrorContains("None is not of type string or int or bool", "int(None)");
  }

  @Test
  public void testIntWithBase() throws Exception {
    new BothModesTest()
        .testStatement("int('11', 2)", 3)
        .testStatement("int('11', 9)", 10)
        .testStatement("int('AF', 16)", 175)
        .testStatement("int('11', 36)", 37)
        .testStatement("int('az', 36)", 395)
        .testStatement("int('11', 10)", 11)
        .testStatement("int('11', 0)", 11);
  }

  @Test
  public void testIntWithBase_InvalidBase() throws Exception {
    new BothModesTest()
        .testIfExactError("invalid literal for int() with base 3: \"123\"", "int('123', 3)")
        .testIfExactError("invalid literal for int() with base 15: \"FF\"", "int('FF', 15)")
        .testIfExactError("int() base must be >= 2 and <= 36", "int('123', -1)")
        .testIfExactError("int() base must be >= 2 and <= 36", "int('123', 1)")
        .testIfExactError("int() base must be >= 2 and <= 36", "int('123', 37)");
  }

  @Test
  public void testIntWithBase_Prefix() throws Exception {
    new BothModesTest()
        .testStatement("int('0b11', 0)", 3)
        .testStatement("int('0B11', 2)", 3)
        .testStatement("int('0o11', 0)", 9)
        .testStatement("int('0O11', 8)", 9)
        .testStatement("int('0XFF', 0)", 255)
        .testStatement("int('0xFF', 16)", 255)
        .testIfExactError("invalid literal for int() with base 8: \"0xFF\"", "int('0xFF', 8)");
  }

  @Test
  public void testIntWithBase_NoString() throws Exception {
    new BothModesTest()
        .testIfExactError("int() can't convert non-string with explicit base", "int(True, 2)")
        .testIfExactError("int() can't convert non-string with explicit base", "int(1, 2)")
        .testIfExactError("int() can't convert non-string with explicit base", "int(True, 10)")
    ;
  }

  @Test
  public void testStrFunction() throws Exception {
    new SkylarkTest().testStatement("def foo(x): return x\nstr(foo)", "<function foo>");
  }

  @Test
  public void testType() throws Exception {
    new SkylarkTest()
        .testStatement("type(1)", "int")
        .testStatement("type('a')", "string")
        .testStatement("type([1, 2])", "list")
        .testStatement("type((1, 2))", "tuple")
        .testStatement("type(True)", "bool")
        .testStatement("type(None)", "NoneType")
        .testStatement("type(str)", "function");
  }

  // TODO(bazel-team): Move this into a new BazelLibraryTest.java file, or at least out of
  // MethodLibraryTest.java.
  @Test
  public void testSelectFunction() throws Exception {
    enableSkylarkMode();
    eval("a = select({'a': 1})");
    SelectorList result = (SelectorList) lookup("a");
    assertThat(((SelectorValue) Iterables.getOnlyElement(result.getElements())).getDictionary())
        .containsExactly("a", 1);
  }

  @Test
  public void testCountFunction() throws Exception {
    new BothModesTest()
        .testStatement("'abc'.count('')", 4)
        .testStatement("'abc'.count('a')", 1)
        .testStatement("'abc'.count('b')", 1)
        .testStatement("'abc'.count('c')", 1)
        .testStatement("'abbc'.count('b')", 2)
        .testStatement("'aba'.count('a')", 2)
        .testStatement("'aaa'.count('aa')", 1)
        .testStatement("'aaaa'.count('aa')", 2)
        .testStatement("'abc'.count('a', 0)", 1)
        .testStatement("'abc'.count('a', 1)", 0)
        .testStatement("'abc'.count('c', 0, 3)", 1)
        .testStatement("'abc'.count('c', 0, 2)", 0)
        .testStatement("'abc'.count('a', -1)", 0)
        .testStatement("'abc'.count('c', -1)", 1)
        .testStatement("'abc'.count('c', 0, 5)", 1)
        .testStatement("'abc'.count('c', 0, -1)", 0)
        .testStatement("'abc'.count('a', 0, -1)", 1);
  }

  @Test
  public void testZipFunction() throws Exception {
    new BothModesTest()
        .testStatement("str(zip())", "[]")
        .testStatement("str(zip([1, 2]))", "[(1,), (2,)]")
        .testStatement("str(zip([1, 2], ['a', 'b']))", "[(1, \"a\"), (2, \"b\")]")
        .testStatement("str(zip([1, 2, 3], ['a', 'b']))", "[(1, \"a\"), (2, \"b\")]")
        .testStatement("str(zip([1], [2], [3]))", "[(1, 2, 3)]")
        .testStatement("str(zip([1], {2: 'a'}))", "[(1, 2)]")
        .testStatement("str(zip([1], []))", "[]")
        .testIfErrorContains("type 'int' is not iterable", "zip(123)")
        .testIfErrorContains("type 'int' is not iterable", "zip([1], 1)")
        .testStatement("str(zip([1], depset([2])))", "[(1, 2)]");
  }

  @Test
  public void testIsAlphaFunction() throws Exception {
    new BothModesTest()
        .testStatement("''.isalpha()", false)
        .testStatement("'abz'.isalpha()", true)
        .testStatement("'a1'.isalpha()", false)
        .testStatement("'a '.isalpha()", false)
        .testStatement("'A'.isalpha()", true)
        .testStatement("'AbZ'.isalpha()", true);
  }

  /**
   * Assert that lstrip(), rstrip(), and strip() produce the expected result for a given input
   * string and chars argument. If chars is null no argument is passed.
   */
  private void checkStrip(
      String input, Object chars,
      String expLeft, String expRight, String expBoth) throws Exception {
    if (chars == null) {
      new BothModesTest()
          .update("s", input)
          .testStatement("s.lstrip()", expLeft)
          .testStatement("s.rstrip()", expRight)
          .testStatement("s.strip()", expBoth);
    } else {
      new BothModesTest()
          .update("s", input)
          .update("chars", chars)
          .testStatement("s.lstrip(chars)", expLeft)
          .testStatement("s.rstrip(chars)", expRight)
          .testStatement("s.strip(chars)", expBoth);
    }
  }

  @Test
  public void testStrip() throws Exception {
    // Strip nothing.
    checkStrip("a b c", "", "a b c", "a b c", "a b c");
    checkStrip(" a b c ", "", " a b c ", " a b c ", " a b c ");
    // Normal case, found and not found.
    checkStrip("abcba", "ba", "cba", "abc", "c");
    checkStrip("abc", "xyz", "abc", "abc", "abc");
    // Default whitespace.
    checkStrip(" a b c ", null, "a b c ", " a b c", "a b c");
    checkStrip(" a b c ", Runtime.NONE, "a b c ", " a b c", "a b c");
    // Default whitespace with full range of Latin-1 whitespace chars.
    String whitespace = "\u0009\n\u000B\u000C\r\u001C\u001D\u001E\u001F\u0020\u0085\u00A0";
    checkStrip(
        whitespace + "a" + whitespace, null,
        "a" + whitespace, whitespace + "a", "a");
    checkStrip(
        whitespace + "a" + whitespace, Runtime.NONE,
        "a" + whitespace, whitespace + "a", "a");
    // Empty cases.
    checkStrip("", "", "", "", "");
    checkStrip("abc", "abc", "", "", "");
    checkStrip("", "xyz", "", "", "");
    checkStrip("", null, "", "", "");
  }

  @Test
  public void testFail() throws Exception {
    new SkylarkTest()
        .testIfErrorContains("abc", "fail('abc')")
        .testIfErrorContains("18", "fail(18)");
  }

  @Test
  public void testTupleCoercion() throws Exception {
    new BothModesTest()
        .testStatement("tuple([1, 2]) == (1, 2)", true)
        .testStatement("tuple(depset([1, 2])) == (1, 2)", true)
        // Depends on current implementation of dict
        .testStatement("tuple({1: 'foo', 2: 'bar'}) == (1, 2)", true);
  }
}
