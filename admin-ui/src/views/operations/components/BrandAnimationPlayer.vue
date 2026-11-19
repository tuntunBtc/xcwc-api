<template>
  <div v-loading="loading" class="animation-stage">
    <canvas v-show="ready" ref="canvas" aria-label="待机动画画面"/>
    <span v-if="!loading&&!ready">{{error||'动画尚未就绪'}}</span>
  </div>
  <div class="animation-controls">
    <el-button type="primary" :disabled="!ready||starting" @click="playing?stop():play()">
      {{playing?'停止播放':'播放预览'}}
    </el-button>
    <span v-if="animation">{{animation.frames}} 帧 · {{duration}} 毫秒 · {{animation.audioAssetId?'含原声':'无原声'}}</span>
    <span v-if="ready" aria-live="off">第 {{frame+1}} / {{animation.frames}} 帧</span>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <audio v-if="audioUrl" ref="audio" :src="audioUrl" preload="auto"/>
</template>

<script setup>
import {adminPath} from '@/api/operations'

const props=defineProps({animation:{type:Object,required:true},token:{type:String,required:true}})
const canvas=ref(null), audio=ref(null), audioUrl=ref('')
const ready=ref(false), loading=ref(true), playing=ref(false), starting=ref(false), error=ref(''),frame=ref(0)
const duration=computed(()=>props.animation.durationMs??Math.round(props.animation.frames*1000/props.animation.fps))
const controller=new AbortController()
let bitmap, raf=0, starts=[], runId=0

async function fetchAsset(id){
  const result=await fetch(adminPath('/content/preview/assets/'+encodeURIComponent(id)),{
    headers:{'X-Preview-Token':props.token},
    cache:'no-store',
    signal:controller.signal
  })
  if(!result.ok)throw new Error('预览素材无法读取，请关闭后重新打开预览。')
  return result.blob()
}
function draw(index){
  const {columns,rows}=props.animation
  const width=bitmap.width/columns, height=bitmap.height/rows
  const context=canvas.value.getContext('2d')
  context.clearRect(0,0,width,height)
  context.drawImage(bitmap,(index%columns)*width,Math.floor(index/columns)*height,width,height,0,0,width,height)
  frame.value=index
}
function stop(){
  runId++
  cancelAnimationFrame(raf)
  playing.value=false
  starting.value=false
  audio.value?.pause()
}
async function play(){
  if(!ready.value||starting.value)return
  stop()
  const id=runId
  error.value=''
  starting.value=true
  try{
    if(audio.value){
      audio.value.currentTime=0
      await audio.value.play()
    }
    if(id!==runId)return
    starting.value=false
    playing.value=true
    draw(0)
    const start=performance.now()
    function tick(now){
      if(id!==runId)return
      const elapsed=now-start
      let next=frame.value
      while(next+1<starts.length&&starts[next+1]<=elapsed)next++
      if(next!==frame.value)draw(next)
      if(elapsed>=duration.value){stop();return}
      raf=requestAnimationFrame(tick)
    }
    raf=requestAnimationFrame(tick)
  }catch{
    if(id===runId){
      stop()
      error.value='原声无法播放，请检查音频格式后重试。'
    }
  }
}
onMounted(async()=>{
  try{
    const {assetId,audioAssetId,columns,rows,frames,fps,frameTimesMs}=props.animation
    const [atlas,sound]=await Promise.all([fetchAsset(assetId),audioAssetId?fetchAsset(audioAssetId):null])
    const decoded=await createImageBitmap(atlas)
    if(controller.signal.aborted){decoded.close();return}
    bitmap=decoded
    if(bitmap.width%columns||bitmap.height%rows)throw new Error('图集尺寸无法按配置整齐切分。')
    canvas.value.width=bitmap.width/columns
    canvas.value.height=bitmap.height/rows
    starts=frameTimesMs??Array.from({length:frames},(_,index)=>index*1000/fps)
    if(sound)audioUrl.value=URL.createObjectURL(sound)
    draw(0)
    ready.value=true
  }catch(e){if(!controller.signal.aborted)error.value=e.message||'动画预览加载失败。'}
  finally{loading.value=false}
})
onBeforeUnmount(()=>{
  controller.abort()
  stop()
  bitmap?.close()
  if(audioUrl.value)URL.revokeObjectURL(audioUrl.value)
})
</script>

<style scoped>
.animation-stage{min-height:260px;background:#f0f6ee;border:1px solid #dce7d9;border-radius:8px;display:grid;place-items:center;padding:16px}
canvas{width:auto;max-width:100%;max-height:350px;object-fit:contain}
.animation-controls{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin:16px 0}
</style>
