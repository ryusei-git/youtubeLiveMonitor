package com.example.monitor.sound;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 録画の音声から「耳キスの候補」（時刻と点数）を探す。版 {@code ear-kiss-linear12-v1} の検出器。
 *
 * <h2>方式の出どころ</h2>
 * 方式は Issue #460 の調査で決めた「12 特徴の線形モデル」、計算の細部は Issue #466 の結論の
 * 「4. 食い違い対策の仕様」がそのまま正本（#460 の「4.」を置き換えたもの）。丸め・同じ値のときの選び方・
 * 中央値とパーセンタイルの取り方・範囲の端・足す順番まで、参照実装（Python。#466 のコメントに全文がある）と
 * 1 対 1 にしてある。仕様の読み方で迷ったら参照実装の計算を正とする。
 * 突き合わせの許してよい誤差は、整数（フレーム・耳・数）が完全一致、実数が絶対誤差 1e-9。
 * 1e-9 は、double で素直に書けば通り、float が混ざると落ちる大きさとして決めたもの（この実装の正解データでの差は
 * 最大 4e-14。録画 #23・#39 の全体でも、候補と目立つ候補のフレーム・耳が参照実装と全部同じで、p の差は 2e-14 未満）。
 *
 * <h2>本番に Python を足さない理由</h2>
 * 試作と参照実装は Python（numpy）だが、本番は Java だけで動かす（Issue #465 の決まり）。Python を足すと、
 * 端末に処理系とライブラリを入れて版を合わせ続ける手間と、別プロセスの起動・失敗の扱いが増える。
 * 使う計算（FFT・ソート・平方根）は Java の標準ライブラリで書ける量で、依存も増やさずに済む。
 *
 * <h2>流しながら処理する理由（メモリ）</h2>
 * 2 時間の録画は 32kHz・2ch で約 4.6 億サンプル（double なら 3.7GB）になり、全体をメモリには載せられない。
 * 入力は読みながら輪（リングバッファー）に流し、5ms ごとの帯域フレームと 1ms ごとの細かいフレームを順に作る。
 * 帯域のエネルギー（12 帯域 × 左右）も録画全体ぶんは持たない（2 時間で約 280MB になり、本番のヒープ 1GB を圧迫する）。
 * <ul>
 *   <li>フレームごとの値で全体を持つのは、D（大きい方の耳の 1〜16kHz の dB）と耳の 2 つだけ（1 時間で約 6.5MB）。
 *       背景・候補・目立つ候補は、読み終えてから、この 2 つで参照実装と同じ手順で決める。</li>
 *   <li>帯域のエネルギーが要る特徴（山の前後 −80〜+23 フレーム）と細かい包絡の特徴は、読みながら、
 *       「目立つ候補になりうる山」ごとに先に計算して取っておく。目立つ候補かどうかは読み終えないと決まらないが、
 *       目立つ候補は必ず「立ち上がり（D が 3 フレームで 6dB 以上上がる）の直後 5 フレームで D が最大の点」なので、
 *       その点すべてを先に計算しておけば取りこぼさない（背景との差の条件は後で絞る）。</li>
 *   <li>{@code ioi_cv}（前後 3 秒の候補の間隔のばらつき）だけは候補の並びが要るので、読み終えてから計算する。</li>
 * </ul>
 * 持つ量は録画の長さに比例する（D と耳が 1 時間で約 6.5MB、山ごとの特徴が 1 時間に 4〜6 万個で 10〜15MB、結果の一覧）。
 * 実測（GC 後のヒープの最大）は #39（2 時間 5 分）で 50MB、#38（4 時間。山が多い）で 117MB。
 *
 * <p>このクラスは Spring に依存しない。ffmpeg を通さずに s16le のバイト列を渡せば、テストから直接呼べる
 * （Issue #473 の突き合わせのテストが使う）。録画ファイルから読むときは {@link PcmDecoder} を通す。
 * 1 回の {@link #detect} の中の状態は、その呼び出しの中だけで持つ。
 */
public final class EarKissDetector {

    /** 入力のサンプリング周波数（Hz）。 */
    public static final int SAMPLE_RATE = 32000;

    /** 入力のチャンネル数（左・右）。 */
    public static final int CHANNELS = 2;

    /** 特徴の順番。重みのファイルの {@code features} と同じでなければならない。 */
    public static final List<String> FEATURES = List.of(
            "s3", "fill", "s2", "mf_after", "rise", "ioi_cv", "nsub", "j0", "dec60", "j3", "j2", "ild");

    /** 帯域フレームの窓（16ms）と送り（5ms）。 */
    private static final int BAND_WINDOW = 512;
    private static final int BAND_HOP = 160;

    /** 細かいフレームの窓（2ms）と送り（1ms）。帯域フレーム 1 つは細かいフレーム 5 つ分。 */
    private static final int FINE_WINDOW = 64;
    private static final int FINE_HOP = 32;

    /** 帯域の数と、帯域ごとの最初のビン（512 点 FFT のビン k は 62.5k Hz。境目ちょうどは上の帯域。最後はビン 256 まで）。 */
    private static final int BANDS = 12;
    private static final int[] BAND_FIRST_BIN = {0, 3, 5, 10, 16, 24, 36, 48, 64, 88, 120, 160};
    private static final int[] BAND_OF_BIN = bandOfBin();

    /** 1 時間の帯域フレームの数（200 フレーム/秒）。最終の候補の上限に使う。 */
    private static final long FRAMES_PER_HOUR = 200L * 3600;

    /** 最初と最後の 0.5 秒（100 フレーム）の山は候補にしない。 */
    private static final int EDGE_FRAMES = 100;

    /** 背景を求めるブロックの長さ（0.5 秒）。 */
    private static final int BLOCK_FRAMES = 100;

    /** dB に直すときに足す値。0 でも −120dB の床になり、log の −∞ を避ける。 */
    private static final double DB_FLOOR = 1e-12;

    /**
     * 山の局所の特徴を、山から何フレーム後に計算するか。帯域は山の +23、細かい包絡は細かいフレームの
     * {@code 5p + 161}（帯域フレームの +29.4 相当）まで要るので、+30 で全部そろう。
     */
    private static final int LOCAL_DELAY = 30;

    /** 輪の長さ。どれも、局所の特徴の計算に要る範囲（帯域 111 フレーム・細かい 197 フレーム・サンプル 512 個）より長い 2 のべき乗。 */
    private static final int SAMPLE_RING = 1024;
    private static final int FRAME_RING = 256;
    private static final int FINE_RING = 512;

    /** 読み込みの単位（バイト）。1 サンプル（左右）= 4 バイトの倍数にする。 */
    private static final int READ_BYTES = 1 << 16;

    private static final double[] BAND_HANN = symmetricHann(BAND_WINDOW);
    private static final double[] FINE_HANN = symmetricHann(FINE_WINDOW);

    private final EarKissModel model;

    /**
     * @param model 重みとパラメーター。特徴の順番が {@link #FEATURES} と違えば使えない
     */
    public EarKissDetector(EarKissModel model) {
        if (!FEATURES.equals(model.features())) {
            throw new IllegalArgumentException("重みのファイルの特徴の順番が検出器と違います: " + model.version());
        }
        this.model = model;
    }

    /**
     * s16le・2ch（左右交互）・32kHz の PCM を読み切って、耳キスの候補を探す。
     *
     * <p>最後の、4 バイトに満たない端数は捨てる（ffmpeg の出力なら起きない）。
     *
     * @param pcm PCM のバイト列。閉じるのは呼び出し側
     * @return 最終の候補と、突き合わせ用の途中の値
     * @throws IOException 読み込みに失敗した場合（{@link PcmDecoder} なら ffmpeg の失敗も含む）
     */
    public Result detect(InputStream pcm) throws IOException {
        Analysis analysis = new Analysis();
        byte[] buffer = new byte[READ_BYTES];
        int read;
        while ((read = pcm.readNBytes(buffer, 0, buffer.length)) > 0) {
            int whole = read - read % 4;
            for (int o = 0; o < whole; o += 4) {
                short left = (short) ((buffer[o] & 0xff) | (buffer[o + 1] << 8));
                short right = (short) ((buffer[o + 2] & 0xff) | (buffer[o + 3] << 8));
                // 32767 ではなく 32768 で割る（仕様。2 のべき乗なので割り算で丸めが起きない）
                analysis.addSample(left / 32768.0, right / 32768.0);
            }
        }
        return analysis.finish();
    }

    /**
     * 検出の結果。フレームの番号はどれも入力の先頭から数える（帯域フレーム p は 5ms ごと、細かいフレーム q は 1ms ごと）。
     * 耳は 0 = 左、1 = 右。形は Issue #466 の正解データ（{@code ear-kiss-fixture-expected.json}）に合わせてある。
     *
     * @param bandFrames 帯域フレームの数 T
     * @param fineFrames 細かいフレームの数 Q
     * @param bgBlocks   100 フレームごとのブロックの背景。ブロック j がフレーム 100j〜100j+99 の背景
     * @param onsets     立ち上がりの条件を満たしたフレーム（つなぐ前）
     * @param candidates 候補（40ms 以内をつないで、最初と最後の 0.5 秒を除いた後）。フレームの小さい順
     * @param majors     目立つ候補。フレームの小さい順
     * @param segments   p がしきい値以上の目立つ候補をつないだ区間すべて
     * @param finals     最終の候補（時刻順）
     */
    public record Result(
            int bandFrames,
            int fineFrames,
            double[] bgBlocks,
            int[] onsets,
            List<Candidate> candidates,
            List<Major> majors,
            List<Segment> segments,
            List<FinalCandidate> finals) {
    }

    /**
     * 候補（山）。
     *
     * @param frame 山の帯域フレーム
     * @param d     山の D（大きい方の耳の 1〜16kHz の dB）
     * @param bg    山の背景
     */
    public record Candidate(int frame, double d, double bg) {
    }

    /**
     * 目立つ候補。
     *
     * @param frame    山の帯域フレーム
     * @param ear      大きい方の耳
     * @param features 12 特徴（{@link EarKissDetector#FEATURES} の順。切り詰めた後の値）
     * @param logit    線形モデルの出力
     * @param p        {@code logit} の確率
     * @param debug    確かめ用の途中の値
     */
    public record Major(int frame, int ear, double[] features, double logit, double p, MajorDebug debug) {
    }

    /**
     * 目立つ候補の途中の値（突き合わせで食い違ったときの手がかり）。
     *
     * @param hfL        山のフレームの左の 1〜16kHz の dB
     * @param hfR        山のフレームの右の 1〜16kHz の dB
     * @param bg         山のフレームの背景
     * @param fineQ0     {@code 5 × frame + 8}
     * @param finePeak   大きい方の耳の細かい包絡の、{@code fineQ0−10}〜{@code fineQ0+34} での最大の位置（同じ値なら先）
     * @param fineTop    その位置の細かい包絡
     * @param burstFirst {@code finePeak−30}〜{@code finePeak+119} で {@code fineTop−12} 以上だった最初の細かいフレーム
     * @param burstLast  同じく最後の細かいフレーム
     * @param subPeaks   {@code nsub} を数えるのに使った小さな山の細かいフレーム
     */
    public record MajorDebug(double hfL, double hfR, double bg, int fineQ0, int finePeak, int fineTop,
                             int burstFirst, int burstLast, int[] subPeaks) {
    }

    /**
     * p がしきい値以上の目立つ候補をつないだ区間。
     *
     * @param startFrame   区間の最初の目立つ候補のフレーム
     * @param endFrame     区間の最後の目立つ候補のフレーム
     * @param count        つながった数
     * @param bestFrame    p が最大の目立つ候補のフレーム（同じ値なら先）
     * @param bestP        その p
     * @param enoughEvents つながった数が足りる（最終の候補になりうる）
     * @param kept         上限で外されずに最終の候補に残った
     */
    public record Segment(int startFrame, int endFrame, int count, int bestFrame, double bestP,
                          boolean enoughEvents, boolean kept) {
    }

    /**
     * 最終の候補。区間の代表（p が最大の目立つ候補）の時点。
     *
     * @param frame      代表の帯域フレーム
     * @param ear        代表の大きい方の耳
     * @param score      代表の p
     * @param count      区間のつながった数
     * @param startFrame 区間の最初のフレーム
     * @param endFrame   区間の最後のフレーム
     */
    public record FinalCandidate(int frame, int ear, double score, int count, int startFrame, int endFrame) {

        /**
         * @return 入力の先頭からの位置（ミリ秒）。帯域フレームは 5ms ごと
         */
        public long positionMs() {
            return frame * 5L;
        }
    }

    /** 1 回の検出の途中の状態。 */
    private final class Analysis {

        private final double[][] samples = new double[CHANNELS][SAMPLE_RING];
        private long sampleCount;
        private long nextFineEnd = FINE_WINDOW;
        private long nextBandEnd = BAND_WINDOW;

        private final Fft bandFft = new Fft(BAND_WINDOW);
        private final Fft fineFft = new Fft(FINE_WINDOW);
        private final double[] bandInput = new double[BAND_WINDOW];
        private final double[] bandPower = new double[BAND_WINDOW / 2 + 1];
        private final double[] fineInput = new double[FINE_WINDOW];
        private final double[] finePower = new double[FINE_WINDOW / 2 + 1];

        /** 帯域のエネルギー {@code [耳][フレーム % FRAME_RING][帯域]} と 1〜16kHz の dB {@code [耳][フレーム % FRAME_RING]}。 */
        private final double[][][] bandRing = new double[CHANNELS][FRAME_RING][BANDS];
        private final double[][] hfRing = new double[CHANNELS][FRAME_RING];

        /** 細かい包絡 E {@code [耳][細かいフレーム % FINE_RING]}（dB を偶数への丸めで整数にしたもの）。 */
        private final short[][] fineRing = new short[CHANNELS][FINE_RING];
        private int fineFrames;

        // ponytail: 背景・候補・目立つ候補を読み終えてから決めるので、D と山ごとの特徴が録画の長さに比例して残る
        // （1 時間あたり 20〜30MB）。10 時間を超える録画を扱うなら、この判定も流しながら行う（遅れは最大 900 フレームほど）と、
        // 持つ量が結果の一覧だけになる
        /** 全体で持つ D と耳。長さは足りなくなったら倍にする（使うのは先頭の bandFrames 個）。 */
        private double[] d = new double[1 << 16];
        private byte[] ears = new byte[1 << 16];
        private int bandFrames;

        /** 局所の特徴をまだ計算していない「目立つ候補になりうる山」（小さい順）と、計算した特徴。 */
        private final ArrayDeque<Integer> pendingPeaks = new ArrayDeque<>();
        private int lastPendingPeak = -1;
        private final Map<Integer, Local> locals = new HashMap<>();

        void addSample(double left, double right) {
            int slot = (int) (sampleCount & (SAMPLE_RING - 1));
            samples[0][slot] = left;
            samples[1][slot] = right;
            sampleCount++;
            if (sampleCount == nextFineEnd) {
                fineFrame();
                nextFineEnd += FINE_HOP;
            }
            if (sampleCount == nextBandEnd) {
                bandFrame();
                nextBandEnd += BAND_HOP;
            }
        }

        /** 細かいフレーム q（サンプル 32q〜32q+63）の、1500Hz 以上（ビン 3〜32）のパワーの dB を整数にする。 */
        private void fineFrame() {
            long start = sampleCount - FINE_WINDOW;
            int slot = fineFrames & (FINE_RING - 1);
            for (int c = 0; c < CHANNELS; c++) {
                for (int i = 0; i < FINE_WINDOW; i++) {
                    fineInput[i] = samples[c][(int) ((start + i) & (SAMPLE_RING - 1))] * FINE_HANN[i];
                }
                fineFft.power(fineInput, finePower);
                double sum = finePower[3];
                for (int k = 4; k <= FINE_WINDOW / 2; k++) {
                    sum += finePower[k];
                }
                // (int) のキャストだけにすると負の値が 0 の側へ切り捨てられる（#23 では E の半分が変わる）。
                // Math.round とも .5 ちょうどで違う。numpy の round と同じ「偶数への丸め」にする
                fineRing[c][slot] = (short) Math.rint(db(sum));
            }
            fineFrames++;
        }

        /** 帯域フレーム p（サンプル 160p〜160p+511）の、帯域のエネルギー・1〜16kHz の dB・D・耳を求める。 */
        private void bandFrame() {
            long start = sampleCount - BAND_WINDOW;
            int p = bandFrames;
            int slot = p & (FRAME_RING - 1);
            for (int c = 0; c < CHANNELS; c++) {
                for (int i = 0; i < BAND_WINDOW; i++) {
                    bandInput[i] = samples[c][(int) ((start + i) & (SAMPLE_RING - 1))] * BAND_HANN[i];
                }
                bandFft.power(bandInput, bandPower);
                double[] band = bandRing[c][slot];
                Arrays.fill(band, 0.0);
                for (int k = 0; k <= BAND_WINDOW / 2; k++) {
                    band[BAND_OF_BIN[k]] += bandPower[k];
                }
                double high = band[4];
                for (int b = 5; b < BANDS; b++) {
                    high += band[b];
                }
                hfRing[c][slot] = db(high);
            }
            if (p == d.length) {
                d = Arrays.copyOf(d, p * 2);
                ears = Arrays.copyOf(ears, p * 2);
            }
            double left = hfRing[0][slot];
            double right = hfRing[1][slot];
            d[p] = Math.max(left, right);
            ears[p] = (byte) (right > left ? 1 : 0);   // 同じ値なら左
            bandFrames++;

            addPeakCandidate(p - 4);
            computeReadyLocals(p);
        }

        /**
         * フレーム t が立ち上がり（D が 3 フレームで 6dB 以上上がる）なら、t〜t+4 で D が最大の点（同じ値なら先）を
         * 「目立つ候補になりうる山」に加える。背景との差の条件は、背景が読み終えないと決まらないのでここでは見ない。
         * t が増えると山の位置は減らないので、重なりは直前と比べるだけで除ける。
         */
        private void addPeakCandidate(int t) {
            if (t < 3 || d[t] - d[t - 3] < 6.0) {
                return;
            }
            int peak = t;
            for (int j = t + 1; j <= t + 4; j++) {
                if (d[j] > d[peak]) {
                    peak = j;
                }
            }
            if (peak != lastPendingPeak) {
                pendingPeaks.addLast(peak);
                lastPendingPeak = peak;
            }
        }

        /** 帯域フレーム f まで読んだ時点で、局所の特徴の材料がそろった山の特徴を計算する。 */
        private void computeReadyLocals(int f) {
            while (!pendingPeaks.isEmpty() && pendingPeaks.peekFirst() + LOCAL_DELAY <= f) {
                int peak = pendingPeaks.pollFirst();
                // 最初の 0.5 秒の山は候補にならない（範囲の外の輪を読まないためにも、ここで除く）
                if (peak >= EDGE_FRAMES) {
                    locals.put(peak, local(peak));
                }
            }
        }

        /** 山 p の、輪にある材料だけで決まる特徴（{@code ioi_cv} 以外の 11 個）と途中の値。 */
        private Local local(int p) {
            int c = ears[p];
            int o = 1 - c;
            double[] hf = hfRing[c];
            double peakHf = hf[p & (FRAME_RING - 1)];
            double[] x = new double[FEATURES.size()];
            x[Feature.RISE] = peakHf - hf[(p - 3) & (FRAME_RING - 1)];
            x[Feature.DEC60] = peakHf - hf[(p + 12) & (FRAME_RING - 1)];
            x[Feature.ILD] = clamp(peakHf - hfRing[o][p & (FRAME_RING - 1)], -5.0, 30.0);

            // 帯域ごとの背景 = フレーム p−80〜p−11 の 70 個の中央値（偶数個なので真ん中 2 つの平均）
            double[][] bands = bandRing[c];
            double[] bgB = new double[BANDS];
            double[] column = new double[70];
            for (int b = 0; b < BANDS; b++) {
                for (int i = 0; i < column.length; i++) {
                    column[i] = bands[(p - 80 + i) & (FRAME_RING - 1)][b];
                }
                Arrays.sort(column);
                bgB[b] = (column[34] + column[35]) / 2;
            }
            double[] pk = bands[p & (FRAME_RING - 1)];
            double[] ex = new double[BANDS];
            for (int b = 0; b < BANDS; b++) {
                ex[b] = Math.max(pk[b] - bgB[b], 0.0);
            }
            double total = ex[1];
            for (int b = 2; b < BANDS; b++) {
                total += ex[b];
            }
            x[Feature.S2] = db(ex[5] + ex[6]) - db(total);
            x[Feature.S3] = db(ex[7] + ex[8]) - db(total);
            x[Feature.J0] = clamp(db(pk[1] + pk[2]) - db(bgB[1] + bgB[2]), -10.0, 40.0);
            x[Feature.J2] = clamp(db(pk[5] + pk[6]) - db(bgB[5] + bgB[6]), -10.0, 40.0);
            x[Feature.J3] = clamp(db(pk[7] + pk[8]) - db(bgB[7] + bgB[8]), -10.0, 40.0);

            // 山の 8〜23 フレーム後（40〜115ms）の、帯域 3〜6（600〜3000Hz）の平均
            double after = 0.0;
            for (int b = 3; b <= 6; b++) {
                double sum = bands[(p + 8) & (FRAME_RING - 1)][b];
                for (int f = p + 9; f <= p + 23; f++) {
                    sum += bands[f & (FRAME_RING - 1)][b];
                }
                after += sum / 16;
            }
            x[Feature.MF_AFTER] = clamp(db(after) - db(pk[3] + pk[4] + pk[5] + pk[6]), -40.0, 5.0);

            // 細かい包絡の破裂の形（fill・nsub）
            short[] e = fineRing[c];
            int q0 = 5 * p + 8;
            int finePeak = q0 - 10;
            for (int q = q0 - 9; q <= q0 + 34; q++) {
                if (e[q & (FINE_RING - 1)] > e[finePeak & (FINE_RING - 1)]) {
                    finePeak = q;   // 同じ値なら先（E は整数なので同じ値がよく並ぶ）
                }
            }
            int top = e[finePeak & (FINE_RING - 1)];
            int level = top - 12;
            int[] w = new int[150];   // 細かいフレーム finePeak−30〜finePeak+119
            int first = -1;
            int last = -1;
            int above = 0;
            for (int i = 0; i < w.length; i++) {
                w[i] = e[(finePeak - 30 + i) & (FINE_RING - 1)];
                if (w[i] >= level) {
                    if (first < 0) {
                        first = i;
                    }
                    last = i;
                    above++;
                }
            }
            int span = last - first + 1;
            x[Feature.FILL] = (double) above / span;
            List<Integer> subPeaks = new ArrayList<>();
            int nsub = 1;
            int previous = -1;
            for (int j = 2; j <= span - 3; j++) {
                int u = w[first + j];
                if (u >= level && u == max(w, first + j - 2, first + j + 2)) {
                    if (previous >= 0 && j - previous >= 2) {
                        nsub++;
                    }
                    previous = j;
                    subPeaks.add(finePeak - 30 + first + j);
                }
            }
            x[Feature.NSUB] = nsub;
            return new Local(x, hfRing[0][p & (FRAME_RING - 1)], hfRing[1][p & (FRAME_RING - 1)], q0, finePeak, top,
                    finePeak - 30 + first, finePeak - 30 + last, subPeaks.stream().mapToInt(Integer::intValue).toArray());
        }

        /** 読み終えた後、背景・候補・目立つ候補・区間・最終の候補を決める。 */
        Result finish() {
            int t = bandFrames;
            if (t < 2 * EDGE_FRAMES + 1) {
                // 最初と最後の 0.5 秒を除くと何も残らない
                return new Result(t, fineFrames, new double[0], new int[0], List.of(), List.of(), List.of(), List.of());
            }
            double[] background = background(d, t);

            List<Integer> onsets = new ArrayList<>();
            List<Integer> peaks = new ArrayList<>();
            for (int x = 3; x < t; x++) {
                if (d[x] - d[x - 3] < 6.0 || d[x] - background[x / BLOCK_FRAMES] < 10.0) {
                    continue;
                }
                onsets.add(x);
                int peak = x;
                for (int j = x + 1; j <= Math.min(x + 4, t - 1); j++) {
                    if (d[j] > d[peak]) {
                        peak = j;
                    }
                }
                // 40ms（8 フレーム）以内に続いた山は、後ろが大きいときだけ置き換える
                if (!peaks.isEmpty() && peak - peaks.getLast() < 8) {
                    if (d[peak] > d[peaks.getLast()]) {
                        peaks.set(peaks.size() - 1, peak);
                    }
                    continue;
                }
                peaks.add(peak);
            }
            // つないでから端の 0.5 秒を除く（先に除くと、端の山に吸われるはずの山が残る）
            int[] k = peaks.stream().mapToInt(Integer::intValue)
                    .filter(p -> p >= EDGE_FRAMES && p <= t - EDGE_FRAMES - 1).toArray();

            List<Candidate> candidates = new ArrayList<>(k.length);
            List<Major> majors = new ArrayList<>();
            for (int p : k) {
                double bg = background[p / BLOCK_FRAMES];
                candidates.add(new Candidate(p, d[p], bg));
                if (d[p] - bg < 20.0 || !isHighestAround(k, p)) {
                    continue;
                }
                majors.add(major(k, p, bg));
            }

            List<Segment> segments = new ArrayList<>();
            List<FinalCandidate> finals = new ArrayList<>();
            choose(majors, t, segments, finals);
            return new Result(t, fineFrames, background, onsets.stream().mapToInt(Integer::intValue).toArray(),
                    candidates, majors, segments, finals);
        }

        /** ±60 フレーム（0.3 秒。端を含む）の候補の中で、D がいちばん高い（同じ高さなら両方残す）。 */
        private boolean isHighestAround(int[] k, int p) {
            for (int j = lowerBound(k, p - 60); j < k.length && k[j] <= p + 60; j++) {
                if (d[k[j]] > d[p]) {
                    return false;
                }
            }
            return true;
        }

        /** 目立つ候補 p の 12 特徴・logit・p を求める。 */
        private Major major(int[] k, int p, double bg) {
            Local local = locals.get(p);
            if (local == null) {
                // 目立つ候補は必ず読みながら計算した山に入っている（クラスの JavaDoc）。ここに来たら作りの誤り
                throw new IllegalStateException("目立つ候補の特徴を計算していません: frame=" + p);
            }
            double[] x = local.features().clone();
            x[Feature.IOI_CV] = ioiCv(k, p);
            double logit = model.logit(x);
            double probability = 1.0 / (1.0 + Math.exp(-logit));
            MajorDebug debug = new MajorDebug(local.hfL(), local.hfR(), bg, local.fineQ0(), local.finePeak(),
                    local.fineTop(), local.burstFirst(), local.burstLast(), local.subPeaks());
            return new Major(p, ears[p], x, logit, probability, debug);
        }

        /**
         * 目立つ候補をつないで区間を作り、最終の候補を選ぶ（Issue #466 の結論の「1.」）。
         * p がしきい値以上の目立つ候補を、直前の（しきい値以上の）目立つ候補から joinGapFrames 以内ならつなぐ
         * （区間の先頭からではなく直前から測る）。minEvents 個以上の区間ごとに p 最大（同じ値なら先）を代表にし、
         * 代表が 1 時間あたり maxPerHour（切り上げ）を超えたら、p の高い順（同じ値なら先）に残して時刻順に出す。
         * 単独の耳キスを出さないのは、聞いて答える数を抑えるため（単独まで出すと候補が約 3 倍になる。#466）。
         */
        private void choose(List<Major> majors, int t, List<Segment> segments, List<FinalCandidate> finals) {
            EarKissModel.CandidateRule rule = model.candidate();
            List<List<Major>> groups = new ArrayList<>();
            for (Major m : majors) {
                if (m.p() < rule.threshold()) {
                    continue;
                }
                if (!groups.isEmpty() && m.frame() - groups.getLast().getLast().frame() <= rule.joinGapFrames()) {
                    groups.getLast().add(m);
                } else {
                    groups.add(new ArrayList<>(List.of(m)));
                }
            }
            List<Major> bests = new ArrayList<>(groups.size());
            List<Integer> ranked = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                List<Major> group = groups.get(i);
                Major best = group.getFirst();
                for (Major m : group) {
                    if (m.p() > best.p()) {
                        best = m;
                    }
                }
                bests.add(best);
                if (group.size() >= rule.minEvents()) {
                    ranked.add(i);
                }
            }
            long limit = (rule.maxPerHour() * (long) t + FRAMES_PER_HOUR - 1) / FRAMES_PER_HOUR;
            ranked.sort((a, b) -> bests.get(a).p() != bests.get(b).p()
                    ? Double.compare(bests.get(b).p(), bests.get(a).p())
                    : Integer.compare(bests.get(a).frame(), bests.get(b).frame()));
            boolean[] kept = new boolean[groups.size()];
            for (int i = 0; i < ranked.size() && i < limit; i++) {
                kept[ranked.get(i)] = true;
            }
            for (int i = 0; i < groups.size(); i++) {
                List<Major> group = groups.get(i);
                Major best = bests.get(i);
                int start = group.getFirst().frame();
                int end = group.getLast().frame();
                segments.add(new Segment(start, end, group.size(), best.frame(), best.p(),
                        group.size() >= rule.minEvents(), kept[i]));
                if (kept[i]) {
                    finals.add(new FinalCandidate(best.frame(), best.ear(), best.p(), group.size(), start, end));
                }
            }
        }
    }

    /**
     * 山の局所の特徴（{@code ioi_cv} の欄だけ空）と、突き合わせ用の途中の値。
     */
    private record Local(double[] features, double hfL, double hfR, int fineQ0, int finePeak, int fineTop,
                         int burstFirst, int burstLast, int[] subPeaks) {
    }

    /** {@link EarKissDetector#FEATURES} の中の位置。 */
    private static final class Feature {
        static final int S3 = FEATURES.indexOf("s3");
        static final int FILL = FEATURES.indexOf("fill");
        static final int S2 = FEATURES.indexOf("s2");
        static final int MF_AFTER = FEATURES.indexOf("mf_after");
        static final int RISE = FEATURES.indexOf("rise");
        static final int IOI_CV = FEATURES.indexOf("ioi_cv");
        static final int NSUB = FEATURES.indexOf("nsub");
        static final int J0 = FEATURES.indexOf("j0");
        static final int DEC60 = FEATURES.indexOf("dec60");
        static final int J3 = FEATURES.indexOf("j3");
        static final int J2 = FEATURES.indexOf("j2");
        static final int ILD = FEATURES.indexOf("ild");

        private Feature() {
        }
    }

    /**
     * 背景。0.5 秒（100 フレーム）ごとのブロックの D の 20 パーセンタイルの、前後 2 ブロックを含む中央値。
     *
     * <p>ブロックの数は {@code (T + 99) / 100}。最後のブロックが足りなければ {@code D[T−1]} で埋める。
     * #460 の試作は {@code T / 100 + 1} 個にしていたが（T が 100 の倍数のとき 1 つ余分にできる）、真似しない。
     * パーセンタイルは numpy の既定（線形補間）と同じ {@code v[19] + 0.8 × (v[20] − v[19])}。
     *
     * @return ブロックごとの背景
     */
    private static double[] background(double[] d, int t) {
        int blocks = (t + BLOCK_FRAMES - 1) / BLOCK_FRAMES;
        double[] p20 = new double[blocks];
        double[] v = new double[BLOCK_FRAMES];
        for (int j = 0; j < blocks; j++) {
            for (int i = 0; i < BLOCK_FRAMES; i++) {
                v[i] = d[Math.min(j * BLOCK_FRAMES + i, t - 1)];
            }
            Arrays.sort(v);
            p20[j] = v[19] + 0.8 * (v[20] - v[19]);
        }
        double[] median = new double[blocks];
        for (int j = 0; j < blocks; j++) {
            // 端では 3〜4 個。偶数個なら真ん中 2 つの平均
            double[] around = Arrays.copyOfRange(p20, Math.max(0, j - 2), Math.min(blocks - 1, j + 2) + 1);
            Arrays.sort(around);
            int n = around.length;
            median[j] = n % 2 == 1 ? around[n / 2] : (around[n / 2 - 1] + around[n / 2]) / 2;
        }
        return median;
    }

    /**
     * 前後 3 秒（600 フレーム。端を含む）の候補の間隔のばらつき（標準偏差 ÷ 平均）。
     * 間隔が 3 つ未満なら 1.0。標準偏差は n で割る（n−1 ではない）。
     */
    private static double ioiCv(int[] k, int p) {
        int from = lowerBound(k, p - 600);
        int to = lowerBound(k, p + 601);
        int n = to - from - 1;
        if (n < 3) {
            return 1.0;
        }
        double sum = k[from + 1] - k[from];
        for (int j = from + 2; j < to; j++) {
            sum += k[j] - k[j - 1];
        }
        double mean = sum / n;
        double first = (k[from + 1] - k[from]) - mean;
        double squares = first * first;
        for (int j = from + 2; j < to; j++) {
            double deviation = (k[j] - k[j - 1]) - mean;
            squares += deviation * deviation;
        }
        return Math.sqrt(squares / n) / mean;
    }

    /** 昇順の配列で、key 以上の最初の位置。 */
    private static int lowerBound(int[] sorted, int key) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] < key) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static int max(int[] values, int from, int to) {
        int max = values[from];
        for (int i = from + 1; i <= to; i++) {
            max = Math.max(max, values[i]);
        }
        return max;
    }

    private static double db(double power) {
        return 10 * Math.log10(power + DB_FLOOR);
    }

    private static double clamp(double value, double low, double high) {
        return Math.min(Math.max(value, low), high);
    }

    /** 対称のハン窓（N−1 で割る。周期の窓とは値が違う）。 */
    private static double[] symmetricHann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
        }
        return w;
    }

    private static int[] bandOfBin() {
        int[] bandOf = new int[BAND_WINDOW / 2 + 1];
        for (int k = 0; k < bandOf.length; k++) {
            int b = 0;
            while (b + 1 < BANDS && k >= BAND_FIRST_BIN[b + 1]) {
                b++;
            }
            bandOf[k] = b;
        }
        return bandOf;
    }
}
