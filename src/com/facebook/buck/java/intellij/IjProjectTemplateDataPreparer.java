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

package com.facebook.buck.java.intellij;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Does the converting of abstract data structures to a format immediately consumable by the
 * StringTemplate-based templates employed by {@link IjProjectWriter}. This is a separate class
 * mainly for testing convenience.
 */
@VisibleForTesting
public class IjProjectTemplateDataPreparer {

  private JavaPackageFinder javaPackageFinder;
  private IjModuleGraph moduleGraph;
  private ProjectFilesystem projectFilesystem;
  private ImmutableSet<Path> referencedFolderPaths;
  private ImmutableSet<Path> filesystemTraversalBoundaryPaths;

  public IjProjectTemplateDataPreparer(
      JavaPackageFinder javaPackageFinder,
      IjModuleGraph moduleGraph,
      ProjectFilesystem projectFilesystem) {
    this.javaPackageFinder = javaPackageFinder;
    this.moduleGraph = moduleGraph;
    this.projectFilesystem = projectFilesystem;
    this.referencedFolderPaths = createReferencedFolderPathsSet(moduleGraph);
    this.filesystemTraversalBoundaryPaths = createfilesystemTraversalBoundaryPathSet(moduleGraph);
  }

  private static void addPathAndParents(Set<Path> pathSet, Path path) {
    do {
      pathSet.add(path);
      path = path.getParent();
    } while(path != null && !pathSet.contains(path));
  }

  public static ImmutableSet<Path> createReferencedFolderPathsSet(IjModuleGraph moduleGraph) {
    Set<Path> pathSet = new HashSet<>();
    for (IjModule module : moduleGraph.getModuleNodes()) {
      addPathAndParents(pathSet, module.getModuleBasePath());
      for (IjFolder folder : module.getFolders()) {
        addPathAndParents(pathSet, folder.getPath());
      }
    }
    return ImmutableSet.copyOf(pathSet);
  }

  public static ImmutableSet<Path> createfilesystemTraversalBoundaryPathSet(
      IjModuleGraph moduleGraph) {
    ImmutableSet.Builder<Path> pathSetBuilder = ImmutableSet.builder();
    for (IjModule module : moduleGraph.getModuleNodes()) {
      pathSetBuilder.add(module.getModuleBasePath());
    }
    pathSetBuilder.add(IjProjectWriter.IDEA_CONFIG_DIR_PREFIX);
    return pathSetBuilder.build();
  }

  /**
   * @param path path to folder.
   * @param moduleLocationBasePath path to the location of the .iml file.
   * @return a path, relative to the module .iml file location describing a folder
   * in IntelliJ format.
   */
  private static String toModuleDirRelativeString(Path path, Path moduleLocationBasePath) {
    String moduleRelativePath = moduleLocationBasePath.relativize(path).toString();
    if (moduleRelativePath.isEmpty()) {
      return "file://$MODULE_DIR$";
    } else {
      return "file://$MODULE_DIR$/" + moduleRelativePath;
    }
  }

  private static String toProjectDirRelativeString(Path projectRelativePath) {
    String path = projectRelativePath.toString();
    if (path.isEmpty()) {
      return "file://$PROJECT_DIR$";
    } else {
      return "file://$PROJECT_DIR$/" + path;
    }
  }

  public static Path getModuleOutputFilePath(String name) {
    return IjProjectWriter.MODULES_PREFIX.resolve(name + ".iml");
  }

  @Value.Immutable
  @BuckStyleImmutable
  public abstract static class AbstractIjSourceFolder implements Comparable<IjSourceFolder> {
    public abstract String getType();
    public abstract String getUrl();
    public abstract boolean getIsTestSource();
    @Nullable public abstract String getPackagePrefix();

    @Override
    public int compareTo(IjSourceFolder o) {
      return getUrl().compareTo(o.getUrl());
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  public abstract static class AbstractContentRoot implements Comparable<ContentRoot> {
    public abstract String getUrl();
    public abstract ImmutableSortedSet<IjSourceFolder> getFolders();

    @Override
    public int compareTo(ContentRoot o) {
      return getUrl().compareTo(o.getUrl());
    }
  }

  private IjSourceFolder createSourceFolder(IjFolder folder, Path moduleLocationBasePath) {
    String packagePrefix = null;
    if (folder.getWantsPackagePrefix()) {
      packagePrefix = javaPackageFinder.findJavaPackage(folder.getPath().resolve("notfound"));
    }
    return IjSourceFolder.builder()
        .setType(folder.getType().getIjName())
        .setUrl(toModuleDirRelativeString(folder.getPath(), moduleLocationBasePath))
        .setIsTestSource(folder.isTest())
        .setPackagePrefix(packagePrefix)
        .build();
  }

  private ContentRoot createContentRoot(
      Path contentRootPath,
      ImmutableSet<IjFolder> folders,
      final Path moduleLocationBasePath) {
    String url = toModuleDirRelativeString(contentRootPath, moduleLocationBasePath);
    ImmutableSortedSet<IjSourceFolder> sourceFolders = FluentIterable.from(folders)
        .transform(
            new Function<IjFolder, IjSourceFolder>() {
              @Override
              public IjSourceFolder apply(IjFolder input) {
                return createSourceFolder(input, moduleLocationBasePath);
              }
            })
        .toSortedSet(Ordering.natural());
    return ContentRoot.builder()
        .setUrl(url)
        .setFolders(sourceFolders)
        .build();
  }

  public ImmutableSet<IjFolder> createExcludes(IjModule module) throws IOException {
    final ImmutableSet.Builder<IjFolder> excludesBuilder = ImmutableSet.builder();
    final Path moduleBasePath = module.getModuleBasePath();
    projectFilesystem.walkRelativeFileTree(
        moduleBasePath, new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir, BasicFileAttributes attrs) throws IOException {
            // This is another module that's nested in this one. The entire subtree will be handled
            // When we create excludes for that module.
            if (filesystemTraversalBoundaryPaths.contains(dir) && !moduleBasePath.equals(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (!referencedFolderPaths.contains(dir)) {
              excludesBuilder.add(
                  IjFolder.builder()
                      .setPath(dir)
                      .setType(AbstractIjFolder.Type.EXCLUDE_FOLDER)
                      .setWantsPackagePrefix(false)
                      .build());
              return FileVisitResult.SKIP_SUBTREE;
            }

            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(
              Path file, BasicFileAttributes attrs) throws IOException {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
          }
        });
    return excludesBuilder.build();
  }

  public ContentRoot getContentRoot(IjModule module) throws IOException {
    Path moduleBasePath = module.getModuleBasePath();
    Path moduleLocation = getModuleOutputFilePath(module.getName());
    final Path moduleLocationBasePath =
        (moduleLocation.getParent() == null) ? Paths.get("") : moduleLocation.getParent();
    ImmutableSet<IjFolder> sourcesAndExcludes = FluentIterable.from(module.getFolders())
        .append(createExcludes(module))
        .toSet();
    return createContentRoot(moduleBasePath, sourcesAndExcludes, moduleLocationBasePath);
  }

  public ImmutableSet<DependencyEntry> getDependencies(IjModule module) {
    ImmutableMap<IjProjectElement, IjModuleGraph.DependencyType> deps =
        moduleGraph.getDepsFor(module);
    IjDependencyListBuilder dependencyListBuilder = new IjDependencyListBuilder();

    for (Map.Entry<IjProjectElement, IjModuleGraph.DependencyType> entry : deps.entrySet()) {
      IjProjectElement element = entry.getKey();
      IjModuleGraph.DependencyType dependencyType = entry.getValue();
      element.addAsDependency(dependencyType, dependencyListBuilder);
    }
    return dependencyListBuilder.build();
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractModuleIndexEntry implements Comparable<ModuleIndexEntry> {
    public abstract String getFileUrl();
    public abstract Path getFilePath();
    @Nullable public abstract String getGroup();

    @Override
    public int compareTo(ModuleIndexEntry o) {
      return getFilePath().compareTo(o.getFilePath());
    }
  }

  public ImmutableSortedSet<ModuleIndexEntry> getModuleIndexEntries() {
    return FluentIterable.from(moduleGraph.getNodes())
        .filter(IjModule.class)
        .transform(
            new Function<IjModule, ModuleIndexEntry>() {
              @Override
              public ModuleIndexEntry apply(IjModule module) {
                Path moduleOutputFilePath = getModuleOutputFilePath(module.getName());
                String fileUrl = toProjectDirRelativeString(moduleOutputFilePath);
                // The root project module cannot belong to any group.
                String group = (module.getModuleBasePath().toString().isEmpty()) ? null : "modules";
                return  ModuleIndexEntry.builder()
                    .setFileUrl(fileUrl)
                    .setFilePath(moduleOutputFilePath)
                    .setGroup(group)
                    .build();
              }
            })
        .toSortedSet(Ordering.natural());
  }
}
