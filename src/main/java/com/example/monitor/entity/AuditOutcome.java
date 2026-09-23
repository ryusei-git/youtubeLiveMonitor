package com.example.monitor.entity;

/**
 * 監査ログに記録する操作の結果。
 *
 * <p>{@link AuditLog#outcome} に使う。「成功だけ記録する監査ログは役に立たない」という方針
 * （{@code docs/user-portal-design.md} 4.3 参照）のもとでは、ログイン失敗の連続や
 * 権限の無い操作の試行そのものが不正の証跡になるため、{@link #FAILURE} を必ず残す。
 */
public enum AuditOutcome {

    /** 操作が成功した。 */
    SUCCESS,

    /** 操作が失敗した。失敗理由は {@link AuditLog#detail} に残す。 */
    FAILURE
}
