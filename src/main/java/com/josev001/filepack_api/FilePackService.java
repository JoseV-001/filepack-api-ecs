package com.josev001.filepack_api;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;

@Service
public class FilePackService {

    private static final Logger log = LoggerFactory.getLogger(FilePackService.class);

    public ZipResourceModel createEncryptedZipResource(
            List<MultipartFile> multipartFiles,
            String password,
            String requestId) throws IOException {
        log.info(
                "PACK_START requestId={} fileNames={}",
                requestId,
                multipartFiles.stream().map(MultipartFile::getOriginalFilename).toList());

        List<File> tempFiles = new ArrayList<>();
        
        try {
            // Converte MultipartFile para File temporário
            for (MultipartFile multipartFile : multipartFiles) {
                File tempFile = File.createTempFile("upload-", "-" + multipartFile.getOriginalFilename());
                multipartFile.transferTo(tempFile);
                tempFiles.add(tempFile);
            }

            // Cria o ZIP criptografado
            File zipFile = createEncryptedZip(tempFiles, password, requestId);
            tempFiles.add(zipFile); // Adiciona o ZIP à lista de limpeza

            // Prepara o recurso para retorno
            Resource resource = new FileSystemResource(zipFile);
            return new ZipResourceModel(resource, zipFile.getName(), zipFile.length(), new ArrayList<>(tempFiles));
            
        } catch (IOException e) {
            // Em caso de erro, limpa os arquivos temporários já criados
            cleanupFiles(tempFiles);
            throw e;
        }
    }

    private File createEncryptedZip(List<File> files, String password, String requestId) throws IOException {
        long zipStart = System.currentTimeMillis();

        ZipParameters params = new ZipParameters();
        params.setEncryptFiles(true);
        // AES é a opção mais segura suportada pelo formato zip
        params.setEncryptionMethod(EncryptionMethod.AES);

        // arquivo de saída no diretório temporário
        File target = File.createTempFile("archive-", ".zip");
        ZipFile zipFile = new ZipFile(target, password.toCharArray());
        zipFile.addFiles(files, params);

        log.info(
                "PACK_END requestId={} compressionTimeMs={} outputSizeBytes={}",
                requestId,
                System.currentTimeMillis() - zipStart,
                target.length());

        return target;
    }

    private void cleanupFiles(List<File> files) {
        for (File file : files) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.warn("TEMP_FILE_DELETE_FAILED path={}", file.getAbsolutePath(), e);
            }
        }
    }
}
