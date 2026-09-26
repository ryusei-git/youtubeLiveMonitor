package com.example.monitor.sound;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 耳キスの検出器の重みとパラメーター（線形モデル）。{@code src/main/resources/sound/} の JSON から読む。
 *
 * <p><b>コードに書かず JSON に分けている理由。</b>重みは、利用者の答え（「耳キス」「ちがう」）がたまったら
 * 学び直して版を上げる（Issue #465）。版ごとにファイルを分けておけば、学び直しの差分が重みのファイルだけで済み、
 * 付けた候補がどの版の結果かも {@code version} で記録できる。値は Issue #480 の結論の JSON そのもの
 * （重みは v1 の Issue #466 の JSON から 1 桁も変えていない。小数 4 桁）。丸める前の値を使うと、突き合わせの正解データの
 * logit・p と合わない。
 *
 * <p>特徴の計算の定数（窓の長さ・帯域の境目・切り詰めの範囲など）は JSON に入れない。
 * 版を上げるときに変わるのは重みと {@code candidate}・{@code loudnessGate} だけの見込み（Issue #466・#480）。
 *
 * @param version      版の名前（例 {@code ear-kiss-linear12-v2}）
 * @param features     特徴の順番。{@link EarKissDetector#FEATURES} と同じでなければならない
 * @param mean         特徴ごとの平均（標準化に使う）
 * @param sd           特徴ごとの標準偏差（標準化に使う）
 * @param weight       標準化した特徴ごとの重み
 * @param intercept    切片
 * @param candidate    目立つ候補から最終の候補を選ぶ決まり
 * @param loudnessGate 区間の大きさで最終の候補を絞る門（v2 で足した）
 */
public record EarKissModel(
        String version,
        List<String> features,
        double[] mean,
        double[] sd,
        double[] weight,
        double intercept,
        CandidateRule candidate,
        LoudnessGate loudnessGate) {

    /** 同梱している重みのファイル（クラスパスの中の場所）。 */
    private static final String RESOURCE = "/sound/ear-kiss-linear12-v2.json";

    /**
     * 読んだ値の数がそろっているかを確かめる。ずれたまま計算すると、誤った p を黙って出すため。
     */
    public EarKissModel {
        if (version == null || features == null || mean == null || sd == null || weight == null || candidate == null
                || loudnessGate == null) {
            throw new IllegalArgumentException("重みのファイルに足りない項目があります");
        }
        int n = features.size();
        if (mean.length != n || sd.length != n || weight.length != n) {
            throw new IllegalArgumentException("重みのファイルの特徴の数がそろっていません: " + version);
        }
    }

    /**
     * 同梱している重みを読む。
     *
     * @return 重みとパラメーター
     */
    public static EarKissModel load() {
        try (InputStream in = EarKissModel.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("重みのファイルが見つかりません: " + RESOURCE);
            }
            return JsonMapper.shared().readValue(in, EarKissModel.class);
        } catch (IOException e) {
            throw new UncheckedIOException("重みのファイルを読めませんでした: " + RESOURCE, e);
        }
    }

    /**
     * 12 特徴から logit を求める。
     *
     * <p>標準化して重みを掛け、JSON の順に 1 つずつ足してから切片を足す。参照実装（Issue #466）と足す順番を
     * そろえて、丸めの差を出さないため。
     *
     * @param x 特徴（{@code features} の順）
     * @return logit
     */
    public double logit(double[] x) {
        double sum = 0.0;
        for (int i = 0; i < weight.length; i++) {
            sum = sum + weight[i] * ((x[i] - mean[i]) / sd[i]);
        }
        return sum + intercept;
    }

    /**
     * 目立つ候補から最終の候補を選ぶ決まり（Issue #466 の結論の「1.」。上限の並べ方は #480 で G の順にした）。
     *
     * <p>{@code rankBy} は計算の手順だが、v1（p の順）と v2（G の順）で違う所なので、版の中身として JSON に残す
     * （どの版の候補がどちらの順で選ばれたかを、JSON だけで読めるようにするため。#480）。
     *
     * @param threshold     この p 以上の目立つ候補だけをつなぐ
     * @param joinGapFrames 直前の（p がしきい値以上の）目立つ候補からのフレームの差がこれ以下ならつなぐ
     * @param minEvents     この数以上つながった区間だけを候補にする
     * @param maxPerHour    1 本の録画の候補の数の上限（1 時間あたり。切り上げ）
     * @param rankBy        上限を超えたときに残す区間を並べる基準。{@code "G"}（G の高い順）だけを受け付ける
     */
    public record CandidateRule(double threshold, int joinGapFrames, int minEvents, int maxPerHour, String rankBy) {

        /**
         * 並べる基準を確かめる。検出器は G の順しか持たない（p の順の v1 を動かし続ける予定は無い）ので、
         * ほかの値のまま読むと、JSON に書いた並べ方と違う並べ方で黙って選ぶことになるため、読み込みで止める。
         */
        public CandidateRule {
            if (!"G".equals(rankBy)) {
                throw new IllegalArgumentException("上限を並べる基準は \"G\" だけに対応しています: " + rankBy);
            }
        }
    }

    /**
     * 区間の大きさで最終の候補を絞る「大きさの門」（Issue #479 で見つけ、#480 で仕様を決めた v2 の門）。
     *
     * @param windowFrames        区間の代表から前後これだけのフレーム（両端を含む）の目立つ候補の D の最大を、区間の大きさにする
     * @param referencePercentile 大きさの基準 ref にする、録画の全目立つ候補の D のパーセント点
     * @param minDb               G（区間の大きさ − ref。dB）がこの値以上の区間だけを残す（ちょうどは通す）
     */
    public record LoudnessGate(int windowFrames, double referencePercentile, double minDb) {
    }
}
