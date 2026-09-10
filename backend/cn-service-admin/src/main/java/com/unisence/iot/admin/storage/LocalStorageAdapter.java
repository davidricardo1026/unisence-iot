package com.unisence.iot.admin.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class LocalStorageAdapter implements StorageService {

    private final StorageProperties props;

    @Override
    public StoredFile upload(UploadCommand command) {
        String ext = getExtension(command.originalFilename());
        String fileKey = command.folder() + "/" + UUID.randomUUID() + ext;
        Path target = Paths.get(props.getLocal().getBasePath(), fileKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(command.inputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("文件存储失败", e);
        }
        return new StoredFile(fileKey, command.originalFilename(), command.contentType(), command.size(), null);
    }

    @Override
    public Resource download(String fileKey) {
        Path path = Paths.get(props.getLocal().getBasePath(), fileKey);
        return Files.exists(path) ? new FileSystemResource(path) : null;
    }

    @Override
    public void delete(String fileKey) {
        Path path = Paths.get(props.getLocal().getBasePath(), fileKey);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", fileKey, e);
        }
    }

    private String getExtension(String name) {
        if (name != null && name.contains(".")) {
            return name.substring(name.lastIndexOf(".")).toLowerCase();
        }
        return "";
    }
}
