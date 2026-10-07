package com.example.monitor.controller;

import com.example.monitor.dto.SystemProcessesResponse;
import com.example.monitor.service.SystemProcessService;
import com.example.monitor.service.SystemProcessService.StopResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    /**
     * 選んだプロセスを子孫ごと止める。SIGTERM を送り、10 秒たっても残れば SIGKILL を送る。
     *
     * <p>止め終わるまで最大 15 秒かかるので待たず、受け付けたら 202 を返す（結果は一覧の
     * {@code stops} で分かる）。止められないときは例外にせず 409 を返す。画面の表示と今の状態の
     * 食い違いで、サーバーの異常ではないため（{@code RecordingController#stopRecording} と同じ
     * 返し方）。
     *
     * <p>録画はこの API では止めず、{@code /api/recordings/{id}/stop} で止める。録り直しを止める印を
     * 立てないと、録画の仕組みが「普通に終わった」と見て今の時点から録り直すため。
     *
     * @param pid       止めるプロセスの ID
     * @param startTime 画面で選んだときのプロセスの起動時刻（エポックミリ秒）。PID は使い回されるので、
     *                  同じプロセスかを確かめる
     * @return 受け付けたら 202 と停止の記録、断ったら 409（本文は {@code {"error": "理由"}}）
     */
    @PostMapping("/processes/{pid:\\d+}/stop")
    public ResponseEntity<Object> stopProcess(@PathVariable int pid, @RequestParam long startTime) {
        StopResult result = systemProcessService.stop(pid, startTime);
        if (result.error() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", result.error()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.job());
    }
}
