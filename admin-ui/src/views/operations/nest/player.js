  const names = ['清醒待机', '趴下入睡', '睡眠待机', '触摸唤醒', '摸头回应', '吃饭', '喝水', '生气'];
  const instructions = ['触摸小鸟可摸头，静置 30 秒后入睡', '此时触摸，会在趴稳后唤醒', '触摸睡着的小鸟，唤醒它', '正在醒来，即将回到清醒待机', '摸头回应结束后回到清醒待机', '吃完后回到清醒待机', '喝完后回到清醒待机', '生气回应结束后回到清醒待机'];

  export const createNestPlayer = (container, { birdId, actionCodes, resolveClip, title = '桂花鸟', soundEnabled = [], extraActions = [] }) => {
    const view = document.createElement('div');
    view.className = 'nest-player';
    const icon = key => '<img src="' + new URL('./hud/'+key+'.png',import.meta.url).href + '" alt="" draggable="false">';
    view.innerHTML = `<div class="np-frame"><i class="np-keys" aria-hidden="true"></i><div class="np-glass">
      <video class="np-film" playsinline muted preload="metadata"></video>
      <button type="button" class="np-touch" aria-label="触摸小鸟"></button>
      <div class="np-hud" aria-hidden="true">
        <div class="np-system"><strong>12:48</strong><span class="np-system-right">4G <b class="np-battery"></b></span></div>
        <span class="np-camera"></span>
        <div class="np-identity"><img class="np-avatar" src="${new URL('./hud/avatar.webp',import.meta.url).href}" alt=""><span class="np-who"><b></b><small>⌖ 我的小窝</small></span><i class="np-split"></i><span class="np-recovery">${icon('spark-dim')}同步中</span><span class="np-points">${icon('feather')}<b>39</b><small>叽分</small></span></div>
        <div class="np-capsule"><i></i><b></b></div>
        <div class="np-stats"><span>${icon('hunger')}饱食 0%</span><span>${icon('water')}水分 0%</span><span>${icon('mood')}心情 100%</span></div>
        <div class="np-tools"><span class="np-gift">${icon('supply')}</span>${icon('checkin')}${icon('ar')}${icon('video')}<i class="np-diary"><i></i></i></div>
        <div class="np-dock">${icon('home')}${icon('shop')}</div><span class="np-home"></span>
      </div>
      <button type="button" class="np-retry" hidden>重新播放</button>
      </div></div>
      <div class="np-monitor"><div class="np-state" role="status"><span></span></div>
      <button type="button" class="np-sound" aria-label="开启原声" aria-pressed="false">原声关</button></div>
      <p class="np-hint" aria-live="polite"></p>
      <div class="np-controls"><button type="button" class="np-touch-btn">摸摸头</button><button type="button" class="np-sleep">立即入睡</button></div>
      <div class="np-controls np-extra-controls np-food" role="group" aria-label="饮食照料"></div>
      <div class="np-controls np-extra-controls np-emotion" role="group" aria-label="情绪表达"></div>`;
    container.replaceChildren(view);
    const find = selector => view.querySelector(selector);
    find('.np-who b').textContent = title;
    const video = find('.np-film');
    video.setAttribute('aria-label', title + '互动试玩');
    const touch = find('.np-touch-btn');
    const touchArea = find('.np-touch');
    const sound = find('.np-sound');
    const sleep = find('.np-sleep');
    const retry = find('.np-retry');
    const hint = find('.np-hint');
    let awakeSince = Date.now();
    let phase = 0;
    let previewOnce = false;
    let generation = 0;
    let timer;
    let wakePending = false;
    let audible = false;
    let disposed = false;
    let suspended = false;
    const clipUrl = slot => {
      const code = actionCodes[slot];
      return code ? resolveClip(birdId, code) : null;
    };
    const customNames = new Map();
    const foodSlots = new Set([5,6]);
    for (const {slot, label, group} of extraActions) {
      if (Number.isInteger(slot) && slot >= 8 && typeof label === 'string' && label.trim()) {
        customNames.set(slot, label.trim());
        if (group === 'food') foodSlots.add(slot);
      }
    }
    const extras = [[5, '喂食'], [6, '喂水'], [7, '模拟生气'], ...customNames].map(([slot, label]) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = clipUrl(slot) ? label : label + ' · 待配置';
      button.onclick = () => {
        awakeSince = Date.now();
        show(slot);
      };
      find(foodSlots.has(slot) ? '.np-food' : '.np-emotion').appendChild(button);
      return {button, slot};
    });

    function updateSound() {
      video.muted = !audible || soundEnabled[phase] === false;
      sound.textContent = audible ? '原声开' : '原声关';
      sound.setAttribute('aria-pressed', String(audible));
      sound.setAttribute('aria-label', audible ? '关闭原声' : '开启原声');
    }

    function scheduleSleep() {
      clearTimeout(timer);
      if (!previewOnce && phase === 0 && !sleep.disabled && !document.hidden && !video.paused && !disposed && !suspended) {
        timer = setTimeout(() => show(1), Math.max(0, awakeSince + 30000 - Date.now()));
      }
    }

    function playbackError(autoplayBlocked) {
      clearTimeout(timer);
      retry.hidden = false;
      retry.textContent = autoplayBlocked ? '开始播放' : '重新播放';
      hint.textContent = autoplayBlocked ? '点「开始播放」启动试玩' : '视频播放失败，请重试或更换这个动作的素材';
    }

    function play() {
      const current = generation;
      if (disposed || suspended || document.hidden || !video.hasAttribute('src')) return;
      video.play().catch(error => {
        if (!disposed && current === generation && error.name !== 'AbortError') {
          playbackError(error.name === 'NotAllowedError');
        }
      });
    }

    function show(next, single = false) {
      if (disposed) return;
      clearTimeout(timer);
      generation++;
      phase = next;
      previewOnce = single;
      wakePending = false;
      view.dataset.phase = String(next);
      video.loop = !single && (next === 0 || next === 2);
      updateSound();
      find('.np-state span').textContent = names[next] || customNames.get(next);
      hint.textContent = single ? '单条试看，播完回到清醒待机' : instructions[next] || '情绪回应结束后回到清醒待机';
      const source = clipUrl(next);
      sleep.disabled = single || next !== 0 || !source || ![1, 2, 3].every(clipUrl);
      extras.forEach(({button, slot}) => {
        button.disabled = !clipUrl(slot);
      });
      touch.disabled = single || next > 2 || !source || !clipUrl(next === 0 ? 4 : 3);
      touchArea.disabled = touch.disabled;
      touch.textContent = next === 1 || next === 2 || next === 3 ? '唤醒小鸟' : '摸摸头';
      retry.hidden = true;
      if (!source) {
        video.pause();
        video.removeAttribute('poster');
        video.removeAttribute('src');
        video.load();
        hint.textContent = '请先配置' + (names[next] || customNames.get(next)) + '视频';
        return;
      }
      if (/(?:^|\/)media\/0[1-5]\.mp4$/.test(source)) {
        video.poster = source.replace(/\.mp4$/, '.jpg');
      } else {
        video.removeAttribute('poster');
      }
      video.src = source;
      play();
    }

    function tap() {
      if (phase === 1) {
        if (!wakePending) {
          awakeSince = Date.now();
          wakePending = true;
          hint.textContent = '已收到触摸，趴稳后会自动唤醒';
        }
      } else if (phase === 0 || phase === 2) {
        awakeSince = Date.now();
        show(phase === 0 ? 4 : 3);
      }
    }

    video.onended = () => {
      if (previewOnce) awakeSince = Date.now();
      show(!previewOnce && phase === 1 ? (wakePending ? 3 : 2) : 0);
    };
    video.onerror = () => { if (video.hasAttribute('src')) playbackError(false); };
    video.onplaying = () => { retry.hidden = true; scheduleSleep(); };
    touch.onclick = touchArea.onclick = tap;
    sleep.onclick = () => show(1);
    sound.onclick = () => {
      audible = !audible;
      updateSound();
      if (!retry.hidden) play();
    };
    retry.onclick = () => {
      if (video.error) video.load();
      if (phase === 0) awakeSince = Date.now();
      play();
    };
    function visibility() {
      if (document.hidden || suspended) {
        clearTimeout(timer);
        video.pause();
      } else {
        awakeSince = Date.now();
        play();
      }
    }
    document.addEventListener('visibilitychange', visibility);
    show(0);
    return {
      previewAction(code) {
        const slot = actionCodes.indexOf(code);
        if (disposed || slot < 0 || !clipUrl(slot)) return false;
        show(slot, true);
        return true;
      },
      suspend() { suspended = true; visibility(); },
      resume() { suspended = false; visibility(); },
      destroy() {
        disposed = true;
        clearTimeout(timer);
        document.removeEventListener('visibilitychange', visibility);
        video.onended = video.onerror = video.onplaying = null;
        video.pause();
        video.removeAttribute('src');
        video.load();
        view.remove();
      }
    };
  };
