/* 角色音色 */
const NOVEL_ID = novelId();

async function saveCharacter(id) {
  const select = document.querySelector('.voice-select[data-character-id="' + id + '"]');
  const instr = document.querySelector('.instr-input[data-character-id="' + id + '"]');
  const body = {
    ttsVoiceId: parseInt(select.value, 10),
    ttsInstructions: instr.value
  };
  try {
    await api('/api/characters/' + id, { method: 'PUT', body: JSON.stringify(body) });
    instr.style.borderColor = '#2f9e44';
    setTimeout(() => { instr.style.borderColor = ''; }, 800);
  } catch (e) {
    alert('儲存失敗: ' + e.message);
  }
}

document.querySelectorAll('.save-char').forEach(btn => {
  btn.addEventListener('click', () => saveCharacter(btn.dataset.characterId));
});

document.getElementById('autoVoiceBtn').addEventListener('click', async () => {
  const msg = document.getElementById('autoMsg');
  msg.textContent = '指派中…';
  try {
    const result = await api('/api/novels/' + NOVEL_ID + '/characters/auto-voice?overwrite=true', { method: 'POST' });
    msg.textContent = '已指派 ' + result.count + ' 個角色';
    location.reload();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});
