/*-
 * -\-\-
 * Flo Styx
 * --
 * Copyright (C) 2016 - 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.flo.contrib.styx;

import static com.spotify.flo.contrib.styx.LabelUtil.buildLabels;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

import com.google.common.base.Strings;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class LabelUtilTest {
  
  @Mock
  private Config config;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void shouldBuildLabels() {
    final Map<String, String> labels = buildLabels("foo", ConfigFactory.load());
    final Map<String, String> expected = new HashMap<>();
    expected.put("foo-styx-component-id", "unknown-component-id");
    expected.put("foo-styx-workflow-id", "unknown-workflow-id");
    expected.put("foo-styx-parameter", "unknown-parameter");
    expected.put("foo-styx-execution-id", "unknown-execution-id");
    expected.put("foo-styx-trigger-id", "unknown-trigger-id");
    expected.put("foo-styx-trigger-type", "unknown-trigger-type");

    assertThat(labels, is(expected));
  }
  
  @Test
  public void shouldFailIfKeyTooLong() {
    final String labelPrefix = Strings.repeat("foo", 30);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(String
        .format("Invalid label key: Too long, must be <= 63 characters: %s-styx-component-id",
            labelPrefix));

    buildLabels(Strings.repeat("foo", 30), config);
  }

  @Test
  public void shouldFailIfInvalidKey() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Invalid label key: 123-styx-component-id");

    buildLabels("123", config);
  }

  @Test
  public void shouldHandleValuesWithCorrectPadding() {
    when(config.hasPath(anyString())).thenReturn(true);

    when(config.getString("styx.component.id")).thenReturn("0123-");
    when(config.getString("styx.workflow.id")).thenReturn("0123-");
    when(config.getString("styx.parameter")).thenReturn("0123-");
    when(config.getString("styx.execution.id")).thenReturn("0123-");
    when(config.getString("styx.trigger.id")).thenReturn("0123-");
    when(config.getString("styx.trigger.type")).thenReturn("0123-");

    final Map<String, String> labels = buildLabels("foo", config);
    final Map<String, String> expected = new HashMap<>();
    expected.put("foo-styx-component-id", "c-0123-c");
    expected.put("foo-styx-workflow-id", "w-0123-w");
    expected.put("foo-styx-parameter", "p-0123-p");
    expected.put("foo-styx-execution-id", "e-0123-e");
    expected.put("foo-styx-trigger-id", "t-0123-t");
    expected.put("foo-styx-trigger-type", "tt-0123-tt");

    assertThat(labels, is(expected));
  }

  @Test
  public void shouldHandleValues() {
    when(config.hasPath(anyString())).thenReturn(true);
    
    // missing value
    when(config.hasPath("styx.component.id")).thenReturn(false);
    // . should be replaced
    when(config.getString("styx.workflow.id")).thenReturn("foo.bar");
    // too long should be trimmed
    when(config.getString("styx.parameter")).thenReturn(Strings.repeat("foo", 40));
    // should be padded
    when(config.getString("styx.execution.id")).thenReturn("0123-");
    // becomes invalid after trimmed
    when(config.getString("styx.trigger.id")).thenReturn(Strings.repeat("foo123-", 30));
    // valid value
    when(config.getString("styx.trigger.type")).thenReturn("foo-bar");

    final Map<String, String> labels = buildLabels("foo", config);
    final Map<String, String> expected = new HashMap<>();
    expected.put("foo-styx-workflow-id", "foo-bar");
    expected.put("foo-styx-parameter", Strings.repeat("foo", 21));
    expected.put("foo-styx-execution-id", "e-0123-e");
    expected.put("foo-styx-trigger-type", "foo-bar");

    assertThat(labels, is(expected));
  }
}
