// 独立只读页面。前端窗口将本目录加入分包并确认 API_BASE 后接入。
const { API_BASE } = require('../../utils/config');
Page({
  data: { token: '', error: '', busy: false, content: null, appearanceIndex: 0, expression: 'normal', image: '', background: '', appearanceNames: [], expressions: ['normal','happy','hungry','thirsty','lonely','shy','angry','crying','confused','veryHappy'] },
  onLoad() { this.sequence = 0; this.tempFiles = []; },
  tokenInput(e) { this.setData({ token: e.detail.value }); },
  load() {
    if (!API_BASE) return this.setData({ error: '当前环境尚未配置接口地址，请联系开发人员。' });
    if (!/^[0-9a-f]{64}$/.test(this.data.token)) return this.setData({ error: '请输入后台生成的预览凭据。' });
    const seq = ++this.sequence; this.setData({ busy: true, error: '' });
    wx.request({ url: API_BASE + '/api/content/preview', header: { 'X-Preview-Token': this.data.token }, success: r => {
      if (seq !== this.sequence) return;
      if (r.statusCode !== 200) return this.setData({ error: '预览凭据无效或已过期，请重新生成。' });
      this.setData({ busy: false, content: r.data, appearanceIndex: 0, appearanceNames: r.data.appearances.map(a => a.name) }); this.render();
    }, fail: () => { if (seq === this.sequence) this.setData({ error: '无法连接预览服务，请检查网络。' }); }, complete: () => { if (seq === this.sequence) this.setData({ busy: false }); } });
  },
  selectAppearance(e) { this.setData({ appearanceIndex: Number(e.detail.value) }); this.render(); },
  selectExpression(e) { this.setData({ expression: this.data.expressions[Number(e.detail.value)] }); this.render(); },
  render() {
    const seq = ++this.sequence, content = this.data.content, look = content.appearances[this.data.appearanceIndex];
    this.setData({ image: '', background: '' }); if (!look) return;
    const character = content.characters.find(c => c.id === look.characterId), fallback = content.pages.find(p => p.id === 'nestDefault');
    for (const [key, asset] of [['image', look.slots[this.data.expression]], ['background', character.backgroundAssetId || (fallback && fallback.backgroundAssetId)]]) {
      if (!asset) continue;
      wx.downloadFile({ url: API_BASE + '/api/content/preview/assets/' + asset, header: { 'X-Preview-Token': this.data.token }, success: r => {
        if (r.tempFilePath) this.tempFiles.push(r.tempFilePath);
        if (seq !== this.sequence) return;
        if (r.statusCode === 200) this.setData({ [key]: r.tempFilePath }); else this.setData({ error: '素材不可用或凭据已过期。' });
      }, fail: () => { if (seq === this.sequence) this.setData({ error: '素材加载失败，可重新预览。' }); } });
    }
  },
  onHide() { this.sequence++; this.setData({ token: '', content: null, image: '', background: '', busy: false }); },
  onUnload() { this.sequence++; const fs = wx.getFileSystemManager(); for (const filePath of this.tempFiles) fs.unlink({ filePath, fail() {} }); }
});
