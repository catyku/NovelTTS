package com.noveltts.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章節切分：優先偵測標題行；找不到標題時依字數上限切分。
 */
@Component
public class ChapterSplitter {

    /** 中文章節標題：第一章 / 第 12 回 / 第三节 */
    private static final Pattern CN_HEADING = Pattern.compile(
            "^[\\s　]*(第\\s*[0-9０-９一二三四五六七八九十百千零兩]+\\s*[章回節卷部篇])[^\\n]{0,40}$");
    /** 英文章節標題：Chapter 1 / CHAPTER I */
    private static final Pattern EN_HEADING = Pattern.compile(
            "^[\\s　]*(CHAPTER|Chapter)\\s+[0-9IVXLCivxlc]+[^\\n]{0,60}$");
    /** Markdown 標題：## 第一章 */
    private static final Pattern MD_HEADING = Pattern.compile("^[\\s　]*#{1,3}\\s+\\S[^\\n]{0,60}$");

    public record Chunk(int index, String title, String content) {
    }

    public List<Chunk> split(String fullText, int maxChars) {
        List<Chunk> result = new ArrayList<>();
        if (fullText == null || fullText.isBlank()) {
            return result;
        }
        String[] lines = fullText.split("\r?\n");
        List<int[]> blocks = new ArrayList<>();   // [起始行, 結束行)
        List<String> titles = new ArrayList<>();
        int start = 0;
        String currentTitle = null;

        for (int i = 0; i < lines.length; i++) {
            if (isHeading(lines[i])) {
                if (i > start) {
                    blocks.add(new int[]{start, i});
                    titles.add(currentTitle);
                }
                start = i;
                currentTitle = lines[i].trim();
            }
        }
        if (start < lines.length) {
            blocks.add(new int[]{start, lines.length});
            titles.add(currentTitle);
        }

        if (blocks.size() <= 1) {
            // 沒有偵測到標題 → 依字數切
            return splitBySize(fullText, maxChars);
        }

        int index = 1;
        for (int b = 0; b < blocks.size(); b++) {
            StringBuilder sb = new StringBuilder();
            // 區塊的第一行是章節標題（blocks 以標題行起頭），標題已存在於
            // title 欄位，若留在 content 會被 AI 當成旁白內容念出來。
            // 因此從第二行開始取內容。
            int from = blocks.get(b)[0];
            if (from < blocks.get(b)[1] && isHeading(lines[from])) {
                from++;
            }
            for (int i = from; i < blocks.get(b)[1]; i++) {
                sb.append(lines[i]).append('\n');
            }
            String content = sb.toString().strip();
            if (content.isEmpty()) {
                continue;
            }
            String title = titles.get(b);
            result.add(new Chunk(index++, title, content));
        }
        return result;
    }

    private boolean isHeading(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String t = line.strip();
        return CN_HEADING.matcher(t).matches()
                || EN_HEADING.matcher(t).matches()
                || MD_HEADING.matcher(t).matches();
    }

    private List<Chunk> splitBySize(String text, int maxChars) {
        List<Chunk> result = new ArrayList<>();
        String[] paragraphs = text.split("\n\\s*\n");
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (String p : paragraphs) {
            if (p.isBlank()) {
                continue;
            }
            if (sb.length() + p.length() + 2 > maxChars && sb.length() > 0) {
                result.add(new Chunk(index++, "第 " + index + " 段", sb.toString().strip()));
                sb.setLength(0);
            }
            sb.append(p.strip()).append("\n\n");
        }
        if (sb.length() > 0) {
            result.add(new Chunk(index++, "第 " + index + " 段", sb.toString().strip()));
        }
        return result;
    }

    /** 供除錯：檢視某段文字前 60 字。 */
    public String preview(String s) {
        if (s == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?s)^.{0,60}").matcher(s);
        return m.find() ? m.group() : "";
    }
}
