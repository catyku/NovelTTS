package com.noveltts.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayJobStore} 的驗證。
 *
 * <p>重點是「伺服器不保留使用者資料」這條原則：
 * 工作結束後 API Key 必須被清除，工作過期後整筆連同 Key 一起消失。
 */
class GatewayJobStoreTest {

    @Test
    void 建立工作可取得() {
        GatewayJobStore store = new GatewayJobStore();
        GatewayJobStore.Job job = store.create("sk-or-v1-test", null, 5);

        assertThat(job.id()).isNotBlank();
        assertThat(job.total()).isEqualTo(5);
        assertThat(store.get(job.id())).isNotNull();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void 移除工作會清除ApiKey() {
        GatewayJobStore store = new GatewayJobStore();
        GatewayJobStore.Job job = store.create("sk-or-v1-secret", null, 3);

        store.remove(job.id());

        assertThat(store.get(job.id())).isNull();
        assertThat(job.apiKey()).isNull();
    }

    @Test
    void 結束工作時清除ApiKey() {
        GatewayJobStore store = new GatewayJobStore();
        GatewayJobStore.Job job = store.create("sk-or-v1-secret", null, 3);

        job.clearApiKey();

        assertThat(job.apiKey()).isNull();
    }

    @Test
    void toString不包含ApiKey() {
        GatewayJobStore store = new GatewayJobStore();
        GatewayJobStore.Job job = store.create("sk-or-v1-supersecret", null, 3);

        assertThat(job.toString()).doesNotContain("supersecret");
    }

    @Test
    void 快照不包含ApiKey() {
        GatewayJobStore store = new GatewayJobStore();
        GatewayJobStore.Job job = store.create("sk-or-v1-supersecret", null, 3);

        assertThat(job.snapshot().toString()).doesNotContain("supersecret");
        assertThat(job.snapshot()).containsEntry("status", "PENDING");
        assertThat(job.snapshot()).containsEntry("total", 3);
    }

    @Test
    void 未知工作回傳null() {
        GatewayJobStore store = new GatewayJobStore();

        assertThat(store.get("job_not_exist")).isNull();
    }

    @Test
    void 清除過期工作會一併丟掉Key() {
        GatewayJobStore store = new GatewayJobStore();
        GatewayJobStore.Job job = store.create("sk-or-v1-secret", null, 3);

        // 模擬過期：把 updatedAt 推到很久以前
        expire(job);

        store.cleanupExpired();

        assertThat(store.size()).isZero();
        assertThat(job.apiKey()).isNull();
    }

    @Test
    void 未過期的工作不會被清除() {
        GatewayJobStore store = new GatewayJobStore();
        GatewayJobStore.Job job = store.create("sk-or-v1-secret", null, 3);

        store.cleanupExpired();

        assertThat(store.size()).isEqualTo(1);
    }

    /** 用反射把 updatedAt 推到遠過去，模擬時間流逝。 */
    private void expire(GatewayJobStore.Job job) {
        try {
            java.lang.reflect.Field f = GatewayJobStore.Job.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.setLong(job, System.currentTimeMillis() - 60 * 60 * 1000L - 1000);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
