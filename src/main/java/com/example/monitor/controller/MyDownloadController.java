package com.example.monitor.controller;

import com.example.monitor.dto.DeviceDownloadResponse;
import com.example.monitor.dto.DownloadRequest;
import com.example.monitor.dto.DownloadResponse;
import com.example.monitor.service.DeviceDownloadService;
import com.example.monitor.service.VideoDownloadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ログイン中の利用者が URL を渡して動画を保存する API。
 *
 * <h2>サービスに保存</h2>
 * 中身は管理者の手動ダウンロード（{@link DownloadController}）と同じで、サービスの録画として残る。
 * {@code /api/downloads/**} は ADMIN のみなので、認証だけを求める {@code /api/my/} の下に口を分けている。
 * 配信中・待機所の拒否、取り直さない判定、空き容量の判定、監査ログ（操作者はログイン中の本人）は
 * すべて {@link VideoDownloadService#startDownload(String)} 側にあり、管理者と利用者で食い違わない。
 *
 * <h2>端末に保存</h2>
 * サービスの録画には入れず、サーバーが一時的に取得して、取得した本人だけに渡す（{@link DeviceDownloadService}）。
 * 受け付け・一覧・状態・ファイルの 4 つの口に分けているのは、取得に数分〜数十分かかり、画面が状態を見に来る必要があるため。
 * 一覧があるのは、画面を移る・読み込み直すと、画面が覚えていた仕事 ID が消えるため。
 * ファイルは Spring の資源の配信（{@link Resource} を返す）に任せ、自前のストリーミングは書かない
 * （{@code docs/pitfalls.md}「録画ファイルの配信は自前のストリーミング処理を書かない」。Range にも対応する）。
 * {@code /recordings/**} のような静的配信にしないのは、本人以外に 404 を返す確認を挟むため。
 *
 * <p>Web でしか使わないため {@code @Profile("!cli")} を付けている（{@code docs/pitfalls.md} 参照）。
 */
@RestController
@RequestMapping("/api/my/downloads")
@RequiredArgsConstructor
@Profile("!cli")
public class MyDownloadController {

    private final VideoDownloadService videoDownloadService;
    private final DeviceDownloadService deviceDownloadService;

    /**
     * 動画をサービスの録画として保存し始める。
     *
     * <p>返すのは 202 Accepted（受け付けただけで、終わってはいない。{@link DownloadController} と同じ）。
     *
     * @param request 保存したい動画の URL
     * @return 受け付けた内容（HTTP 202）
     * @throws IllegalArgumentException 対応していない URL、または動画の情報を取得できない場合（400）
     * @throws com.example.monitor.exception.VideoAlreadyDownloadedException
     *         既に同じ動画の録画履歴がある場合（409）
     */
    @PostMapping
    public ResponseEntity<DownloadResponse> startDownload(@Valid @RequestBody DownloadRequest request) {
        DownloadResponse accepted = videoDownloadService.startDownload(request.url());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    /**
     * 動画を「端末に保存」するための一時取得を受け付ける。
     *
     * <p>既にサービスの録画にある動画なら取り直さず、{@code status=READY} と録画のファイルの場所を返す。
     *
     * @param request 保存したい動画の URL
     * @return 受け付けた内容（HTTP 202）
     */
    @PostMapping("/device")
    public ResponseEntity<DeviceDownloadResponse> startDeviceDownload(@Valid @RequestBody DownloadRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(deviceDownloadService.start(request.url()));
    }

    /**
     * ログイン中の利用者の一時取得を、新しい順に返す。他人の仕事は含めない。
     *
     * <p>画面は受け付けたときの仕事 ID を、画面を移る・読み込み直すと失う。そのたびにここから
     * 取得中・受け取れる仕事を取り直せるようにしている（無いと、終わったファイルを受け取れず、
     * 取得中は次を頼むと 409 になるだけだった）。
     *
     * @return 自分の仕事の状態。無ければ空の配列
     */
    @GetMapping("/device")
    public List<DeviceDownloadResponse> deviceDownloads() {
        return deviceDownloadService.list();
    }

    /**
     * 一時取得の状態を返す。本人以外は 404。
     *
     * @param jobId 仕事 ID
     * @return 状態
     */
    @GetMapping("/device/{jobId}")
    public DeviceDownloadResponse deviceDownloadStatus(@PathVariable String jobId) {
        return deviceDownloadService.status(jobId);
    }

    /**
     * 受け取れるファイル（完成品、または途中までのもの）を添付として返す。本人以外・取得中・失敗したものは 404。
     *
     * @param jobId 仕事 ID
     * @return ファイル
     */
    @GetMapping("/device/{jobId}/file")
    public ResponseEntity<Resource> deviceDownloadFile(@PathVariable String jobId) {
        DeviceDownloadService.ReadyFile file = deviceDownloadService.readyFile(jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.downloadName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(file.path()));
    }
}
