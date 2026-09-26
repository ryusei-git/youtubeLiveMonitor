package com.example.monitor.sound;

import com.example.monitor.sound.EarKissDetector.Result;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * 耳キスの検出器が、理論調査の参照実装（Python。Issue #466）と同じ値を出すことを、合成音の正解データで確かめる。
 * 検出器を直したり重みを変えたりしたときに、計算が黙って変わるのを防ぐため。
 *
 * <p><b>途中の値まで比べ、段ごとにテストを分けている理由。</b>最終の候補だけを比べると、途中の計算が変わっても
 * 結果がたまたま同じなら気づけない。背景 → 立ち上がり → 候補 → 目立つ候補 → 区間 → 最終の候補の順に分けておけば、
 * 食い違ったときに、最初にずれた段がテストの名前で分かる。1 つのテストの中では食い違いをまとめて出し、
 * 失敗の文言には期待値の JSON の中の場所（例 {@code majors[3].features.s3}）・期待値・実際の値が出る。
 *
 * <p><b>正解データ</b>（{@code src/test/resources/earkiss/}。形式・比べ方・音の中身は同じ場所の README.md）
 * <ul>
 *   <li>PCM は合成した音だけでできている（配信の音声をリポジトリに入れないため）。ffmpeg を通さずに、
 *       バイト列をそのまま検出器に渡す。録画ファイルを使うと、ffmpeg の版でデコードされた音が変わり、
 *       検出器が正しくても落ちるようになる。</li>
 *   <li>期待値は参照実装が計算したもの。<b>落ちたときに、検出器が出した値で期待値を書き換えない</b>
 *       （それでは突き合わせにならない）。検出器の誤りなら検出器を直し、重みや仕様を変えたのなら、
 *       参照実装（Issue #466 の結論の次のコメントに全文がある）で期待値を作り直す。</li>
 *   <li>整数（フレーム・耳・数・細かい包絡の値と位置）は完全一致を求める。この PCM では、どの判定もしきい値から
 *       十分離れていて（いちばん近い立ち上がりの判定でも 0.0149dB）、double の計算の差でほかの分かれ道に進まないため。</li>
 *   <li>移植でやりがちな誤りのうち、この正解データで見つけられないものが 6 通りある（どれも値がちょうど等しいときだけ
 *       結果が変わるもの。例: 丸めを {@code Math.round} にしても、丸める前の値が .5 ちょうどにならない限り同じ）。
 *       一覧は README.md。</li>
 * </ul>
 */
@DisplayName("EarKissDetector")
class EarKissDetectorTest {

    /**
     * 実数を比べるときに許す絶対誤差（Issue #466 で決めた値）。
     *
     * <p>double で正しく書けば、参照実装（numpy）との差は FFT の書き方や足す順番の違いから来る丸めの差だけで、
     * この正解データで 1e-12 ほど（#466 の実測で最大 9.4e-13。この検出器では 4.5e-14）。
     * 一方、FFT に float が混ざると 1e-6 前後ずれる（#466 の実測で背景 1.6e-6・特徴 2.6e-6。
     * この検出器の FFT の入力と出力を float に丸めると、背景 1.6e-6・特徴 2.5e-6）。
     * 1e-9 はその間にあり、正しく double で書けば通り、float が混ざれば落ちる。
     * 1e-6 まで緩めると、float が混ざったことをほぼ見逃す。
     */
    private static final double TOLERANCE = 1e-9;

    /** 参照実装の期待値（{@code ear-kiss-fixture-expected.json}）。 */
    private static JsonNode expected;

    /** 正解データの PCM を検出器に通した結果。どのテストも同じ結果を比べるので、1 回だけ計算する。 */
    private static Result actual;

    @BeforeAll
    static void detectFixture() throws Exception {
        expected = JsonMapper.shared().readTree(fixture("ear-kiss-fixture-expected.json"));
        byte[] pcm = fixture("ear-kiss-fixture.pcm");
        // PCM か重みが期待値を計算したときと違うと、検出器のずれと見分けられないので先に確かめる
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm)))
                .as("PCM の sha256（期待値を計算した PCM と同じか）")
                .isEqualTo(expected.required("pcm").required("sha256").stringValue());
        EarKissModel model = EarKissModel.load();
        assertThat(model.version())
                .as("期待値の計算に使った重みの版（重みを変えたら、参照実装で期待値を作り直す）")
                .isEqualTo(expected.required("model").stringValue());
        actual = new EarKissDetector(model).detect(new ByteArrayInputStream(pcm));
    }

    @Nested
    @DisplayName("detect()")
    class Detect {

        @Test
        @DisplayName("正常系：帯域フレームと細かいフレームの数が参照実装と一致する")
        void testMethod01() {
            compare(root -> {
                root.integer("bandFrames", actual.bandFrames());
                root.integer("fineFrames", actual.fineFrames());
            });
        }

        @Test
        @DisplayName("正常系：ブロックごとの背景が参照実装と一致する")
        void testMethod02() {
            compare(root -> root.reals("bgBlocks", actual.bgBlocks()));
        }

        @Test
        @DisplayName("正常系：立ち上がりのフレームが参照実装と一致する")
        void testMethod03() {
            compare(root -> root.integers("onsets", actual.onsets()));
        }

        @Test
        @DisplayName("正常系：候補のフレーム・D・背景が参照実装と一致する")
        void testMethod04() {
            compare(root -> root.each("candidates", actual.candidates(), (item, candidate) -> {
                item.integer("frame", candidate.frame());
                item.real("D", candidate.d());
                item.real("bg", candidate.bg());
            }));
        }

        @Test
        @DisplayName("正常系：目立つ候補の山のフレーム・耳・12 特徴・logit・p・途中の値が参照実装と一致する")
        void testMethod05() {
            compare(root -> root.each("majors", actual.majors(), (item, major) -> {
                item.integer("frame", major.frame());
                item.integer("ear", major.ear());
                Item features = item.child("features");
                for (int i = 0; i < EarKissDetector.FEATURES.size(); i++) {
                    features.real(EarKissDetector.FEATURES.get(i), major.features()[i]);
                }
                item.real("logit", major.logit());
                item.real("p", major.p());
                Item debug = item.child("debug");
                debug.real("hfL", major.debug().hfL());
                debug.real("hfR", major.debug().hfR());
                debug.real("bg", major.debug().bg());
                debug.integer("fineQ0", major.debug().fineQ0());
                debug.integer("finePeak", major.debug().finePeak());
                debug.integer("fineTop", major.debug().fineTop());
                debug.integer("burstFirst", major.debug().burstFirst());
                debug.integer("burstLast", major.debug().burstLast());
                debug.integers("subPeaks", major.debug().subPeaks());
            }));
        }

        @Test
        @DisplayName("正常系：目立つ候補をつないだ区間が参照実装と一致する")
        void testMethod06() {
            compare(root -> root.each("segments", actual.segments(), (item, segment) -> {
                item.integer("startFrame", segment.startFrame());
                item.integer("endFrame", segment.endFrame());
                item.integer("count", segment.count());
                item.integer("bestFrame", segment.bestFrame());
                item.real("bestP", segment.bestP());
                item.bool("enoughEvents", segment.enoughEvents());
                item.bool("kept", segment.kept());
            }));
        }

        @Test
        @DisplayName("正常系：最終の候補が参照実装と一致する")
        void testMethod07() {
            compare(root -> root.each("final", actual.finals(), (item, candidate) -> {
                item.integer("frame", candidate.frame());
                item.integer("ear", candidate.ear());
                item.real("p", candidate.score());
                item.integer("count", candidate.count());
                item.integer("startFrame", candidate.startFrame());
                item.integer("endFrame", candidate.endFrame());
            }));
        }
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = EarKissDetectorTest.class.getResourceAsStream("/earkiss/" + name)) {
            return Objects.requireNonNull(in, "正解データが見つかりません: " + name).readAllBytes();
        }
    }

    /** 期待値の JSON 全体を根にして比べ、食い違いを最後にまとめて出す（最初の 1 つで止めず、ずれ方の全体を見るため）。 */
    private static void compare(Consumer<Item> body) {
        assertSoftly(softly -> body.accept(new Item(softly, "", expected)));
    }

    /**
     * 期待値の JSON の 1 つのオブジェクト（{@code node}）と、実際の値を欄ごとに比べる。
     * 欄ごとに JSON の中の場所を {@code as} で名前に付け、失敗の文言だけで期待値の JSON の該当箇所を引けるようにする。
     *
     * @param softly 食い違いをためておく先
     * @param name   {@code node} の JSON の中の場所（根なら空）
     * @param node   期待値
     */
    private record Item(SoftAssertions softly, String name, JsonNode node) {

        private String label(String field) {
            return name.isEmpty() ? field : name + "." + field;
        }

        Item child(String field) {
            return new Item(softly, label(field), node.required(field));
        }

        void integer(String field, int actualValue) {
            softly.assertThat(actualValue).as(label(field)).isEqualTo(node.required(field).intValue());
        }

        void bool(String field, boolean actualValue) {
            softly.assertThat(actualValue).as(label(field)).isEqualTo(node.required(field).booleanValue());
        }

        void real(String field, double actualValue) {
            softly.assertThat(actualValue).as(label(field))
                    .isCloseTo(node.required(field).doubleValue(), within(TOLERANCE));
        }

        /** 整数の配列。数と並びまで一致させる。 */
        void integers(String field, int[] actualValues) {
            int[] expectedValues = node.required(field).values().stream().mapToInt(JsonNode::intValue).toArray();
            softly.assertThat(actualValues).as(label(field)).containsExactly(expectedValues);
        }

        /** 実数の配列。数は一致させ、各要素は {@code TOLERANCE} 以内。 */
        void reals(String field, double[] actualValues) {
            JsonNode expectedValues = node.required(field);
            softly.assertThat(actualValues.length).as(label(field) + " の数").isEqualTo(expectedValues.size());
            for (int i = 0; i < Math.min(actualValues.length, expectedValues.size()); i++) {
                softly.assertThat(actualValues[i]).as(label(field) + "[" + i + "]")
                        .isCloseTo(expectedValues.get(i).doubleValue(), within(TOLERANCE));
            }
        }

        /**
         * オブジェクトの配列。数を一致させ、同じ番号どうしを {@code compareOne} で比べる。
         * 数が違っても、そろっている番号までは比べる（どこからずれたかを見るため）。
         */
        <T> void each(String field, List<T> actualValues, BiConsumer<Item, T> compareOne) {
            JsonNode expectedValues = node.required(field);
            softly.assertThat(actualValues.size()).as(label(field) + " の数").isEqualTo(expectedValues.size());
            for (int i = 0; i < Math.min(actualValues.size(), expectedValues.size()); i++) {
                compareOne.accept(new Item(softly, label(field) + "[" + i + "]", expectedValues.get(i)), actualValues.get(i));
            }
        }
    }
}
