package com.example.monitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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

}
