package com.example.monitor.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RootCommand")
class RootCommandTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void redirectStreams() {
        originalOut = System.out;
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
    }

    @Nested
    @DisplayName("run()")
    class Run {

        @Test
        @DisplayName("正常系：使い方（usage）を標準出力へ表示する")
        void testMethod01() {
            new RootCommand().run();

            assertThat(outContent.toString()).contains("Usage").contains("monitor");
        }
    }
}
