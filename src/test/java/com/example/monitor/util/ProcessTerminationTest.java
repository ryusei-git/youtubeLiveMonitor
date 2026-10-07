package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProcessTermination")
class ProcessTerminationTest {

    @Nested
    @DisplayName("destroyForciblyAndAwait()")
    class DestroyForciblyAndAwait {

        @Test
        @DisplayName("正常系：強制終了してから終了を待ち、false を返す")
        void testMethod01() throws InterruptedException {
            Process process = mock(Process.class);
            when(process.waitFor()).thenReturn(137);

            boolean interrupted = ProcessTermination.destroyForciblyAndAwait(process);

            assertThat(interrupted).isFalse();
            InOrder order = inOrder(process);
            order.verify(process).destroyForcibly();
            order.verify(process).waitFor();
        }

        @Test
        @DisplayName("異常系：終了待ちで割り込まれたら true を返し、割り込み状態は立て直さない")
        void testMethod02() throws InterruptedException {
            Process process = mock(Process.class);
            when(process.waitFor()).thenThrow(new InterruptedException());

            boolean interrupted;
            boolean interruptedAfter;
            try {
                interrupted = ProcessTermination.destroyForciblyAndAwait(process);
                interruptedAfter = Thread.currentThread().isInterrupted();
            } finally {
                // JUnit は同じスレッドで次のテストを走らせるので、割り込み状態を必ず消してから終える
                Thread.interrupted();
            }

            assertThat(interrupted).isTrue();
            assertThat(interruptedAfter).isFalse();
        }
    }

    @Nested
    @DisplayName("terminateTreeAndAwait()")
    class TerminateTreeAndAwait {

        @Test
        @DisplayName("正常系：子孫を止める前に集め、猶予内に終わったものには SIGKILL を送らない")
        void testMethod01() {
            ProcessHandle root = mock(ProcessHandle.class);
            ProcessHandle child = mock(ProcessHandle.class);
            when(root.descendants()).thenReturn(Stream.of(child));
            when(root.onExit()).thenReturn(CompletableFuture.completedFuture(root));
            when(child.onExit()).thenReturn(CompletableFuture.completedFuture(child));

            boolean interrupted = ProcessTermination.terminateTreeAndAwait(root, Duration.ofSeconds(10));

            assertThat(interrupted).isFalse();
            // child と root のどちらを先に止めるかは決まりではないので、root だけで順序を見る
            InOrder order = inOrder(root);
            order.verify(root).descendants();
            order.verify(root).destroy();
            verify(child).destroy();
            verify(root, never()).destroyForcibly();
            verify(child, never()).destroyForcibly();
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("正常系：猶予を過ぎても残っているものだけに SIGKILL を送り、終わるまで待つ")
        void testMethod02() {
            ProcessHandle root = mock(ProcessHandle.class);
            ProcessHandle child = mock(ProcessHandle.class);
            when(root.descendants()).thenReturn(Stream.of(child));
            CompletableFuture<ProcessHandle> childExit = new CompletableFuture<>();
            when(root.onExit()).thenReturn(CompletableFuture.completedFuture(root));
            when(child.onExit()).thenReturn(childExit);
            when(child.isAlive()).thenReturn(true);
            when(child.destroyForcibly()).thenAnswer(invocation -> {
                childExit.complete(child);
                return true;
            });

            boolean interrupted = ProcessTermination.terminateTreeAndAwait(root, Duration.ofMillis(100));

            assertThat(interrupted).isFalse();
            InOrder order = inOrder(child);
            order.verify(child).destroy();
            order.verify(child).destroyForcibly();
            verify(root, never()).destroyForcibly();
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("異常系：待っている間に割り込まれたら、すべてに SIGKILL を送って true を返す")
        void testMethod03() {
            ProcessHandle root = mock(ProcessHandle.class);
            ProcessHandle child = mock(ProcessHandle.class);
            when(root.descendants()).thenReturn(Stream.of(child));
            when(root.onExit()).thenReturn(new CompletableFuture<>());
            when(child.onExit()).thenReturn(new CompletableFuture<>());

            boolean interrupted;
            Thread.currentThread().interrupt();
            try {
                interrupted = ProcessTermination.terminateTreeAndAwait(root, Duration.ofSeconds(10));
            } finally {
                // JUnit は同じスレッドで次のテストを走らせるので、割り込み状態を必ず消してから終える
                Thread.interrupted();
            }

            assertThat(interrupted).isTrue();
            verify(child).destroyForcibly();
            verify(root).destroyForcibly();
        }
    }

    @Nested
    @DisplayName("terminateTree()")
    class TerminateTree {

        @Test
        @DisplayName("正常系：猶予内に全員終われば forced は false で、beforeKill を呼ばず SIGKILL も送らない")
        void testMethod01() throws InterruptedException {
            // モックの既定で onExit() は完了済み、isAlive() は false（すぐ終わったプロセス）
            ProcessHandle parent = mock(ProcessHandle.class);
            ProcessHandle child = mock(ProcessHandle.class);
            Runnable beforeKill = mock(Runnable.class);

            ProcessTermination.TreeResult result = ProcessTermination.terminateTree(List.of(parent, child),
                    Duration.ofSeconds(10), Duration.ofSeconds(5), beforeKill);

            assertThat(result.forced()).isFalse();
            assertThat(result.remaining()).isEmpty();
            verify(parent).destroy();
            verify(child).destroy();
            verify(beforeKill, never()).run();
            verify(parent, never()).destroyForcibly();
            verify(child, never()).destroyForcibly();
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("正常系：猶予を過ぎて残ったものがあれば、beforeKill を 1 回呼んでから残りだけに SIGKILL を送る")
        void testMethod02() throws InterruptedException {
            ProcessHandle parent = mock(ProcessHandle.class);
            ProcessHandle child = mock(ProcessHandle.class);
            Runnable beforeKill = mock(Runnable.class);
            // child だけが SIGTERM では終わらず、SIGKILL で終わる
            AtomicBoolean childAlive = new AtomicBoolean(true);
            CompletableFuture<ProcessHandle> childExit = new CompletableFuture<>();
            when(child.onExit()).thenReturn(childExit);
            when(child.isAlive()).thenAnswer(invocation -> childAlive.get());
            when(child.destroyForcibly()).thenAnswer(invocation -> {
                childAlive.set(false);
                childExit.complete(child);
                return true;
            });

            ProcessTermination.TreeResult result = ProcessTermination.terminateTree(List.of(parent, child),
                    Duration.ofMillis(100), Duration.ofSeconds(1), beforeKill);

            assertThat(result.forced()).isTrue();
            assertThat(result.remaining()).isEmpty();
            InOrder order = inOrder(child, beforeKill);
            order.verify(child).destroy();
            order.verify(beforeKill).run();
            order.verify(child).destroyForcibly();
            verify(beforeKill).run();
            verify(parent, never()).destroyForcibly();
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("異常系：SIGKILL の後も killWait を過ぎて残ったもの（D 状態など）は remaining に入る")
        void testMethod03() throws InterruptedException {
            ProcessHandle parent = mock(ProcessHandle.class);
            ProcessHandle stuck = mock(ProcessHandle.class);
            when(stuck.onExit()).thenReturn(new CompletableFuture<>());
            when(stuck.isAlive()).thenReturn(true);

            ProcessTermination.TreeResult result = ProcessTermination.terminateTree(List.of(parent, stuck),
                    Duration.ofMillis(100), Duration.ofMillis(100), () -> { });

            assertThat(result.forced()).isTrue();
            assertThat(result.remaining()).containsExactly(stuck);
            verify(stuck).destroyForcibly();
            verify(parent, never()).destroyForcibly();
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("異常系：待っている間に割り込まれたら、全員に SIGKILL を送って InterruptedException を投げる")
        void testMethod04() {
            ProcessHandle parent = mock(ProcessHandle.class);
            ProcessHandle child = mock(ProcessHandle.class);
            when(parent.onExit()).thenReturn(new CompletableFuture<>());
            when(child.onExit()).thenReturn(new CompletableFuture<>());

            Throwable thrown;
            Thread.currentThread().interrupt();
            try {
                thrown = catchThrowable(() -> ProcessTermination.terminateTree(List.of(parent, child),
                        Duration.ofSeconds(10), Duration.ofSeconds(5), () -> { }));
            } finally {
                // JUnit は同じスレッドで次のテストを走らせるので、割り込み状態を必ず消してから終える
                Thread.interrupted();
            }

            assertThat(thrown).isInstanceOf(InterruptedException.class);
            verify(parent).destroyForcibly();
            verify(child).destroyForcibly();
        }
    }
}
