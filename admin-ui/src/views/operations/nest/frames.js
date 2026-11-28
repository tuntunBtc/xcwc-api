  function mediaEvent(video, eventName, start) {
    return new Promise((resolve, reject) => {
      const finish = (error) => {
        clearTimeout(timer);
        video.removeEventListener(eventName, success);
        video.removeEventListener('error', failure);
        if (error) reject(error);
        else resolve();
      };
      const success = () => finish();
      const failure = () => finish(new Error('无法读取视频，请检查格式或素材访问权限'));
      const timer = setTimeout(() => finish(new Error('视频读帧超时，请重试')), 30000);
      video.addEventListener(eventName, success);
      video.addEventListener('error', failure);
      try {
        start();
      } catch (error) {
        finish(error);
      }
    });
  }

  async function withVideo(url, read) {
    if (!url) throw new Error('请选择视频');
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'auto';
    video.crossOrigin = 'anonymous';
    try {
      await mediaEvent(video, 'loadeddata', () => {
        video.src = url;
        video.load();
      });
      if (!video.videoWidth || !video.videoHeight || !Number.isFinite(video.duration) || video.duration <= 0) {
        throw new Error('无法识别视频尺寸或时长');
      }
      return await read(video);
    } finally {
      video.pause();
      video.removeAttribute('src');
      video.load();
    }
  }

  async function capture(video, edge) {
    if (edge !== 'first' && edge !== 'last') throw new Error('请选择首帧或尾帧');
    const target = edge === 'last' ? Math.max(0, video.duration - 0.000001) : 0;
    if (video.currentTime !== target) {
      await mediaEvent(video, 'seeked', () => { video.currentTime = target; });
    }
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('浏览器未能创建画布');
    context.drawImage(video, 0, 0);
    return new Promise((resolve, reject) => {
      canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('生成 PNG 失败')), 'image/png');
    });
  }

  async function imageSize(blob) {
    const url = URL.createObjectURL(blob);
    try {
      const img = new Image();
      img.src = url;
      await img.decode();
      return {width: img.naturalWidth, height: img.naturalHeight};
    } catch {
      throw new Error('图片无法解码，请重新选择');
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  const dimensions = video => ({width: video.videoWidth, height: video.videoHeight});
  export const frames = {
    imageSize,
    videoSize: url => withVideo(url, dimensions),
    extract: (url, edge) => withVideo(url, video => capture(video, edge)),
    extractPair: url => withVideo(url, async video => ({
      size: dimensions(video),
      first: await capture(video, 'first'),
      last: await capture(video, 'last'),
    })),
  };
