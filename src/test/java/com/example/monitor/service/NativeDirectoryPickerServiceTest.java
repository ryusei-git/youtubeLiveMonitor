package com.example.monitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NativeDirectoryPickerService")
// ArgumentCaptor.forClass(List.class) は総称型の情報を渡せず未検査キャストになる（StreamRecorderTest と同じ）
@SuppressWarnings("unchecked")
class NativeDirectoryPickerServiceTest {

    @Mock
    private ProcessLauncher processLauncher;

    @InjectMocks
    private NativeDirectoryPickerService service;

    /**
     * 指定した標準出力と終了コードを返す、外部コマンドの代わりのプロセスを作る。
     *
     * @param standardOutput 標準出力として読ませたい内容
     * @param exitCode       終了コード
     * @return 組み立てたモックプロセス
     */
    private Process mockProcess(String standardOutput, int exitCode) throws InterruptedException {
        Process process = mock(Process.class);
        when(process.getInputStream())
                .thenReturn(new ByteArrayInputStream(standardOutput.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), any())).thenReturn(true);
        when(process.exitValue()).thenReturn(exitCode);
        return process;
    }

    @Nested
    @DisplayName("pickDirectory()")
    class PickDirectoryMethod {

        @Test
        @DisplayName("正常系：選ばれたパスを返す")
        void testMethod01() throws Exception {
            Process process = mockProcess("/home/user/recordings\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            Optional<String> result = service.pickDirectory(null);

            assertThat(result).contains("/home/user/recordings");
        }

        @Test
        @DisplayName("正常系：終了コードが0以外の場合はキャンセルとして空を返す")
        void testMethod02() throws Exception {
            Process process = mockProcess("", 1);
            when(processLauncher.launch(any())).thenReturn(process);

            assertThat(service.pickDirectory(null)).isEmpty();
        }

        @Test
        @DisplayName("正常系：終了コードが0でも出力が空ならキャンセルとして空を返す")
        void testMethod03() throws Exception {
            Process process = mockProcess("", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            assertThat(service.pickDirectory(null)).isEmpty();
        }

        @Test
        @DisplayName("正常系：初期ディレクトリを指定すると起動コマンドに含める")
        void testMethod04() throws Exception {
            Process process = mockProcess("/home/user/movies\n", 0);
            when(processLauncher.launch(any())).thenReturn(process);

            service.pickDirectory("/home/user/movies");

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            org.mockito.Mockito.verify(processLauncher).launch(captor.capture());
            assertThat(String.join(" ", captor.getValue())).contains("/home/user/movies");
        }

        @Test
        @DisplayName("異常系：ダイアログを起動できない場合は例外を投げる")
        void testMethod05() throws Exception {
            when(processLauncher.launch(any())).thenThrow(new IOException("not found"));

            assertThatThrownBy(() -> service.pickDirectory(null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
