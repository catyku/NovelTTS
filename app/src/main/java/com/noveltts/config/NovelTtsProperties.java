package com.noveltts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noveltts")
public class NovelTtsProperties {

    /** API Key 加密主金鑰（Base64, 32 bytes）。未設定時 Provider 設定不可用。 */
    private String masterKey = "";

    /** 音檔儲存根目錄。 */
    private String storageRoot = "./storage";

    /** 章節切分最大字數。 */
    private int chapterMaxChars = 3000;

    /** TTS 併發上限。 */
    private int ttsConcurrency = 3;

    /** 單次 TTS group 字數上限。 */
    private int ttsGroupMaxChars = 3000;

    /** 全域語氣指令（送給 TTS 的頂層 instructions）。 */
    private String globalInstructions = "小說有聲書風格，發音清晰自然";

    /** 旁白預設音色 voice_id。 */
    private String narratorVoiceId = "Kore";

    /** 未指派角色音色的預設 voice_id。 */
    private String defaultVoiceId = "Kore";

    /** 是否自動為角色指派音色。 */
    private boolean autoAssignVoice = true;

    /** TTS 音訊取樣率（Gemini TTS 為 24000 Hz / 16-bit mono）。 */
    private int audioSampleRate = 24000;

    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public int getChapterMaxChars() { return chapterMaxChars; }
    public void setChapterMaxChars(int chapterMaxChars) { this.chapterMaxChars = chapterMaxChars; }
    public int getTtsConcurrency() { return ttsConcurrency; }
    public void setTtsConcurrency(int ttsConcurrency) { this.ttsConcurrency = ttsConcurrency; }
    public int getTtsGroupMaxChars() { return ttsGroupMaxChars; }
    public void setTtsGroupMaxChars(int ttsGroupMaxChars) { this.ttsGroupMaxChars = ttsGroupMaxChars; }
    public String getGlobalInstructions() { return globalInstructions; }
    public void setGlobalInstructions(String globalInstructions) { this.globalInstructions = globalInstructions; }
    public String getNarratorVoiceId() { return narratorVoiceId; }
    public void setNarratorVoiceId(String narratorVoiceId) { this.narratorVoiceId = narratorVoiceId; }
    public String getDefaultVoiceId() { return defaultVoiceId; }
    public void setDefaultVoiceId(String defaultVoiceId) { this.defaultVoiceId = defaultVoiceId; }
    public boolean isAutoAssignVoice() { return autoAssignVoice; }
    public void setAutoAssignVoice(boolean autoAssignVoice) { this.autoAssignVoice = autoAssignVoice; }
    public int getAudioSampleRate() { return audioSampleRate; }
    public void setAudioSampleRate(int audioSampleRate) { this.audioSampleRate = audioSampleRate; }

    /**
     * 主金鑰是否已設定。主金鑰必須跨重啟固定，否則已加密的 API Key 無法解密。
     */
    public boolean isMasterKeyConfigured() {
        if (masterKey == null || masterKey.isBlank()) {
            return false;
        }
        try {
            return java.util.Base64.getDecoder().decode(masterKey.trim()).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
