package com.noveltts.ai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayMergeJobStore} 的驗證。
 *
 * <p>重點有兩個：
 *
 * <ul>
 *   <li>片段依上傳順序保存 —— 合併順序錯了，音檔順序就錯了</li>
 *   <li>移除工作時暫存檔必須被刪掉 —— 伺服器不該留著使用者的音檔副本</li>
 * </ul>
 */
class GatewayMergeJobStoreTest {

    @Test
    void 建立工作可取得() {
        GatewayMergeJobStore store = new GatewayMergeJobStore();
        GatewayMergeJobStore.MergeJob job = store.create("MERGE", 10);

        assertThat(job.id()).isNotBlank();
        assertThat(job.expectedTotal()).isEqualTo(10);
        assertThat(job.status()).isEqualTo("PENDING");
        assertThat(store.get(job.id())).isNotNull();
    }

    @Test
    void 片段依上傳順序保存() throws Exception {
        GatewayMergeJobStore store = new GatewayMergeJobStore();
        GatewayMergeJobStore.MergeJob job = store.create("MERGE", 3);

        store.appendPart(job, "a.mp3", new ByteArrayInputStream("AAA".getBytes()));
        store.appendPart(job, "b.mp3", new ByteArrayInputStream("BBB".getBytes()));
        store.appendPart(job, "c.mp3", new ByteArrayInputStream("CCC".getBytes()));

        assertThat(job.uploaded()).isEqualTo(3);
        assertThat(job.partNames()).containsExactly("a.mp3", "b.mp3", "c.mp3");
        assertThat(job.partPaths()).hasSize(3);
        // 順序不能亂 —— 合併順序錯了音檔順序就錯了
        assertThat(Files.readString(job.partPaths().get(0))).isEqualTo("AAA");
        assertThat(Files.readString(job.partPaths().get(2))).isEqualTo("CCC");
    }

    @Test
    void 移除工作會刪掉暫存檔() throws Exception {
        GatewayMergeJobStore store = new GatewayMergeJobStore();
        GatewayMergeJobStore.MergeJob job = store.create("MERGE", 1);
        store.appendPart(job, "a.mp3", new ByteArrayInputStream("data".getBytes()));
        Path dir = job.dir();
        assertThat(Files.exists(dir)).isTrue();

        store.remove(job.id());

        assertThat(store.get(job.id())).isNull();
        // 伺服器不該留著使用者的音檔副本
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void 清除過期工作會刪掉暫存檔() throws Exception {
        GatewayMergeJobStore store = new GatewayMergeJobStore();
        GatewayMergeJobStore.MergeJob job = store.create("MERGE", 1);
        store.appendPart(job, "a.mp3", new ByteArrayInputStream("data".getBytes()));
        Path dir = job.dir();

        expire(job);

        store.cleanupExpired();

        assertThat(store.size()).isZero();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void 未過期的工作不會被清除() {
        GatewayMergeJobStore store = new GatewayMergeJobStore();
        GatewayMergeJobStore.MergeJob job = store.create("MERGE", 1);

        store.cleanupExpired();

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void 已取回的結果會很快清除() throws Exception {
        GatewayMergeJobStore store = new GatewayMergeJobStore();
        GatewayMergeJobStore.MergeJob job = store.create("MERGE", 1);
        Path dir = job.dir();

        job.markDelivered();
        // 從現在往回推 40 秒 —— 超過 30 秒的寬限期
        expire(job, 40_000L);

        store.cleanupExpired();

        // 結果都取回了，暫存檔沒必要留著
        assertThat(store.size()).isZero();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void 快照不含暫存路徑() {
        GatewayMergeJobStore store = new GatewayMergeJobStore();
        GatewayMergeJobStore.MergeJob job = store.create("MERGE", 5);

        // 快照會回給前端，不該洩漏伺服器的檔案系統路徑
        assertThat(job.snapshot().toString()).doesNotContain("/tmp/");
        assertThat(job.snapshot()).containsEntry("status", "PENDING");
        assertThat(job.snapshot()).containsEntry("uploaded", 0);
    }

    /** 用反射把 updatedAt 推到遠過去，模擬時間流逝。 */
    private void expire(GatewayMergeJobStore.MergeJob job) {
        expire(job, 60 * 60 * 1000L + 1000L);
    }

    private void expire(GatewayMergeJobStore.MergeJob job, long agoMs) {
        try {
            java.lang.reflect.Field f = GatewayMergeJobStore.MergeJob.class
                    .getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.setLong(job, System.currentTimeMillis() - agoMs);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
