package com.example.monitor.controller;

import com.example.monitor.dto.SystemProcessesResponse;
import com.example.monitor.service.SystemProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端末の状態の画面（{@code /system.html}）が使う REST API。管理者だけが使える
 * （{@code SecurityConfig}）。
 *
 * <p>CLI では作らない。{@link SystemProcessService} が依存するリソース計測が CLI では作られず、
 * 依存したままだと CLI が丸ごと起動できなくなるため（{@code DashboardController} と同じ）。
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemProcessService systemProcessService;

    /**
     * 実行ユーザーのプロセスの一覧と、左メニューのサービスの状態を返す。
     *
     * <p>コマンドラインにはパスワードやトークンなどの秘密が入ることがあるので、この API の外
     * （アプリのログ・監査ログ）には出さない。値は最大 10 秒古い
     * （{@link SystemProcessService#processes()}）。
     *
     * @return プロセスの一覧とサービスの状態
     */
    @GetMapping("/processes")
    public SystemProcessesResponse getProcesses() {
        return systemProcessService.processes();
    }
}
