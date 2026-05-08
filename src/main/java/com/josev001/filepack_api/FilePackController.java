package com.josev001.filepack_api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/filepack")
public class FilePackController {

    private static final Logger log = LoggerFactory.getLogger(FilePackController.class);

    private final FilePackService filePackService;

    public FilePackController(FilePackService filePackService) {
        this.filePackService = filePackService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    public ResponseEntity<Resource> createEncryptedZip(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("password") String password) throws IOException {

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();
        int fileCount = files.size();
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();

        log.info("REQUEST_START requestId={} fileCount={} totalSizeBytes={}", requestId, fileCount, totalSize);

        ZipResourceModel zipResource = filePackService.createEncryptedZipResource(files, password, requestId);

        try {
            // Lê o arquivo ZIP para memória antes de deletar
            Path zipPath = zipResource.getResource().getFile().toPath();
            byte[] zipBytes = Files.readAllBytes(zipPath);
            
            // Cria um recurso em memória
            ByteArrayResource resource = new ByteArrayResource(zipBytes);

            long duration = System.currentTimeMillis() - startTime;
            log.info("REQUEST_END requestId={} durationMs={} zipSizeBytes={}", requestId, duration, zipBytes.length);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipResource.getFilename() + "\"")
                    .contentLength(zipBytes.length)
                    .body(resource);

        } finally {
            // Agora é seguro limpar os arquivos temporários
            zipResource.cleanup();
        }
    }
}
