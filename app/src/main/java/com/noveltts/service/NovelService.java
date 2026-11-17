package com.noveltts.service;

import com.noveltts.config.NovelTtsProperties;
import com.noveltts.domain.entity.Chapter;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.entity.NovelGroup;
import com.noveltts.domain.enums.ChapterStatus;
import com.noveltts.domain.enums.NovelStatus;
import com.noveltts.domain.repository.ChapterRepository;
import com.noveltts.domain.repository.DialogueSegmentRepository;
import com.noveltts.domain.repository.NovelGroupRepository;
import com.noveltts.domain.repository.NovelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NovelService {

    private final NovelRepository novelRepository;
    private final NovelGroupRepository novelGroupRepository;
    private final ChapterRepository chapterRepository;
    private final DialogueSegmentRepository segmentRepository;
    private final ChapterSplitter chapterSplitter;
    private final StorageService storageService;
    private final NovelTtsProperties properties;

    public NovelService(NovelRepository novelRepository,
                         NovelGroupRepository novelGroupRepository,
                         ChapterRepository chapterRepository,
                         DialogueSegmentRepository segmentRepository,
                         ChapterSplitter chapterSplitter,
                         StorageService storageService,
                         NovelTtsProperties properties) {
        this.novelRepository = novelRepository;
        this.novelGroupRepository = novelGroupRepository;
        this.chapterRepository = chapterRepository;
        this.segmentRepository = segmentRepository;
        this.chapterSplitter = chapterSplitter;
        this.storageService = storageService;
        this.properties = properties;
    }

    @Transactional
    public Novel create(String title, String author, String content, String sourceFilename) {
        return create(title, author, content, sourceFilename, null, null);
    }

    /**
     * 建立小說，可指定所屬群組與群組內的集數順序。
     *
     * 群組讓同一本連載小說的各集共用角色音色與旁白音色；
     * groupOrder 決定集數順序，未指定時接續群組內既有的集數。
     */
    @Transactional
    public Novel create(String title, String author, String content, String sourceFilename,
                        Long groupId, Integer groupOrder) {
        Novel novel = new Novel();
        novel.setTitle(title == null || title.isBlank() ? "未命名小說" : title.strip());
        novel.setAuthor(author);
        novel.setSourceText(content);
        novel.setSourceFilename(sourceFilename);
        novel.setTotalChars(content == null ? 0 : content.length());
        novel.setStatus(NovelStatus.PENDING);

        if (groupId != null) {
            NovelGroup group = novelGroupRepository.findById(groupId)
                    .orElseThrow(() -> new IllegalArgumentException("找不到群組 " + groupId));
            novel.setGroup(group);
            novel.setGroupOrder(groupOrder != null ? groupOrder : nextGroupOrder(groupId));
        }

        novel = novelRepository.save(novel);
        splitChapters(novel);
        return novel;
    }

    /** 群組內的下一個集數順序。 */
    private Integer nextGroupOrder(Long groupId) {
        List<Novel> siblings = novelRepository.findByGroupIdWithDetails(groupId);
        int max = 0;
        for (Novel n : siblings) {
            if (n.getGroupOrder() != null) {
                max = Math.max(max, n.getGroupOrder());
            }
        }
        return max + 1;
    }

    @Transactional
    public List<Chapter> splitChapters(Novel novel) {
        chapterRepository.deleteByNovelId(novel.getId());
        chapterRepository.flush();

        List<ChapterSplitter.Chunk> chunks = chapterSplitter.split(novel.getSourceText(),
                properties.getChapterMaxChars());
        List<Chapter> chapters = new java.util.ArrayList<>();
        for (ChapterSplitter.Chunk chunk : chunks) {
            Chapter chapter = new Chapter();
            chapter.setNovel(novel);
            chapter.setChapterIndex(chunk.index());
            chapter.setTitle(chunk.title());
            chapter.setContent(chunk.content());
            chapter.setCharCount(chunk.content().length());
            chapter.setStatus(ChapterStatus.PENDING);
            chapters.add(chapterRepository.save(chapter));
        }
        novel.setTotalChapters(chapters.size());
        novelRepository.save(novel);
        return chapters;
    }

    @Transactional
    public void delete(Long novelId) {
        Novel novel = novelRepository.findById(novelId).orElse(null);
        if (novel == null) {
            return;
        }
        novelRepository.delete(novel);
        storageService.deleteNovelFiles(novelId);
    }

    @Transactional(readOnly = true)
    public List<Chapter> chapters(Long novelId) {
        return chapterRepository.findByNovelIdOrderByChapterIndex(novelId);
    }

    @Transactional(readOnly = true)
    public List<DialogueSegment> segments(Long chapterId) {
        return segmentRepository.findByChapterIdOrderBySeq(chapterId);
    }
}
