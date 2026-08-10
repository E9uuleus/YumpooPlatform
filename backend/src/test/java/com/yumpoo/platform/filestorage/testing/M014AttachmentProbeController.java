package com.yumpoo.platform.filestorage.testing;

import com.yumpoo.platform.filestorage.application.AttachmentUploadPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.UUID;

/** Test-only HTTP 探针；不打包进生产 JAR，也不进入正式 OpenAPI paths。 */
@Profile("m0-14-probe")
@RestController
@RequestMapping("/api/v1/__test/m0-14/attachments")
public final class M014AttachmentProbeController {

    public static final String ACTOR_HEADER = "X-M0-14-Actor";

    private final M014AttachmentProbeService service;

    public M014AttachmentProbeController(M014AttachmentProbeService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<M014AttachmentProbeService.Metadata> createIntent(
            @RequestHeader(ACTOR_HEADER) UUID actorId,
            @RequestBody CreateIntentRequest request
    ) {
        M014AttachmentProbeService.Metadata metadata = service.createIntent(
                request.ownerId(),
                actorId,
                request.fileName(),
                request.declaredMime()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/__test/m0-14/attachments/" + metadata.id()
                )
                .body(metadata);
    }

    @PutMapping(path = "/{attachmentId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<M014AttachmentProbeService.Metadata> uploadContent(
            @PathVariable UUID attachmentId,
            @RequestHeader(ACTOR_HEADER) UUID actorId,
            HttpServletRequest request
    ) throws IOException {
        long declaredLength = request.getContentLengthLong();
        OptionalLong contentLength = declaredLength < 0
                ? OptionalLong.empty()
                : OptionalLong.of(declaredLength);
        M014AttachmentProbeService.Metadata metadata;
        try (InputStream input = request.getInputStream()) {
            metadata = service.receive(attachmentId, actorId, input, contentLength);
        }
        return ResponseEntity.accepted().body(metadata);
    }

    @GetMapping("/{attachmentId}")
    M014AttachmentProbeService.Metadata metadata(
            @PathVariable UUID attachmentId,
            @RequestHeader(ACTOR_HEADER) UUID actorId
    ) {
        return service.findMetadata(attachmentId, actorId);
    }

    @GetMapping("/{attachmentId}/content")
    ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID attachmentId,
            @RequestHeader(ACTOR_HEADER) UUID actorId
    ) {
        M014AttachmentProbeService.Download download = service.openDownload(
                attachmentId,
                actorId
        );
        StreamingResponseBody body = output -> {
            try (InputStream input = download.inputStream()) {
                byte[] buffer = new byte[AttachmentUploadPolicy.BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.detectedMime()))
                .contentLength(download.sizeBytes())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("X-Content-SHA256", download.sha256())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(body);
    }

    public record CreateIntentRequest(
            UUID ownerId,
            String fileName,
            String declaredMime
    ) {
    }
}
