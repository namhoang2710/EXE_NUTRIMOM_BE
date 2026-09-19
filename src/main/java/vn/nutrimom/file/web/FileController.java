package vn.nutrimom.file.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.file.dto.CompleteFileRequest;
import vn.nutrimom.file.dto.CreateUploadSessionRequest;
import vn.nutrimom.file.dto.DownloadUrlResponse;
import vn.nutrimom.file.dto.FileResponse;
import vn.nutrimom.file.dto.UploadSessionResponse;
import vn.nutrimom.file.service.FileService;

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files", description = "Private upload sessions and short-lived downloads")
@SecurityRequirement(name = "bearerAuth")
public class FileController {
    private final FileService service;

    public FileController(FileService service) { this.service = service; }

    @PostMapping("/upload-sessions")
    @Operation(summary = "Create a private upload session")
    public ApiResponse<UploadSessionResponse> createSession(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateUploadSessionRequest request) {
        return ApiResponses.success(service.createUploadSession(jwt.getSubject(), request));
    }

    @PutMapping("/{fileId}/content")
    @Operation(summary = "Upload bytes through the local development storage adapter")
    public ResponseEntity<Void> putContent(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String fileId,
                                            @RequestBody byte[] content) {
        service.putContent(jwt.getSubject(), fileId, content);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{fileId}/complete")
    @Operation(summary = "Complete and validate an upload")
    public ApiResponse<FileResponse> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String fileId,
            @Valid @RequestBody(required = false) CompleteFileRequest request) {
        return ApiResponses.success(service.complete(jwt.getSubject(), fileId, request));
    }

    @GetMapping("/{fileId}/download-url")
    @Operation(summary = "Create a short-lived private download URL")
    public ApiResponse<DownloadUrlResponse> downloadUrl(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable String fileId) {
        return ApiResponses.success(service.createDownloadUrl(jwt.getSubject(), fileId));
    }

    @GetMapping("/{fileId}/content")
    @Operation(summary = "Download file content using owner auth or a signed URL")
    public ResponseEntity<byte[]> content(
            @AuthenticationPrincipal Object principal,
            @PathVariable String fileId,
            @RequestParam(required = false) String token) {
        String userId = principal instanceof Jwt jwt ? jwt.getSubject() : null;
        FileService.DownloadedFile file = service.download(userId, fileId, token);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.mimeType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(file.content());
    }
}
