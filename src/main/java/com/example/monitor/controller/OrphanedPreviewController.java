package com.example.monitor.controller;
import com.example.monitor.service.OrphanedPreviewService;
import com.example.monitor.dto.OrphanedCleanupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 削除の確定前に対象と容量を見せるための専用経路。 */
@RestController
@RequestMapping("/api/recordings/orphaned")
@RequiredArgsConstructor
public class OrphanedPreviewController {
    private final OrphanedPreviewService service;
    @GetMapping("/preview")
    public OrphanedPreviewService.Preview preview() { return service.preview(); }
    @DeleteMapping("/confirmed")
    public OrphanedCleanupResponse delete(@RequestParam String token) { return service.deleteConfirmed(token); }
}
