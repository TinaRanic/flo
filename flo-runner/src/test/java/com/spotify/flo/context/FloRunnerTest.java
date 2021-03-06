/*-
 * -\-\-
 * Flo Runner
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

package com.spotify.flo.context;

import static com.spotify.flo.context.FloRunner.runTask;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.spotify.flo.FloTesting;
import com.spotify.flo.Task;
import com.spotify.flo.TaskBuilder.F1;
import com.spotify.flo.TaskId;
import com.spotify.flo.TestScope;
import com.spotify.flo.Tracing;
import com.spotify.flo.context.FloRunner.Result;
import com.spotify.flo.context.Jobs.JobOperator;
import com.spotify.flo.context.Mocks.DataProcessing;
import com.spotify.flo.context.Mocks.PublishingContext;
import com.spotify.flo.context.Mocks.StorageLookup;
import com.spotify.flo.freezer.Persisted;
import com.spotify.flo.freezer.PersistingContext;
import com.spotify.flo.status.NotReady;
import com.spotify.flo.status.NotRetriable;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FloRunnerTest {

  private static final Logger log = LoggerFactory.getLogger(FloRunnerTest.class);

  final Task<String> FOO_TASK = Task.named("task").ofType(String.class)
      .process(() -> "foo");

  private TerminationHook validTerminationHook;
  private TerminationHook exceptionalTerminationHook;

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void setUp() {
    exceptionalTerminationHook = mock(TerminationHook.class);
    doThrow(new RuntimeException("hook exception")).when(exceptionalTerminationHook).accept(any());

    validTerminationHook = mock(TerminationHook.class);
    doNothing().when(validTerminationHook).accept(any());

    TestTerminationHookFactory.injectCreator((config) -> validTerminationHook);
  }

  @Test
  public void nonBlockingRunnerDoesNotBlock() throws Exception {
    final Path directory = temporaryFolder.newFolder().toPath();
    final Path startedFile = directory.resolve("started");
    final Path latchFile = directory.resolve("latch");
    final Path happenedFile = directory.resolve("happened");

    final Task<Void> task = Task.named("task").ofType(Void.class)
        .process(() -> {
          try {
            Files.write(startedFile, new byte[0]);
            while (true) {
              if (Files.exists(latchFile)) {
                Files.write(happenedFile, new byte[0]);
                return null;
              }
              Thread.sleep(100);
            }
          } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
          }
        });

    final Result<Void> result = runTask(task);

    // Verify that the task ran at all
    CompletableFuture.supplyAsync(() -> {
      while (true) {
        if (Files.exists(startedFile)) {
          return true;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }).get(30, SECONDS);

    // Wait a little more to ensure that the task process has some time to write the "happened" file
    try {
      result.future().get(2, SECONDS);
      fail();
    } catch (TimeoutException ignore) {
    }

    // If this file doesn't exist now, it's likely that runTask doesn't block
    assertThat(Files.exists(happenedFile), is(false));

    Files.write(latchFile, new byte[0]);
  }

  @Test
  public void blockingRunnerBlocks() throws IOException {
    final Path file = temporaryFolder.newFile().toPath();

    final Task<Void> task = Task.named("task").ofType(Void.class)
        .process(() -> {
          try {
            Thread.sleep(10);
            try {
              Files.write(file, "hello".getBytes(UTF_8));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          return null;
        });

    runTask(task).waitAndExit(status -> { });

    assertThat(new String(Files.readAllBytes(file), UTF_8), is("hello"));
  }

  @Test
  public void valueIsPassedInFuture() throws Exception {
    final String result = runTask(FOO_TASK).future().get(30, SECONDS);

    assertThat(result, is("foo"));
  }

  @Test
  public void testSerializeException() throws Exception {
    final File file = temporaryFolder.newFile();
    file.delete();
    PersistingContext.serialize(new RuntimeException("foo"), file.toPath());
  }

  @Test
  public void exceptionsArePassed() throws Exception {
    final Task<String> task = Task.named("foo").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("foo");
        });

    Throwable exception = null;
    try {
      runTask(task).value();
    } catch (ExecutionException e) {
      exception = e.getCause();
    }
    assertThat(exception, is(instanceOf(RuntimeException.class)));
    assertThat(exception.getMessage(), is("foo"));
  }

  @Test
  public void errorsArePassed() throws Exception {
    final Task<String> task = Task.named("foo").ofType(String.class)
        .process(() -> {
          throw new Error("foo");
        });

    Throwable exception = null;
    try {
      runTask(task).value();
    } catch (ExecutionException e) {
      exception = e.getCause();
    }
    assertThat(exception, is(instanceOf(Error.class)));
    assertThat(exception.getMessage(), is("foo"));
  }

  @Test
  public void persistedExitsZero() {
    final Task<Void> task = Task.named("persisted").ofType(Void.class)
        .process(() -> {
          throw new Persisted();
        });

    AtomicInteger status = new AtomicInteger(1);

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(0));
  }

  @Test
  public void valuesCanBeWaitedOn() throws Exception {
    final String result = runTask(FOO_TASK).value();

    assertThat(result, is("foo"));
  }

  @Test
  public void notReadyExitsTwenty() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new NotReady();
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(20));
  }

  @Test
  public void notRetriableExitsFifty() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new NotRetriable();
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(50));
  }

  @Test
  public void exceptionsExitNonZero() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("this task should throw");
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(1));
  }

  @Test
  public void ignoreExceptionsFromTerminationHook() {
    TestTerminationHookFactory.injectHook(exceptionalTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(FOO_TASK).waitAndExit(status::set);

    verify(exceptionalTerminationHook, times(1)).accept(eq(0));
    assertThat(status.get(), is(0));
  }

  @Test
  public void validateTerminationHookInvocationOnTaskSuccess() {
    TestTerminationHookFactory.injectHook(validTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(FOO_TASK).waitAndExit(status::set);

    verify(validTerminationHook, times(1)).accept(eq(0));
    assertThat(status.get(), is(0));
  }

  @Test
  public void validateTerminationHookInvocationOnTaskFailure() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("this task should throw");
        });

    TestTerminationHookFactory.injectHook(validTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(task).waitAndExit(status::set);

    verify(validTerminationHook, times(1)).accept(eq(1));
    assertThat(status.get(), is(1));
  }

  @Test(expected = RuntimeException.class)
  public void failOnExceptionalTerminationHookFactory() {
    TestTerminationHookFactory.injectCreator((config) -> {
      throw new RuntimeException("factory exception");
    });
    runTask(FOO_TASK);
  }

  @Test
  public void taskIdIsInContext() throws Exception {
    final Task<TaskId> task = Task.named("task").ofType(TaskId.class)
        .process(() -> Tracing.TASK_ID.get());

    final Result<TaskId> result = runTask(task);

    assertThat(result.value(), is(task.id()));
  }

  @Test
  public void tasksRunInProcesses() throws Exception {

    final Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    final Instant yesterday = today.minus(1, ChronoUnit.DAYS);

    final Task<String> baz = Task.named("baz", today).ofType(String.class)
        .process(() -> {
          final String bazJvm = jvmName();
          log.info("baz: bazJvm={}, today={}", bazJvm, today);
          return bazJvm;
        });

    final Task<String[]> foo = Task.named("foo", yesterday).ofType(String[].class)
        .input(() -> baz)
        .process(bazJvm -> {
          final String fooJvm = jvmName();
          log.info("foo: fooJvm={}, bazJvm={}, yesterday={}", fooJvm, bazJvm, yesterday);
          return new String[]{bazJvm, fooJvm};
        });

    final Task<String> quux = Task.named("quux", today).ofType(String.class)
        .process(() -> {
          final String quuxJvm = jvmName();
          log.info("quux: quuxJvm={}, yesterday={}", quuxJvm, yesterday);
          return quuxJvm;
        });

    final Task<String[]> bar = Task.named("bar", today, yesterday).ofType(String[].class)
        .input(() -> foo)
        .input(() -> quux)
        .process((bazFooJvms, quuxJvm) -> {
          final String barJvm = jvmName();
          log.info("bar: barJvm={}, bazFooJvms={}, quuxJvm={} today={}, yesterday={}",
              barJvm, bazFooJvms, quuxJvm, today, yesterday);
          return Stream.concat(
              Stream.of(barJvm),
              Stream.concat(
                  Stream.of(bazFooJvms),
                  Stream.of(quuxJvm))
          ).toArray(String[]::new);
        });

    final List<String> jvms = Arrays.asList(runTask(bar).value());

    final String mainJvm = jvmName();

    log.info("main jvm: {}", mainJvm);
    log.info("task jvms: {}", jvms);
    final Set<String> uniqueJvms = new HashSet<>(jvms);
    assertThat(uniqueJvms.size(), is(4));
    assertThat(uniqueJvms, not(contains(mainJvm)));
  }

  @Test
  public void isTestShouldBeTrueInTestScope() throws Exception {
    assertThat(FloTesting.isTest(), is(false));
    try (TestScope ts = FloTesting.scope()) {
      assertThat(FloTesting.isTest(), is(true));
      final Task<Boolean> isTest = Task.named("task").ofType(Boolean.class)
          .process(FloTesting::isTest);
      assertThat(runTask(isTest).future().get(30, SECONDS), is(true));
    }
    assertThat(FloTesting.isTest(), is(false));
  }

  @Test
  public void mockingInputsOutputsAndContextShouldBePossibleInTestScope() throws Exception {

    // Mock input, data processing results and verify lookups and publishing after the fact
    try (TestScope ts = FloTesting.scope()) {
      final URI barInput = URI.create("gs://bar/4711/");
      final String jobResult = "42";
      final URI publishResult = URI.create("meta://bar/4711/");

      PublishingContext.mock().publish(jobResult, publishResult);
      StorageLookup.mock().data("bar", barInput);
      DataProcessing.mock().result("quux.baz", barInput, jobResult);

      final Task<String> task = Task.named("task").ofType(String.class)
          .input(() -> StorageLookup.of("bar"))
          .context(PublishingContext.of("foo"))
          .process((bar, publisher) -> {
            // Run a data processing job and publish the result
            final String result = DataProcessing.runJob("quux.baz", bar);
            return publisher.publish(result);
          });

      assertThat(runTask(task).future().get(30, SECONDS), is(jobResult));

      assertThat(DataProcessing.mock().jobRuns("quux.baz", barInput), is(1));
      assertThat(StorageLookup.mock().lookups("bar"), is(1));
      assertThat(PublishingContext.mock().lookups("foo"), is(1));
      assertThat(PublishingContext.mock().published("foo"), contains(jobResult));
    }

    // Verify that all mocks are cleared when leaving scope
    try (TestScope ts = FloTesting.scope()) {
      assertThat(PublishingContext.mock().published("foo"), is(empty()));
      assertThat(PublishingContext.mock().lookups("foo"), is(0));
    }
  }

  @Test
  public void mockingContextExistsShouldMakeProcessFnNotRunInTestScope() throws Exception {

    // Mock a context lookup and verify that the process fn does not run
    try (TestScope ts = FloTesting.scope()) {
      PublishingContext.mock().value("foo", "17");

      @SuppressWarnings("unchecked") final F1<PublishingContext.Value, String> mockProcessFn = Mockito.mock(F1.class);
      when(mockProcessFn.apply(any())).thenThrow(new AssertionError());

      final Task<String> task = Task.named("task").ofType(String.class)
          .context(PublishingContext.of("foo"))
          .process(mockProcessFn);

      assertThat(runTask(task).future().get(30, SECONDS), is("17"));

      verify(mockProcessFn, never()).apply(any());

      assertThat(PublishingContext.mock().lookups("foo"), is(1));
      assertThat(PublishingContext.mock().published("foo"), is(empty()));
    }
  }

  @Test
  public void shouldThrowIfForkingIsExplicitlyEnabledInTestMode() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new AssertionError();
        });

    final Config config = ConfigFactory.load("flo")
        .withValue("flo.forking", ConfigValueFactory.fromAnyRef(true));

    try (TestScope ts = FloTesting.scope()) {
      try {
        FloRunner.runTask(task, config);
      } catch (IllegalStateException e) {
        assertThat(e.getMessage(), is("Forking is not supported in test mode"));
      }
    }
  }

  @Test
  public void testOperator() throws Exception {
    final String mainJvm = jvmName();
    final Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    final Task<JobResult> task = Task.named("task", today).ofType(JobResult.class)
        .context(JobOperator.create())
        .process(job -> job
            .options(() -> ImmutableMap.of("quux", 17))
            .pipeline(ctx -> ctx.readFrom("foo").map("x + y").writeTo("baz"))
            .validation(result -> {
              if (result.records < 5) {
                throw new AssertionError("Too few records seen!");
              }
            })
            .success(result -> new JobResult(jvmName(), "hdfs://foo/bar")));

    final JobResult result = FloRunner.runTask(task)
        .future().get(30, SECONDS);

    assertThat(result.jvmName, is(not(mainJvm)));
    assertThat(result.uri, is("hdfs://foo/bar"));
  }

  private static String jvmName() {
    return ManagementFactory.getRuntimeMXBean().getName();
  }

  private static class JobResult {
    private final String jvmName;
    private final String uri;

    JobResult(String jvmName, String uri) {
      this.jvmName = jvmName;
      this.uri = uri;
    }
  }
}
