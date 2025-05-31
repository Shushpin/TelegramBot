package lnu.study.service.impl;

import lnu.study.dao.AppDocumentDAO;
import lnu.study.dao.AppPhotoDAO;
import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.service.FileService;
import lnu.study.utils.CryptoTool;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import lnu.study.dao.AppAudioDAO;
import lnu.study.entity.AppAudio;
import lnu.study.dao.AppVideoDAO;
import lnu.study.entity.AppVideo;


@Service
@Log4j2
public class FileServiceImpl implements FileService {

    private final AppDocumentDAO appDocumentDAO;
    private final AppPhotoDAO appPhotoDAO;
    private final AppAudioDAO appAudioDAO;
    private final AppVideoDAO appVideoDAO;
    private final CryptoTool cryptoTool;

    public FileServiceImpl(AppDocumentDAO appDocumentDAO,AppVideoDAO appVideoDAO,AppAudioDAO appAudioDAO, AppPhotoDAO appPhotoDAO, CryptoTool cryptoTool) {
        this.appDocumentDAO = appDocumentDAO;
        this.appPhotoDAO = appPhotoDAO;
        this.appAudioDAO = appAudioDAO;
        this.appVideoDAO = appVideoDAO;
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
    public AppAudio getAudio(String hash) {
        var id = cryptoTool.idOf(hash);
        if (id == null) {
            return null;
        }
        return appAudioDAO.findById(id).orElse(null);
    }
    @Override
    public AppVideo getVideo(String hash) {
        var id = cryptoTool.idOf(hash);
        if (id == null) {
            return null;
        }
        return appVideoDAO.findById(id).orElse(null);
    }
}
