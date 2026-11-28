// Each editor permits one background frame download. Leave the other read slots for playback.
export function createFrameQueue() {
  const lifetime=new AbortController()
  let tail=Promise.resolve(), playbackGate=null, releasePlayback=null
  function pause() {
    if (!playbackGate) playbackGate=new Promise(resolve=>{releasePlayback=resolve})
  }
  function resume() {
    releasePlayback?.()
    releasePlayback=null
    playbackGate=null
  }
  function delay(ms,signal) {
    return new Promise((resolve,reject)=>{
      signal.throwIfAborted()
      function cancel(){clearTimeout(timer);reject(signal.reason)}
      const timer=setTimeout(()=>{signal.removeEventListener('abort',cancel);resolve()},ms)
      signal.addEventListener('abort',cancel,{once:true})
    })
  }
  async function load(url,componentSignal) {
    const signal=AbortSignal.any([lifetime.signal,componentSignal])
    for(let attempt=0;attempt<7;attempt++) {
      while(playbackGate)await playbackGate
      signal.throwIfAborted()
      let response
      try {
        response=await fetch(url,{signal:AbortSignal.any([signal,AbortSignal.timeout(30000)]),priority:'low'})
        if(response.ok) return await response.blob()
      } catch(error) {
        if(signal.aborted || attempt===6)throw error
      }
      if(response && ![429,500,502,503,504].includes(response.status))throw Error('图片读取失败')
      await response?.body?.cancel()
      if(attempt===6)break
      const header=response?.headers.get('Retry-After')
      let waitMs=Math.min(4000,500*2**attempt)
      if(header){
        const advised=/^\d+$/.test(header)?Number(header)*1000:Date.parse(header)-Date.now()
        if(Number.isFinite(advised))waitMs=Math.max(waitMs,advised)
      }
      await delay(waitMs,signal)
    }
    throw Error('图片暂时无法读取')
  }
  return {
    request(url,signal){
      const result=tail.then(()=>load(url,signal))
      tail=result.catch(()=>{})
      return result
    },
    pause,resume,
    destroy(){lifetime.abort();resume()}
  }
}
