package lnu.study.service;

import lnu.study.entity.AppDocument;
import lnu.study.entity.AppPhoto;
import lnu.study.entity.AppAudio;
import lnu.study.entity.AppVideo;

public interface FileService {

    AppDocument getDocument(String id);
    AppPhoto getPhoto(String id);
    AppAudio getAudio(String hash);
    AppVideo getVideo(String hash);
}
