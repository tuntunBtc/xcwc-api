<template>
<section class="nest-admin">
  <header><el-button link @click="back">‹ 品牌 IP 管理</el-button><h2>{{ bird?.name || '小鸟 IP' }} · 小窝互动</h2><p>测试环境 · 草稿 {{ version }} 版 · 已发布 {{ published || '无' }}</p></header>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <div v-if="!loaded"><el-button :loading="loading" @click="load">重新读取</el-button></div>
  <template v-else>
    <div v-if="recovery" class="bar"><span>此账号在本浏览器有未同步的修改。</span><el-button @click="restoreBackup" :disabled="!editable">恢复未同步内容</el-button><el-button @click="discardBackup">放弃备份</el-button></div>
    <div class="bar"><span role="status">{{ status }}</span><el-button v-if="dirty && error" :disabled="busy" @click="retrySave">重试保存</el-button><el-button v-if="conflict" :disabled="busy" @click="readLatest">读取最新草稿</el-button><el-button v-if="canBrand('publish')" type="primary" :disabled="busy || dirty || conflict || !!recovery || !version || version===published" @click="publish">发布整只鸟</el-button></div>
    <div class="editor"><fieldset class="editing-fields" :disabled="!!recovery || conflict || !loaded"><section v-for="g in groups" :key="g.id" class="group"><div class="group-title"><h3>{{g.name}}</h3><el-button v-if="g.id!=='daily' && editable" :disabled="busy" @click="newAction(g.id)">新增{{g.short}}动作</el-button></div>
      <article v-for="a in actions.filter(item=>item.group===g.id)" :key="a.actionCode">
        <div class="row"><div><strong>{{a.name}}</strong><small>动作编码　<code>{{a.actionCode}}</code></small></div>
          <el-switch v-model="a.enabled" active-text="启用" :disabled="!editable || busy" @change="changed"/>
        </div>
        <div class="toolbar"><el-button v-if="editable" :disabled="busy || !uploadable" @click="pick(a,'video')">{{a.videoId?'更换视频':'上传视频'}}</el-button><el-button v-if="editable" :disabled="busy || !library.length" @click="openLibrary(a)">从已用素材选择</el-button>
          <el-button :disabled="!a.videoUrl" @click="watchClip(a)">单条试看</el-button>
          <el-checkbox v-model="a.sound" :disabled="!editable || busy" @change="changed">原声</el-checkbox>
          <template v-if="editable && !builtinCodes.includes(a.actionCode)"><el-button link :disabled="busy" @click="rename(a)">改名</el-button><el-button link type="danger" :disabled="busy" @click="remove(a)">删除动作</el-button></template>
          <el-button v-if="editable && a.videoId" link :disabled="busy" @click="clearVideo(a)">移除视频</el-button>
        </div>
        <details><summary>素材详情</summary><small>{{a.videoId ? '视频素材 ID　'+a.videoId : '视频待配置'}}</small><small v-if="a.videoCode">内容编码　{{a.videoCode}}</small></details>
        <div class="frames"><div v-for="e in edges" :key="e.key"><FrameThumbnail v-if="a[e.key+'Url']" :key="a[e.key+'Url']" :url="mediaPath(a[e.key+'Url'])" :label="a.name+e.name" :queue="frameQueue"/><span v-else class="empty">待配置</span><div><b>{{e.name}}</b><div class="frame-links"><button v-if="editable" :disabled="busy || !uploadable" @click="pick(a,e.key)">{{a[e.key+'Id']?'替换':'上传'}}</button><button v-if="editable && a.videoUrl" :disabled="busy || !uploadable" @click="extract(a,e)">提取</button><button v-if="a[e.key+'Url']" @click="download(a,e)">下载</button></div></div></div></div>
      </article>
    </section></fieldset><aside><h3>小窝试玩</h3><div ref="monitor"></div></aside></div>
  </template>
  <input ref="fileInput" hidden type="file" @change="fromFile">
  <el-dialog v-model="libraryOpen" title="选用已有测试素材"><el-button v-for="item in library" :key="item.videoId" :disabled="busy" @click="useExisting(item)">{{item.name}}</el-button></el-dialog>
  <el-dialog v-model="previewOpen" :title="previewTitle" destroy-on-close @closed="closeClip"><video v-if="previewOpen" :src="previewUrl" :muted="previewMuted" controls autoplay playsinline class="single" @canplay="frameQueue.resume()" @error="frameQueue.resume()"/></el-dialog>
</section>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import {ElMessageBox} from 'element-plus'
import {api} from '@/api/operations'
import {canBrand,brandDetail} from '@/api/brand-ip'
import {nestTest} from '@/api/nest-test'
import useUserStore from '@/store/modules/user'
import {createNestPlayer} from './player'
import {frames} from './frames'
import FrameThumbnail from './FrameThumbnail.vue'
import {createFrameQueue} from './frameQueue'
import './player.css'
const route=useRoute(),router=useRouter(),user=useUserStore()
const birdId=String(route.params.id),endpoint='/brand-ips/'+encodeURIComponent(birdId)+'/nest-interactions'
const builtinCodes=['awake_idle','fall_asleep','sleep_idle','wake_up','head_pat','eat','drink','angry']
const names=['清醒待机','趴下入睡','睡眠待机','触摸唤醒','摸头回应','吃饭','喝水','生气']
const groups=[{id:'daily',name:'日常陪伴'},{id:'food',name:'饮食照料',short:'饮食'},{id:'emotion',name:'情绪表达',short:'情绪'}]
const edges=[{key:'firstFrame',edge:'first',name:'首帧'},{key:'lastFrame',edge:'last',name:'尾帧'}]
const editable=canBrand('edit'),uploadable=editable && user.permissions.includes('content:edit')
const nestErrors={
  NEST_VERSION_CONFLICT:'其他管理员已修改这份草稿，请核对最新版本。',
  NEST_ACTIVE_MEDIA_INCOMPLETE:'启用中的动作需要视频、首帧和尾帧，请补齐或停用该动作。',
  NEST_ASSET_NOT_READY:'选用的素材尚未在测试素材库就绪，请重新上传。',
  NEST_ASSET_KIND_INVALID:'视频和图片类型不匹配，请核对素材。',
  NEST_FRAME_DIMENSIONS_INVALID:'首尾帧图片尺寸须与该视频一致。',
  NEST_ACTION_NAME_INVALID:'动作名称应唯一，且为 1 至 20 个字。',
  NEST_DRAFT_EMPTY:'请先配置并保存互动草稿。',
  OSS_UPLOAD_NOT_CONFIGURED:'测试 OSS 上传服务尚未配置。'
}
const reason=e=>nestErrors[e.code] || e.message+(e.code?'（'+e.code+'）':'')
const blank=(actionCode,name,group)=>({actionCode,name,group,enabled:false,sound:true,videoId:null,firstFrameId:null,lastFrameId:null})
// Cookies and private server origins stay out of the media player. The local test gateway
// only forwards content-addressed paths to the same isolated backend as the editor.
function mediaPath(url) {
  if(!url)return ''
  try {
    const path=new URL(url,location.origin).pathname
    return /^\/test\/bird\/v1\/oss\/uploads\/[0-9a-f]{32}\.(?:mp4|png|jpg|jpeg|webp)$/.test(path)?path:''
  } catch {return ''}
}
const base=()=>builtinCodes.map((code,i)=>blank(code,names[i],i<5?'daily':i<7?'food':'emotion'))
const arrange=rows=>[...base().map(a=>rows.find(r=>r.actionCode===a.actionCode)||a),...rows.filter(a=>!builtinCodes.includes(a.actionCode))]
const actions=ref([]),bird=ref(),version=ref(0),published=ref(0),loading=ref(false),loaded=ref(false),status=ref(''),error=ref(''),dirty=ref(false),saving=ref(false),processing=ref(false),publishing=ref(false),conflict=ref(false),recovery=ref(null)
const busy=computed(()=>processing.value || publishing.value || saving.value)
const fileInput=ref(),monitor=ref(),previewOpen=ref(false),previewUrl=ref(''),previewTitle=ref(''),previewMuted=ref(false),libraryOpen=ref(false)
const frameQueue=createFrameQueue()
let player,timer,inFlight,mediaTarget,revision=0,gone=false
const backupKey='nest-server-draft-v1:'+import.meta.env.VITE_APP_BASE_API+':'+user.id+':'+birdId
const library=computed(()=>Array.from(new Map(actions.value.filter(a=>a.videoId).map(a=>[a.videoId,a])).values()))
const persisted=()=>actions.value.map(({actionCode,name,group,enabled,sound,videoId,firstFrameId,lastFrameId})=>({actionCode,name,group,enabled,sound,videoId,firstFrameId,lastFrameId}))
function backup(){try{sessionStorage.setItem(backupKey,JSON.stringify({version:version.value,actions:actions.value}))}catch{status.value='浏览器暂时无法备份未同步的修改'}}
function refreshPlayer(){
  player?.destroy()
  if(!monitor.value || gone)return
  const rows=actions.value
  player=createNestPlayer(monitor.value,{birdId,title:bird.value.name,actionCodes:rows.map(a=>a.actionCode),resolveClip:(id,code)=>id===birdId && mediaPath(rows.find(a=>a.actionCode===code && a.enabled)?.videoUrl) || null,soundEnabled:rows.map(a=>a.sound),extraActions:rows.slice(8).map((a,i)=>({slot:i+8,label:a.name,group:a.group}))})
}
async function load(){
  if(!nestTest || loading.value)return
  loading.value=true;error.value=''
  try{
    const [item,draft]=await Promise.all([brandDetail(birdId),api(endpoint)])
    bird.value=item;actions.value=arrange(draft.actions);version.value=draft.version;published.value=draft.publishedVersion
    loaded.value=true;dirty.value=false;conflict.value=false
    const saved=sessionStorage.getItem(backupKey)
    recovery.value=saved?JSON.parse(saved):null
    status.value='已读取服务端草稿';await nextTick();refreshPlayer()
  }catch(e){error.value='读取测试草稿失败。'+e.message}finally{loading.value=false}
}
function changed(){
  revision++;dirty.value=true;error.value='';status.value='正在等待自动保存…';backup();refreshPlayer()
  clearTimeout(timer);timer=setTimeout(()=>save().catch(()=>{}),450)
}
function save(){
  if(inFlight)return inFlight
  if(!dirty.value || !editable || conflict.value || recovery.value)return Promise.resolve()
  saving.value=true
  inFlight=(async()=>{
    while(dirty.value){
      const at=revision,snapshot=persisted()
      const result=await api(endpoint,{method:'PUT',body:{expectedVersion:version.value,actions:snapshot}})
      version.value=result.version;published.value=result.publishedVersion
      if(at===revision){dirty.value=false;sessionStorage.removeItem(backupKey);status.value='已自动保存服务端草稿'}else backup()
    }
  })().catch(e=>{error.value='草稿未同步。'+reason(e);conflict.value=/CONFLICT|VERSION/i.test(e.code||'');backup();throw e})
  .finally(()=>{inFlight=null;saving.value=false})
  return inFlight
}
function retrySave(){error.value='';return save().catch(()=>{})}
async function readLatest(){await load()}
function restoreBackup(){
  if(!recovery.value)return
  if(recovery.value.version!==version.value){error.value='备份与服务器版本不同。请先核对并下载备份，再以服务器最新草稿继续编辑。';downloadBackup();return}
  actions.value=arrange(recovery.value.actions);recovery.value=null;changed()
}
function downloadBackup(){const url=URL.createObjectURL(new Blob([JSON.stringify(recovery.value,null,2)],{type:'application/json'}));saveLink(url,'互动草稿备份.json');setTimeout(()=>URL.revokeObjectURL(url),3000)}
function discardBackup(){sessionStorage.removeItem(backupKey);recovery.value=null;error.value=''}
async function confirm(text){try{await ElMessageBox.confirm(text,'请确认',{confirmButtonText:'确认',cancelButtonText:'取消'});return true}catch{return false}}
async function publish(){
  if(!canBrand('publish') || busy.value || !await confirm('将当前整套互动发布到测试环境？停用的动作不会出现在发布结果中。'))return
  publishing.value=true
  try{clearTimeout(timer);await save();if(dirty.value || conflict.value)return
    const incomplete=actions.value.filter(a=>a.enabled && (!a.videoId || !a.firstFrameId || !a.lastFrameId));
    if(incomplete.length){error.value='请先为启用的动作补齐视频和首尾帧，或将其停用：'+incomplete.map(a=>a.name).join('、');return}
    const result=await api(endpoint+'/publish',{method:'POST',body:{expectedVersion:version.value}})
    error.value='';version.value=result.version;published.value=result.publishedVersion;status.value='整只鸟的互动已发布到测试环境'
  }catch(e){error.value='发布失败。'+reason(e)}
  finally{publishing.value=false}
}
function clearVideo(a){Object.assign(a,{videoId:null,videoUrl:null,videoCode:null,firstFrameId:null,lastFrameId:null,firstFrameUrl:null,lastFrameUrl:null,enabled:false});changed()}
async function askName(label,initial=''){
  try{const {value}=await ElMessageBox.prompt('请输入动作名称',label,{inputValue:initial,inputValidator:v=>{const n=v?.normalize('NFKC').trim();return !!n && [...n].length<=20 && !actions.value.some(a=>a.name===n && n!==initial)||'请输入 1 至 20 字且未使用的动作名称'}});return value.normalize('NFKC').trim()}catch{return ''}
}
async function newAction(group){const name=await askName('新增动作');if(name){actions.value.push(blank(group+'-'+crypto.randomUUID().replaceAll('-',''),name,group));changed()}}
async function rename(a){const name=await askName('修改动作名称',a.name);if(name && name!==a.name){a.name=name;changed()}}
async function remove(a){if(await confirm('从草稿删除「'+a.name+'」及其素材关联？')){actions.value=actions.value.filter(i=>i.actionCode!==a.actionCode);changed()}}
function openLibrary(a){mediaTarget={a,key:'video'};libraryOpen.value=true}
function useExisting(selected){
  const a=mediaTarget.a
  for(const k of ['videoId','videoUrl','videoCode','firstFrameId','firstFrameUrl','lastFrameId','lastFrameUrl'])a[k]=selected[k]
  libraryOpen.value=false;changed()
}
function pick(a,key){mediaTarget={a,key};fileInput.value.accept=key==='video'?'video/mp4,.mp4':'image/png,image/jpeg,image/webp';fileInput.value.value='';fileInput.value.click()}
async function upload(file,name){
  if(!nestTest || !uploadable)throw Error('当前测试账号缺少素材上传权限')
  const form=new FormData();form.append('file',file,name)
  return api('/oss/uploads',{method:'POST',body:form,timeoutMs:180000})
}
async function fromFile(e){
  const file=e.target.files?.[0];if(!file || busy.value)return
  const {a,key}=mediaTarget
  processing.value=true;error.value=''
  const url=URL.createObjectURL(file)
  try{
    if(file.size===0 || file.size>50*1024*1024)throw Error('文件应为 50 MiB 以内的非空素材')
    if(key==='video'){
      status.value='正在提取视频首尾帧并上传测试素材…'
      const pair=await frames.extractPair(url)
      const video=await upload(file,file.name),first=await upload(pair.first,'first.png'),last=await upload(pair.last,'last.png')
      Object.assign(a,{videoId:video.id,videoUrl:video.url,videoCode:'vid_'+video.sha256,firstFrameId:first.id,firstFrameUrl:first.url,lastFrameId:last.id,lastFrameUrl:last.url})
    }else{
      status.value='正在上传测试图片…'
      if(a.videoUrl){
        const [img,vid]=await Promise.all([frames.imageSize(file),frames.videoSize(mediaPath(a.videoUrl))])
        if(img.width!==vid.width || img.height!==vid.height)throw Error('图片尺寸须与当前视频一致')
      }
      const asset=await upload(file,file.name);a[key+'Id']=asset.id;a[key+'Url']=asset.url
    }
    changed()
  }catch(err){error.value='素材尚未绑定。'+reason(err)}
  finally{processing.value=false;URL.revokeObjectURL(url)}
}
async function extract(a,e){
  processing.value=true;error.value=''
  try{const blob=await frames.extract(mediaPath(a.videoUrl),e.edge);const item=await upload(blob,e.edge+'.png');a[e.key+'Id']=item.id;a[e.key+'Url']=item.url;changed()}
  catch(err){error.value='提取或上传失败。'+err.message}finally{processing.value=false}
}
function closeClip(){frameQueue.resume();player?.resume()}
function watchClip(a){frameQueue.pause();player?.suspend();previewUrl.value=mediaPath(a.videoUrl);previewTitle.value=a.name;previewMuted.value=!a.sound;previewOpen.value=true}
function saveLink(url,name){const a=document.createElement('a');a.href=url;a.download=name;a.click()}
async function download(a,e){
  try{const r=await fetch(mediaPath(a[e.key+'Url']));if(!r.ok)throw Error();const b=await r.blob(),url=URL.createObjectURL(b)
    saveLink(url,bird.value.name+'-'+a.name+'-'+e.name+'.'+({'image/webp':'webp','image/jpeg':'jpg'}[b.type]||'png'));setTimeout(()=>URL.revokeObjectURL(url),3000)
  }catch{error.value='图片下载失败，请核对当前测试素材地址'}
}
function back(){router.push({path:'/brand-ips/list',query:route.query})}
function warn(e){if(dirty.value || busy.value){e.preventDefault();e.returnValue=''}}
onMounted(()=>{window.addEventListener('beforeunload',warn);load()})
onBeforeRouteUpdate((to,from)=>to.params.id===from.params.id)
onBeforeRouteLeave(async()=>{if(processing.value||publishing.value)return false;clearTimeout(timer);try{await save()}catch{}return !dirty.value || confirm('草稿尚未同步，保留本机备份并离开？')})
onBeforeUnmount(()=>{frameQueue.destroy();gone=true;clearTimeout(timer);window.removeEventListener('beforeunload',warn);player?.destroy()})
</script>
<style scoped>
.nest-admin{padding:24px;background:#f4f6f5;color:#263b30;min-height:90vh}.nest-admin header h2{margin:10px 0 2px}.nest-admin p,.nest-admin small{color:#637269}.bar,.group-title,.row,.toolbar,.frame-links{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.bar{margin:14px 0}.bar span,.group-title h3,.row>div{margin-right:auto}.editor{display:grid;grid-template-columns:minmax(340px,1.5fr) minmax(300px,1fr);gap:16px;align-items:start}.editing-fields{min-width:0;padding:0;min-inline-size:0;margin:0;border:0}.group,aside{background:#fff;border:1px solid #dce3df;border-radius:12px;margin-bottom:14px;padding:15px}.group article{border-top:1px solid #e3e8e5;padding:16px 0}.row small,details small,.frames b{display:block}.toolbar{margin:8px 0}.toolbar .el-button{margin-left:0}details{font-size:12px;color:#52645a;overflow-wrap:anywhere}summary{cursor:pointer}.frames{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-top:12px}.frames>div{display:flex;align-items:center;gap:6px;background:#f4f6f3;border-radius:6px;padding:6px}.frames img,.empty{width:37px;height:52px;object-fit:contain}.empty{background:#e3e9e5;font-size:10px;display:grid;place-items:center}.frame-links{gap:4px}.frame-links button{border:0;background:none;text-decoration:underline;color:#28583e;padding:0;cursor:pointer}.single{display:block;max-height:60vh;width:100%}aside{position:sticky;top:12px;max-height:90vh;overflow:auto}code{overflow-wrap:anywhere}@media(max-width:800px){.editor{display:flex;flex-direction:column}.editor>*{width:100%}aside{position:static}.nest-admin{padding:10px}}
</style>
