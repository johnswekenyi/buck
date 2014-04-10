/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;


public class ProjectConfig extends AbstractBuildable {

  @Nullable
  private final BuildRule srcRule;

  /** Likely empty for a directory that contains only an android_binary() rule. */
  @Nullable
  private final ImmutableList<SourceRoot> srcSourceRoots;

  @Nullable
  private final BuildRule testRule;

  @Nullable
  private final ImmutableList<SourceRoot> testsSourceRoots;

  private final boolean isIntelliJPlugin;

  private final BuildTarget buildTarget;

  protected ProjectConfig(
      BuildTarget buildTarget,
      @Nullable BuildRule srcRule,
      @Nullable List<String> srcRoots,
      @Nullable BuildRule testRule,
      @Nullable List<String> testRoots,
      boolean isIntelliJPlugin) {
    Preconditions.checkArgument(srcRule != null || testRule != null,
        "At least one of src_target or test_target must be specified in %s.",
        buildTarget.getFullyQualifiedName());
    Preconditions.checkArgument(testRule == null || testRule.getType().isTestRule(),
        "The test_target for a project_config() must correspond to a test rule, if specified, " +
        "but was %s.",
        testRule);

    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.srcRule = srcRule;
    if (srcRoots != null) {
      this.srcSourceRoots = ImmutableList.copyOf(Iterables.transform(srcRoots,
          new Function<String, SourceRoot>() {
        @Override
        public SourceRoot apply(String srcRoot) {
          return new SourceRoot(srcRoot);
        }
      }));
    } else {
      this.srcSourceRoots = null;
    }

    this.testRule = testRule;
    if (testRoots != null) {
      this.testsSourceRoots = ImmutableList.copyOf(Iterables.transform(testRoots,
          new Function<String, SourceRoot>() {
        @Override
        public SourceRoot apply(String testRoot) {
          return new SourceRoot(testRoot);
        }
      }));
    } else {
      this.testsSourceRoots = null;
    }

    this.isIntelliJPlugin = isIntelliJPlugin;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  /**
   * @return the BuildRule that should determine the type of IDE project to create. This will be
   *     the srcRule, if it is present; otherwise, it will be the test rule.
   */
  public BuildRule getProjectRule() {
    if (getSrcRule() != null) {
      return getSrcRule();
    } else {
      return getTestRule();
    }
  }

  @Nullable
  public BuildRule getSrcRule() {
    return srcRule;
  }

  @Nullable
  public BuildRule getTestRule() {
    return testRule;
  }

  @Nullable
  public ImmutableList<SourceRoot> getSourceRoots() {
    return srcSourceRoots;
  }

  @Nullable
  public ImmutableList<SourceRoot> getTestsSourceRoots() {
    return testsSourceRoots;
  }

  public boolean getIsIntelliJPlugin() {
    return isIntelliJPlugin;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  public List<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) throws IOException {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("srcRule", srcRule)
        .set("srcSourceRoots", srcSourceRoots)
        .set("testRule", testRule)
        .set("testsSourceRoots", testsSourceRoots)
        .set("isIntelliJPlugin", isIntelliJPlugin);
  }
}