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
package com.google.devtools.build.android;

import com.android.SdkConstants;
import com.android.aapt.Resources;
import com.android.aapt.Resources.ConfigValue;
import com.android.aapt.Resources.Package;
import com.android.aapt.Resources.ResourceTable;
import com.android.aapt.Resources.Type;
import com.android.aapt.Resources.Value;
import com.android.resources.ResourceType;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.devtools.build.android.FullyQualifiedName.Factory;
import com.google.devtools.build.android.proto.Format.CompiledFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Deserializes {@link DataKey}, {@link DataValue} entries from compiled resource files. */
public class AndroidCompiledDataDeserializer implements AndroidDataDeserializer{
  private static final Logger logger =
      Logger.getLogger(AndroidCompiledDataDeserializer.class.getName());

  private final ImmutableSet<String> filteredResources;

  /**
   * @param filteredResources resources that were filtered out of this target and should be ignored
   *     if they are referenced in symbols files.
   */
  public static AndroidCompiledDataDeserializer withFilteredResources(
      Collection<String> filteredResources) {
    return new AndroidCompiledDataDeserializer(ImmutableSet.copyOf(filteredResources));
  }

  public static AndroidCompiledDataDeserializer create() {
    return new AndroidCompiledDataDeserializer(ImmutableSet.of());
  }

  private AndroidCompiledDataDeserializer(ImmutableSet<String> filteredResources) {
    this.filteredResources = filteredResources;
  }

  private void readResourceTable(
      InputStream resourceTableStream,
      KeyValueConsumers consumers,
      Factory fqnFactory) throws IOException {
    ResourceTable resourceTable = ResourceTable.parseFrom(resourceTableStream);

    List<String> sourcePool =
        decodeSourcePool(resourceTable.getSourcePool().getData().toByteArray());

    Map<String, Entry<FullyQualifiedName, Boolean>> fullyQualifiedNames = new HashMap<>();

    for (int i = resourceTable.getPackageCount() - 1; i >= 0; i--) {
      Package resourceTablePackage = resourceTable.getPackage(i);

      String packageName = "";
      if (!resourceTablePackage.getPackageName().isEmpty()) {
        packageName = resourceTablePackage.getPackageName() + ":";
      }

      for (Type resourceFormatType : resourceTablePackage.getTypeList()) {
        ResourceType resourceType = ResourceType.getEnum(resourceFormatType.getName());

        for (Resources.Entry resource : resourceFormatType.getEntryList()) {
          Value resourceValue = resource.getConfigValue(0).getValue();
          String resourceName = packageName + resource.getName();
          List<ConfigValue> configValues = resource.getConfigValueList();

          Preconditions.checkArgument(configValues.size() == 1);
          int sourceIndex =
              configValues.get(0)
                  .getValue()
                  .getSource()
                  .getPathIdx();

          String source = sourcePool.get(sourceIndex);

          DataSource dataSource = DataSource.of(Paths.get(source));
          FullyQualifiedName fqn = fqnFactory.create(resourceType, resourceName);
          fullyQualifiedNames.put(
              packageName + resourceType + "/" + resource.getName(),
              new SimpleEntry<FullyQualifiedName, Boolean>(fqn, packageName.isEmpty()));

          if (packageName.isEmpty()) {
            DataResourceXml dataResourceXml = DataResourceXml
                .from(resourceValue, dataSource, resourceType, fullyQualifiedNames);
            if (resourceType == ResourceType.ID
                || resourceType == ResourceType.PUBLIC
                || resourceType == ResourceType.STYLEABLE) {
              consumers.combiningConsumer.accept(fqn, dataResourceXml);
            } else {
              consumers.overwritingConsumer.accept(fqn, dataResourceXml);
            }
          }
        }
      }
    }
  }

  /**
   * Reads compiled resource data files and adds them to consumers
   * @param compiledFileStream First byte is number of compiled files represented in this file.
   *    Next 8 bytes is a long indicating the length of the metadata describing the compiled file.
   *    Next N bytes is the metadata describing the compiled file.
   *    The remaining bytes are the actual original file.
   * @param consumers
   * @param fqnFactory
   * @throws IOException
   */
  private void readCompiledFile(
      InputStream compiledFileStream,
      KeyValueConsumers consumers,
      Factory fqnFactory) throws IOException {
    LittleEndianDataInputStream dataInputStream =
        new LittleEndianDataInputStream(compiledFileStream);

    int numberOfCompiledFiles = dataInputStream.readInt();
    if (numberOfCompiledFiles != 1) {
      logger.warning("Compiled resource file has "
          + numberOfCompiledFiles + " files. Expected 1 compiled file.");
    }

    long length = dataInputStream.readLong();
    byte[] file = new byte[(int) length];
    dataInputStream.read(file, 0, (int) length);
    CompiledFile compiledFile = CompiledFile.parseFrom(file);

    Path sourcePath = Paths.get(compiledFile.getSourcePath());
    FullyQualifiedName fqn = fqnFactory.parse(sourcePath);
    DataSource dataSource = DataSource.of(sourcePath);

    if (consumers != null) {
      consumers.overwritingConsumer.accept(fqn, DataValueFile.of(dataSource));
    }

    for (CompiledFile.Symbol exportedSymbol : compiledFile.getExportedSymbolsList()) {
      FullyQualifiedName symbolFqn =
          fqnFactory.create(
              ResourceType.ID, exportedSymbol.getResourceName().replaceFirst("id/", ""));

      DataResourceXml dataResourceXml =
          DataResourceXml.from(null, dataSource, ResourceType.ID, null);
      consumers.combiningConsumer.accept(symbolFqn, dataResourceXml);
    }
  }

  @Override
  public void read(Path inPath, KeyValueConsumers consumers){
    Stopwatch timer = Stopwatch.createStarted();
    try (ZipFile zipFile = new ZipFile(inPath.toFile())) {
      Enumeration<? extends ZipEntry> resourceFiles = zipFile.entries();

      while (resourceFiles.hasMoreElements()) {
        ZipEntry resourceFile = resourceFiles.nextElement();
        InputStream resourceFileStream = zipFile.getInputStream(resourceFile);

        String fileZipPath = resourceFile.getName();
        int resourceSubdirectoryIndex = fileZipPath.indexOf('_', fileZipPath.lastIndexOf('/'));
        Path filePath = Paths.get(String.format("%s%c%s",
            fileZipPath.substring(0, resourceSubdirectoryIndex),
            '/',
            fileZipPath.substring(resourceSubdirectoryIndex + 1)));

        String shortPath = filePath.getParent().getFileName() + "/" + filePath.getFileName();

        if (filteredResources.contains(shortPath) && !Files.exists(filePath)) {
          // Skip files that were filtered out during analysis.
          // TODO(asteinb): Properly filter out these files from android_library symbol files during
          // analysis instead, and remove this list.
          continue;
        }

        final String[] dirNameAndQualifiers = filePath.getParent().getFileName().toString()
            .split(SdkConstants.RES_QUALIFIER_SEP);
        Factory fqnFactory = Factory.fromDirectoryName(dirNameAndQualifiers);

        if (fileZipPath.endsWith(".arsc.flat")) {
          readResourceTable(resourceFileStream, consumers, fqnFactory);
        } else {
          readCompiledFile(resourceFileStream, consumers, fqnFactory);
        }
      }
    } catch (IOException e) {
      throw new DeserializationException("Error deserializing " + inPath, e);
    } finally {
      logger.fine(
          String.format(
              "Deserialized in compiled merged in %sms", timer.elapsed(TimeUnit.MILLISECONDS)));
    }
  }

  private static List<String> decodeSourcePool(byte[] bytes) throws UnsupportedEncodingException {
    ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

    int stringCount = byteBuffer.getInt(8);
    boolean isUtf8 = (byteBuffer.getInt(16) & (1 << 8)) != 0;
    int stringsStart = byteBuffer.getInt(20);
    //Position the ByteBuffer after the metadata
    byteBuffer.position(28);

    List<String> strings = new ArrayList<>();

    for (int i = 0; i < stringCount; i++) {
      int stringOffset = stringsStart + byteBuffer.getInt();

      if (isUtf8) {
        int characterCount = byteBuffer.get(stringOffset) & 0xFF;
        if ((characterCount & 0x80) != 0) {
          characterCount =
              ((characterCount & 0x7F) << 8) | (byteBuffer.get(stringOffset + 1) & 0xFF);
        }

        stringOffset += (characterCount >= (0x80) ? 2 : 1);

        int length = byteBuffer.get(stringOffset) & 0xFF;
        if ((length & 0x80) != 0) {
          length = ((length & 0x7F) << 8) | (byteBuffer.get(stringOffset + 1) & 0xFF);
        }

        stringOffset += (length >= (0x80) ? 2 : 1);

        strings.add(new String(bytes, stringOffset, length, "UTF8"));
      } else {
        int characterCount = byteBuffer.get(stringOffset) & 0xFFFF;
        if ((characterCount & 0x8000) != 0) {
          characterCount =
              ((characterCount & 0x7FFF) << 16) | (byteBuffer.get(stringOffset + 2) & 0xFFFF);
        }

        stringOffset += 2 * (characterCount >= (0x8000) ? 2 : 1);

        int length = byteBuffer.get(stringOffset) & 0xFFFF;
        if ((length & 0x8000) != 0) {
          length = ((length & 0x7FFF) << 16) | (byteBuffer.get(stringOffset + 2) & 0xFFFF);
        }

        stringOffset += 2 * (length >= (0x8000) ? 2 : 1);

        strings.add(new String(bytes, stringOffset, length, "UTF16"));
      }
    }

    return strings;
  }

}
