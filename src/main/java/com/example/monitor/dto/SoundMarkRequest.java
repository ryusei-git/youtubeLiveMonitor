package com.example.monitor.dto;

/**
 * 録画に音の印を付けるリクエスト。
 *
 * <p>種類を enum ではなく文字列で受けるのは、enum だと知らない種類が JSON の読み取りで失敗し、
 * 入力誤りの 400 ではなく「予期しないエラー」の 500 になるため（サービスで直して 400 にする）。
 * 位置を {@code long} ではなく {@code Long} で受けるのは、書き忘れを 0 ミリ秒の印として通さないため。
 *
 * @param kind       種類（{@code EAR_KISS}）
 * @param positionMs 録画の先頭からの位置（ミリ秒）
 */
public record SoundMarkRequest(String kind, Long positionMs) {}
