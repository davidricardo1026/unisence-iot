package com.unisence.iot.admin.storage;

import org.springframework.core.io.Resource;

public interface StorageService {

    StoredFile upload(UploadCommand command);

    Resource download(String objectKey);

    void delete(String objectKey);
}
