package com.dhu.secondhand.marketplace;

import com.dhu.secondhand.common.SessionAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

@RestController
@RequestMapping("/api/images")
public class ImageController {
    private final ImageStorage storage;
    private final SessionAuth auth;

    public ImageController(ImageStorage storage, SessionAuth auth) {
        this.storage = storage;
        this.auth = auth;
    }

    public record UploadResponse(String url) { }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        long owner = auth.requireUserId(request);
        return new UploadResponse(storage.store(owner, file));
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> image(@PathVariable String name) {
        var path = storage.find(name);
        return ResponseEntity.ok()
                .contentType(name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }
}
