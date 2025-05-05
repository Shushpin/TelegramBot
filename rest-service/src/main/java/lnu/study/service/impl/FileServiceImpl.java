package lnu.study.service.impl;

import lnu.study.dao.AppDocumentDAO;
import lnu.study.dao.AppPhotoDAO;
import lnu.study.dao.BinaryContentDAO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.BinaryContent;
import lnu.study.service.FileService;
import lnu.study.utils.CryptoTool;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;


@Service
@Log4j2
public class FileServiceImpl implements FileService {

    private final AppDocumentDAO appDocumentDAO;
    private final AppPhotoDAO appPhotoDAO;
    private final CryptoTool cryptoTool;

    public FileServiceImpl(AppDocumentDAO appDocumentDAO, AppPhotoDAO appPhotoDAO, CryptoTool cryptoTool) {
        this.appDocumentDAO = appDocumentDAO;
        this.appPhotoDAO = appPhotoDAO;
        this.cryptoTool = cryptoTool;
    }

    @Override
    public AppDocument getDocument(String hash) {
        var id = cryptoTool.idOf(hash);
        if (id == null) {
            return null;
        }
        return appDocumentDAO.findById(id).orElse(null);
    }

    @Override
    public AppPhoto getPhoto(String hast) {
        var id = cryptoTool.idOf(hast);
        if (hast == null) {
            return null;
        }
        return appPhotoDAO.findById(id).orElse(null);
    }

    @Override
    public FileSystemResource getFileSystemResource(BinaryContent binaryContent) {
        try{
            //TODO додати генерацію імені тимчасосвого файлу
            File temp = File.createTempFile("tempFile", ".bin");
            temp.deleteOnExit();
            FileUtils.writeByteArrayToFile(temp, binaryContent.getFileAsArrayOfBytes());
            return new FileSystemResource(temp);
        } catch (IOException e) {
            e.printStackTrace();
            log.error(e);
            return null;
        }

    }
}
