/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.JarDirectoryStep;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;

import java.nio.file.Path;

public class AndroidAar extends AbstractBuildRule implements HasClasspathEntries {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);

  private final Path pathToOutputFile;
  private final Path temp;
  private final AndroidManifest manifest;
  private final AndroidResource androidResource;
  private final AssembleDirectories assembleResourceDirectories;
  private final AssembleDirectories assembleAssetsDirectories;
  private final ImmutableSet<Path> nativeLibAssetsDirectories;
  private final ImmutableSet<Path> nativeLibsDirectories;

  public AndroidAar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AndroidManifest manifest,
      AndroidResource androidResource,
      AssembleDirectories assembleResourceDirectories,
      AssembleDirectories assembleAssetsDirectories,
      ImmutableSet<Path> nativeLibAssetsDirectories,
      ImmutableSet<Path> nativeLibsDirectories) {
    super(params, resolver);
    BuildTarget buildTarget = params.getBuildTarget();
    this.pathToOutputFile = BuildTargets.getGenPath(buildTarget, "%s.aar");
    this.temp = BuildTargets.getScratchPath(buildTarget, "__temp__%s");
    this.manifest = manifest;
    this.androidResource = androidResource;
    this.assembleAssetsDirectories = assembleAssetsDirectories;
    this.assembleResourceDirectories = assembleResourceDirectories;
    this.nativeLibAssetsDirectories = nativeLibAssetsDirectories;
    this.nativeLibsDirectories = nativeLibsDirectories;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Create temp folder to store the files going to be zipped
    commands.add(new MakeCleanDirectoryStep(temp));

    // Remove the output .aar file
    commands.add(new RmStep(pathToOutputFile, /* shouldForceDeletion */ true));

    // put manifest into tmp folder
    commands.add(
        CopyStep.forFile(
            manifest.getPathToOutputFile(),
            temp.resolve("AndroidManifest.xml")));

    // put R.txt into tmp folder
    commands.add(CopyStep.forFile(androidResource.getPathToOutputFile(), temp.resolve("R.txt")));

    // put res/ and assets/ into tmp folder
    commands.add(CopyStep.forDirectory(
            assembleResourceDirectories.getPathToOutputFile(),
            temp.resolve("res"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));
    commands.add(CopyStep.forDirectory(
            assembleAssetsDirectories.getPathToOutputFile(),
            temp.resolve("assets"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    // Create our Uber-jar, and place it in the tmp folder.
    commands.add(new JarDirectoryStep(
            temp.resolve("classes.jar"),
            ImmutableSet.copyOf(getTransitiveClasspathEntries().values()),
            null,
            null));

    // move native libs into tmp folder under jni/
    for (Path dir : nativeLibsDirectories) {
      commands.add(CopyStep.forDirectory(dir, temp.resolve("jni"),
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    // move native assets into tmp folder under assets/lib/
    for (Path dir : nativeLibAssetsDirectories) {
      CopyNativeLibraries.copyNativeLibrary(
          dir, temp.resolve("assets").resolve("lib"), ImmutableSet.<TargetCpuType>of(), commands);
    }

    // do the zipping
    commands.add(
        new ZipStep(
            pathToOutputFile,
            ImmutableSet.<Path>of(),
            false,
            ZipStep.DEFAULT_COMPRESSION_LEVEL,
            temp));

    return commands.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return Classpaths.getClasspathEntries(getDeps());
  }
}
