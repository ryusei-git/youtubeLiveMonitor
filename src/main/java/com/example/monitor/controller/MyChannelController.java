package com.example.monitor.controller;

import com.example.monitor.dto.SubscribedChannelResponse;
import com.example.monitor.dto.SubscriptionRecordRequest;
import com.example.monitor.dto.SubscriptionRequest;
import com.example.monitor.service.UserSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ログイン中の利用者自身の購読を操作する API。
 *
 * <h2>パスを {@code /api/my/} で始める理由</h2>
 * <b>「誰のものか」をパスで示し、他人の ID を指定する余地を作らない。</b>
 * 対象の利用者は常にログイン中の本人で、リクエストからは指定できない
 * （指定できる形にすると、渡された ID の検証漏れがそのまま他人のデータへの
 * 操作になる）。管理者向けの {@code /api/channels} とは<b>別の窓口</b>として分けている。
 *
 * <p>CLI モードでは Web サーバーを起動しないが、コンポーネントスキャンで Bean 自体は
 * 作られる。依存する Bean が揃わないと CLI が丸ごと起動できなくなるため、
 * Web 専用のコントローラーには {@code @Profile("!cli")} を付ける決まりだが、
 * このコントローラーが依存する {@link UserSubscriptionService} は
 * プロファイルを問わず作られるので付けていない。
 */
@RestController
@RequestMapping("/api/my/channels")
@RequiredArgsConstructor
public class MyChannelController {

    private final UserSubscriptionService userSubscriptionService;

    /**
     * 自分が購読しているチャンネルの一覧を返す。
     *
     * @return 購読しているチャンネル
     */
    @GetMapping
    public List<SubscribedChannelResponse> listMyChannels() {
        return userSubscriptionService.listMySubscriptions();
    }

    /**
     * チャンネルを購読する。
     *
     * @param request 購読するチャンネルの指定
     * @return 追加された購読
     */
    @PostMapping
    public SubscribedChannelResponse subscribe(@Valid @RequestBody SubscriptionRequest request) {
        return userSubscriptionService.subscribe(
                request.platformOrDefault(), request.channelInput(), request.channelName(),
                request.recordEnabledOrDefault());
    }

    /**
     * 自分の録画の希望を変更する。
     *
     * <p><b>変わるのは自分の設定だけ。</b>実際に録画されるかは
     * 「誰か 1 人でも希望していれば録画する」で決まるため、自分が OFF にしても
     * 他に希望者がいれば録画は続く。
     *
     * @param channelId 対象チャンネルの主キー
     * @param request   希望する設定
     * @return 変更後の購読。購読していなければ 404
     */
    @PutMapping("/{channelId}/record")
    public ResponseEntity<SubscribedChannelResponse> updateRecordSetting(
            @PathVariable Long channelId, @RequestBody SubscriptionRecordRequest request) {
        return userSubscriptionService
                .updateRecordSetting(channelId, request.enabled(), request.titleKeywords())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 購読を解除する。
     *
     * <p>解除できるのは<b>自分の購読だけ</b>。チャンネル本体は残るため、
     * 他の利用者の購読・通知履歴・録画ファイルには影響しない。
     *
     * @param channelId 解除するチャンネルの主キー
     * @return 解除できたら 204。購読していなければ 404
     */
    @DeleteMapping("/{channelId}")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long channelId) {
        return userSubscriptionService.unsubscribe(channelId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
