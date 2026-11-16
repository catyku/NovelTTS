package com.noveltts.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterSplitterTest {

    private final ChapterSplitter splitter = new ChapterSplitter();

    @Test
    void 依中文章節標題切分() {
        String text = """
                第一章 開端

                夜色籠罩著小鎮。

                第二章 追尋

                三天後。
                """;

        List<ChapterSplitter.Chunk> chunks = splitter.split(text, 3000);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).title()).isEqualTo("第一章 開端");
        assertThat(chunks.get(0).content()).contains("夜色籠罩著小鎮");
        assertThat(chunks.get(0).content()).doesNotContain("三天後");
        assertThat(chunks.get(1).index()).isEqualTo(2);
        assertThat(chunks.get(1).content()).contains("三天後");
    }

    /**
     * 章節標題已存在於 title 欄位，若留在 content 會被 AI 當成旁白內容念出來。
     */
    @Test
    void 章節內容不含標題行() {
        String text = """
                第一章 開端

                夜色籠罩著小鎮。

                第二章 追尋

                三天後。
                """;

        List<ChapterSplitter.Chunk> chunks = splitter.split(text, 3000);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).doesNotContain("第一章");
        assertThat(chunks.get(0).content()).startsWith("夜色籠罩著小鎮");
        assertThat(chunks.get(1).content()).doesNotContain("第二章");
    }

    @Test
    void 依英文章節標題切分() {
        String text = """
                Chapter 1

                The door opened.

                Chapter 2

                She stepped inside.
                """;

        List<ChapterSplitter.Chunk> chunks = splitter.split(text, 3000);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).title()).isEqualTo("Chapter 1");
        assertThat(chunks.get(1).content()).contains("She stepped inside");
    }

    @Test
    void 無標題時依字數上限切分() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("這是第").append(i).append("個段落。\n\n");
        }

        List<ChapterSplitter.Chunk> chunks = splitter.split(sb.toString(), 100);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content()).isNotBlank());
    }

    @Test
    void 空內容回傳空清單() {
        assertThat(splitter.split(null, 3000)).isEmpty();
        assertThat(splitter.split("   ", 3000)).isEmpty();
    }
}
