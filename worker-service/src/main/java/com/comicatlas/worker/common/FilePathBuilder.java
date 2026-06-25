package com.comicatlas.worker.common;

import org.springframework.stereotype.Component;

@Component
public class FilePathBuilder {

    public String hqDir(Long comicId, String chapterNo) {
        return String.format("hq/%d/%s", comicId, chapterNo);
    }

    public String lqDir(Long comicId, String chapterNo) {
        return String.format("lq/%d/%s", comicId, chapterNo);
    }

    public String hqFile(Long comicId, String chapterNo, String imageName) {
        return String.format("hq/%d/%s/%s", comicId, chapterNo, imageName);
    }

    public String lqFile(Long comicId, String chapterNo, String baseName) {
        return String.format("lq/%d/%s/%s.webp", comicId, chapterNo, baseName);
    }

    public String thumbPath(Long comicId) {
        return String.format("thumbs/%d/cover.webp", comicId);
    }

    public String rawPath(Long comicId) {
        return String.format("raw/%d.zip", comicId);
    }

    public String tempDir(Long taskId) {
        return String.format("temp/%d", taskId);
    }

    public String metadataFile(Long taskId) {
        return String.format("metadata/%d.json", taskId);
    }
}
