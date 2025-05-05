package lnu.study.service;

import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.BinaryContent;
import org.springframework.core.io.FileSystemResource;

public interface FileService {

    AppDocument getDocument(String id);
    AppPhoto getPhoto(String id);
    FileSystemResource getFileSystemResource(BinaryContent binaryContent);

}
