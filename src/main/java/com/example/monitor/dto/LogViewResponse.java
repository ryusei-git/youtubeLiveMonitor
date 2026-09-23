package com.example.monitor.dto;

import java.util.List;

/**
 * ログ画面 1 回分の表示内容。ログ本体と、そのファイルに実在するレベルの一覧を合わせて返す。
 *
 * <p>レベルの一覧を同梱しているのは、画面の絞り込みの選択肢を
 * <b>実際に出力されている値から作る</b>ため。あらかじめ決め打ちした一覧を出すと、
 * そのログには 1 件も無いレベルまで選べてしまい「選んだのに 0 件」という結果になる。
 *
 * <p>{@link #availableLevels} は絞り込み前の全行から集めた値である点に注意。
 * 絞り込み後の結果から集めてしまうと、一度 ERROR に絞った時点で選択肢が ERROR だけになり、
 * 他のレベルへ戻れなくなる。
 *
 * @param availableLevels このログファイルに実在するログレベル。深刻な順（ERROR → TRACE）に並ぶ
 * @param entries         絞り込みと件数制限を適用した後のログ。古い順に並ぶ
 */
public record LogViewResponse(
        List<String> availableLevels,
        List<LogEntry> entries
) {}
