package com.example.monitor.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 利用者名の変更と接続元の変更のどちらでも総当たりを続けられないよう、両方の上限を持つ。
 * 同時認証中の枠も予約し、結果が返る前の並列送信による上限超過を防ぐ。
 */
@Component
@Profile("!cli")
public class LoginAttemptLimiter {
    private static final int USER_LIMIT = 5;
    private static final int IP_LIMIT = 20;
    private static final int MAX_KEYS = 10000;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final Clock clock;
    private final Map<String, Counter> users = new HashMap<>();
    private final Map<String, Counter> addresses = new HashMap<>();

    /** サーバー時刻を基準とし、リクエストから時刻を指定させない。 */
    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 認証前に枠を確保する。期限内の記録は容量不足でも追い出さず、制限の迂回を防ぐ。
     * @param username Spring Securityと同じく前後の空白を除いた利用者名
     * @param address 信頼する接続元アドレス
     * @return 認証枠。拒否時は待ち時間を持つ
     */
    public synchronized Attempt begin(String username, String address) {
        Instant now = clock.instant();
        users.values().removeIf(c -> c.inFlight == 0 && !now.isBefore(c.expiresAt));
        addresses.values().removeIf(c -> c.inFlight == 0 && !now.isBefore(c.expiresAt));
        // 登録可能な名前は64文字まで。過大な入力をそのままメモリへ蓄積しない。
        String key = username.length() > 64 ? "\u0000oversized" : username;
        if ((!users.containsKey(key) && users.size() >= MAX_KEYS)
                || (!addresses.containsKey(address) && addresses.size() >= MAX_KEYS)) {
            return new Attempt(null, null, 60);
        }
        Counter user = users.computeIfAbsent(key, ignored -> new Counter(now.plus(WINDOW)));
        Counter ip = addresses.computeIfAbsent(address, ignored -> new Counter(now.plus(WINDOW)));
        long retry = Math.max(retryAfter(user, USER_LIMIT, now), retryAfter(ip, IP_LIMIT, now));
        if (retry > 0) return new Attempt(null, null, retry);
        user.inFlight++;
        ip.inFlight++;
        return new Attempt(user, ip, 0);
    }

    /**
     * 成功時は両方の連続失敗をリセットする。拒否されたリクエストでは期限を延長しない。
     * @param attempt 確保した枠
     * @param successful 認証が成功したか
     */
    public synchronized void finish(Attempt attempt, boolean successful) {
        if (!attempt.allowed() || attempt.finished) return;
        attempt.finished = true;
        Instant now = clock.instant();
        complete(attempt.user, successful, USER_LIMIT, now);
        complete(attempt.ip, successful, IP_LIMIT, now);
    }

    private void complete(Counter counter, boolean successful, int limit, Instant now) {
        counter.inFlight--;
        if (successful) {
            counter.failures = 0;
            counter.expiresAt = now.plus(WINDOW);
        } else if (++counter.failures >= limit) {
            counter.expiresAt = now.plus(WINDOW);
        }
    }

    private long retryAfter(Counter counter, int limit, Instant now) {
        if (counter.failures >= limit) {
            return Math.max(1, (Duration.between(now, counter.expiresAt).toMillis() + 999) / 1000);
        }
        return counter.failures + counter.inFlight >= limit ? 1 : 0;
    }

    private static final class Counter {
        private int failures;
        private int inFlight;
        private Instant expiresAt;

        private Counter(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    /** 呼び出し側にカウンターを直接変更させず、予約と完了を対にする。 */
    public static final class Attempt {
        private final Counter user;
        private final Counter ip;
        private final long retryAfterSeconds;
        private boolean finished;

        private Attempt(Counter user, Counter ip, long retryAfterSeconds) {
            this.user = user;
            this.ip = ip;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        /** @return 認証処理へ進めるか */
        public boolean allowed() { return user != null; }

        /** @return 拒否時の再試行までの秒数 */
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }
}
