package com.example.monitor.sound;

/**
 * 基数 2 の FFT で、実数の列のパワースペクトル（片側）を求める。
 *
 * <p><b>ライブラリを足さずに自前で書いている。</b>耳キスの検出器（{@link EarKissDetector}）が使うのは
 * 512 点と 64 点の 2 種類だけで、基数 2 の素直な書き方で足りる。依存を増やさない決まり（Issue #465）に従う。
 *
 * <p>回転因子は漸化式で掛け合わせず、はじめに {@code cos}・{@code sin} の表を作っておく。
 * 漸化式は段が深いほど丸めの誤差が積み重なるが、表なら各値の誤差が 1 回の丸めで済み、速くもなる。
 * 参照実装（Python の numpy）との差は、突き合わせの正解データで最大 4e-14 で、許してよい誤差（1e-9。Issue #466）より十分小さい。
 *
 * <p>作業用の配列を持つので、1 つのインスタンスを複数のスレッドから同時に使わない
 * （検出器は 1 回の検出ごとに作る）。
 */
public final class Fft {

    private final int size;
    private final int[] bitReversed;
    private final double[] cos;
    private final double[] sin;
    private final double[] re;
    private final double[] im;

    /**
     * @param size 点の数（2 のべき乗）
     */
    public Fft(int size) {
        if (size < 2 || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("FFT の点の数は 2 以上の 2 のべき乗にしてください: " + size);
        }
        this.size = size;
        this.re = new double[size];
        this.im = new double[size];
        this.bitReversed = new int[size];
        int bits = Integer.numberOfTrailingZeros(size);
        for (int i = 0; i < size; i++) {
            bitReversed[i] = Integer.reverse(i) >>> (Integer.SIZE - bits);
        }
        this.cos = new double[size / 2];
        this.sin = new double[size / 2];
        for (int k = 0; k < size / 2; k++) {
            double angle = 2 * Math.PI * k / size;
            cos[k] = Math.cos(angle);
            sin[k] = Math.sin(angle);
        }
    }

    /**
     * 実数の列のパワースペクトルを求める。
     *
     * <p>{@code 1/N} などの正規化はせず、片側でも 2 倍しない（Issue #466 の仕様。重みはこの大きさで学習してある）。
     *
     * @param input 長さ {@code size} の実数の列（窓を掛けた後）。書き換えない
     * @param power 結果を書く配列（長さ {@code size / 2 + 1} 以上）。{@code power[k] = re² + im²}（k = 0〜size/2）
     */
    public void power(double[] input, double[] power) {
        for (int i = 0; i < size; i++) {
            re[bitReversed[i]] = input[i];
            im[i] = 0.0;
        }
        for (int half = 1; half < size; half <<= 1) {
            int step = size / (half << 1);
            for (int start = 0; start < size; start += half << 1) {
                for (int k = 0; k < half; k++) {
                    // 回転因子 exp(-2πi·k·step/size) = cos − i·sin
                    double wr = cos[k * step];
                    double wi = -sin[k * step];
                    int a = start + k;
                    int b = a + half;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
        for (int k = 0; k <= size / 2; k++) {
            power[k] = re[k] * re[k] + im[k] * im[k];
        }
    }
}
