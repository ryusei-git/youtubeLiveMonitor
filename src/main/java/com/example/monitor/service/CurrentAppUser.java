package com.example.monitor.service;

import com.example.monitor.entity.AppUser;
import com.example.monitor.repository.AppUserRepository;
import com.example.monitor.util.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ログイン中の利用者を DB から取り出す。
 *
 * <p>利用者本人のデータ（購読・通知の設定・録画の印）を扱うサービスが同じ処理をそれぞれ
 * private メソッドで持っていたのを 1 つにまとめた（#268）。複製のままだと、
 * たとえば無効化された利用者を弾く、といった変更を片方にだけ入れる事故が起きるため。
 *
 * <p>リポジトリを使うので {@code util} の static メソッドではなく Bean にしている。
 */
@Component
@RequiredArgsConstructor
public class CurrentAppUser {

    private final AppUserRepository appUserRepository;

    /**
     * ログイン中の利用者を取得する。
     *
     * <p>認証を必須にしている経路からしか呼ばれない前提のため、取得できなければ想定外として例外にする
     * （誰のデータか分からないまま処理を続けると、別の利用者のデータを変えかねない）。
     *
     * <p><b>監視ループ・録画スレッドから呼ぶメソッドでは使わない。</b>そこにはログインが無く、
     * 必ず例外になる。
     *
     * @return ログイン中の利用者
     * @throws IllegalStateException ログイン情報が無いとき、またはその利用者が DB に無いとき
     */
    public AppUser require() {
        String username = RequestContext.currentUsername();
        if (username == null) {
            throw new IllegalStateException("ログイン情報を特定できませんでした");
        }
        return appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("ログイン中の利用者が見つかりません: " + username));
    }
}
