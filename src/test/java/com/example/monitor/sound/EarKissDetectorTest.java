package com.example.monitor.sound;

import com.example.monitor.sound.EarKissDetector.FinalCandidate;
import com.example.monitor.sound.EarKissDetector.MajorPoint;
import com.example.monitor.sound.EarKissDetector.Result;
import com.example.monitor.sound.EarKissDetector.Segment;
import com.example.monitor.sound.EarKissDetector.Selection;
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
 * 耳キスの検出器が、理論調査の参照実装（Python。Issue #466・#480）と同じ値を出すことを、合成音の正解データと
 * 選び方の表で確かめる。検出器を直したり重みを変えたりしたときに、計算が黙って変わるのを防ぐため。
 *
 * <p><b>途中の値まで比べ、段ごとにテストを分けている理由。</b>最終の候補だけを比べると、途中の計算が変わっても
 * 結果がたまたま同じなら気づけない。背景 → 立ち上がり → 候補 → 目立つ候補 → 区間（大きさの基準・G・門）→ 最終の候補の
 * 順に分けておけば、食い違ったときに、最初にずれた段がテストの名前で分かる。1 つのテストの中では食い違いをまとめて出し、
 * 失敗の文言には期待値の JSON の中の場所（例 {@code majors[3].features.s3}）・期待値・実際の値が出る。
 *
 * <p><b>正解データ</b>（{@code src/test/resources/earkiss/}。形式・比べ方・音の中身は同じ場所の README.md）
 * <ul>
 *   <li>PCM は合成した音だけでできている（配信の音声をリポジトリに入れないため）。ffmpeg を通さずに、
 *       バイト列をそのまま検出器に渡す。録画ファイルを使うと、ffmpeg の版でデコードされた音が変わり、
 *       検出器が正しくても落ちるようになる。</li>
 *   <li>期待値は参照実装が計算したもの。<b>落ちたときに、検出器が出した値で期待値を書き換えない</b>
 *       （それでは突き合わせにならない）。検出器の誤りなら検出器を直し、重みや仕様を変えたのなら、
 *       参照実装（Issue #480 の結論の次の 2 つのコメントに全文がある）で期待値を作り直す。</li>
 *   <li>整数（フレーム・耳・数・細かい包絡の値と位置）と真偽は完全一致を求める。この PCM では、どの判定もしきい値から
 *       十分離れていて（いちばん近い立ち上がりの判定でも 0.228dB。G と門の −10dB の差は 2.22dB）、double の計算の差で
 *       ほかの分かれ道に進まないため。</li>
 *   <li>移植でやりがちな誤りのうち、この正解データで見つけられないものが v1 の分に 6 通りある（どれも値がちょうど等しいときだけ
 *       結果が変わるもの。例: 丸めを {@code Math.round} にしても、丸める前の値が .5 ちょうどにならない限り同じ）。
 *       一覧は README.md。</li>
 * </ul>
 *
 * <p><b>選び方の表</b>（{@code ear-kiss-selection-cases.json}）は、音からは作れない境目（G がちょうど −10dB・
 * G が同じ 2 区間・p がちょうど 0.7・目立つ候補が 0〜2 個）を、目立つ候補の値を直接与えて確かめる。音で作っても、
 * 参照実装との 1e-14 ほどの差でどちらにも転ぶため。これが無いと、v2 の移植の誤り 37 通りのうち 7 通り
 * （門の比べを {@code >} にする・G が同じ区間で後ろを残す など）を見逃す（#480）。
 */
@DisplayName("EarKissDetector")
class EarKissDetectorTest {

    /**
     * 実数を比べるときに許す絶対誤差（Issue #466 で決め、#480 でも同じ値にした）。
     *
     * <p>double で正しく書けば、参照実装（numpy）との差は FFT の書き方や足す順番の違いから来る丸めの差だけで、
     * この正解データで 1e-12 ほど（#480 の実測で最大 3.0e-12。この検出器では 2.6e-13 で、ref と G は差 0）。
     * 一方、FFT に float が混ざると 1e-8 以上ずれる（#480 の実測で ref 1.4e-8・G 4.6e-8・特徴 2.7e-4）。
     * 1e-9 はその間にあり、正しく double で書けば通り、float が混ざれば落ちる。
     * 1e-6 まで緩めると、ref と G では float が混ざったことを見逃す。
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
        @DisplayName("正常系：大きさの基準と、目立つ候補をつないだ区間（G・門・上限を含む）が参照実装と一致する")
        void testMethod06() {
            compare(root -> {
                root.real("loudnessRef", actual.loudnessRef());
                root.each("segments", actual.segments(), EarKissDetectorTest::segment);
            });
        }

        @Test
        @DisplayName("正常系：最終の候補が参照実装と一致する")
        void testMethod07() {
            compare(root -> root.each("final", actual.finals(), EarKissDetectorTest::finalCandidate));
        }
    }

    @Nested
    @DisplayName("select()")
    class Select {

        @Test
        @DisplayName("正常系：選び方の表の全件で、大きさの基準・区間・G・門・上限・最終の候補が参照実装と一致する")
        void testMethod01() throws IOException {
            JsonNode table = JsonMapper.shared().readTree(fixture("ear-kiss-selection-cases.json"));
            EarKissModel model = EarKissModel.load();
            assertThat(model.version())
                    .as("選び方の表の期待値の計算に使った重みの版（重みを変えたら、参照実装で表を作り直す）")
                    .isEqualTo(table.required("model").stringValue());
            EarKissDetector detector = new EarKissDetector(model);
            assertSoftly(softly -> {
                for (JsonNode example : table.required("cases").values()) {
                    List<MajorPoint> majors = example.required("majors").values().stream()
                            .map(m -> new MajorPoint(m.required("frame").intValue(), m.required("ear").intValue(),
                                    m.required("p").doubleValue(), m.required("D").doubleValue()))
                            .toList();
                    Selection selection = detector.select(majors, example.required("bandFrames").intValue());
                    Item item = new Item(softly, "cases[" + example.required("name").stringValue() + "]", example);
                    item.real("loudnessRef", selection.loudnessRef());
                    item.each("segments", selection.segments(), EarKissDetectorTest::segment);
                    item.each("final", selection.finals(), EarKissDetectorTest::finalCandidate);
                }
            });
        }
    }

    /** 区間 1 つを、期待値の {@code segments[]} の 1 件と比べる（音の正解データと選び方の表で同じ形）。 */
    private static void segment(Item item, Segment segment) {
        item.integer("startFrame", segment.startFrame());
        item.integer("endFrame", segment.endFrame());
        item.integer("count", segment.count());
        item.integer("bestFrame", segment.bestFrame());
        item.real("bestP", segment.bestP());
        item.bool("enoughEvents", segment.enoughEvents());
        item.real("G", segment.g());
        item.integer("loudestFrame", segment.loudestFrame());
        item.bool("passedGate", segment.passedGate());
        item.bool("kept", segment.kept());
    }

    /** 最終の候補 1 つを、期待値の {@code final[]} の 1 件と比べる（{@code timeSec} は {@code frame / 200} なので比べない）。 */
    private static void finalCandidate(Item item, FinalCandidate candidate) {
        item.integer("frame", candidate.frame());
        item.integer("ear", candidate.ear());
        item.real("p", candidate.score());
        item.real("G", candidate.g());
        item.integer("count", candidate.count());
        item.integer("startFrame", candidate.startFrame());
        item.integer("endFrame", candidate.endFrame());
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

        /** 実数。期待値が {@code null}（大きさの基準が無いとき）なら、実際の値が NaN（無い）であることを確かめる。 */
        void real(String field, double actualValue) {
            JsonNode expectedValue = node.required(field);
            if (expectedValue.isNull()) {
                softly.assertThat(actualValue).as(label(field) + "（無い）").isNaN();
                return;
            }
            softly.assertThat(actualValue).as(label(field))
                    .isCloseTo(expectedValue.doubleValue(), within(TOLERANCE));
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
