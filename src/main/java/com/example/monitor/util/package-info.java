/**
 * 複数のクラスで使う、状態を持たない static メソッドだけを置く。
 *
 * <p>特定の機能の private メソッドのままにすると、同じ処理が別のクラスにも要るときに複製されやすいため、
 * ここへ切り出す（AGENTS.md「コードを書くときの約束」）。状態を持つもの・Bean に依存するものは {@code service} に置く。
 */
package com.example.monitor.util;
