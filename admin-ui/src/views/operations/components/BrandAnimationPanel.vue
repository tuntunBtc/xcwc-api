<template>
  <section class="animation-panel" aria-label="待机动画">
    <div>
      <h3>待机动画</h3>
      <p>{{summary}}</p>
    </div>
    <div class="panel-actions">
      <el-button v-if="canBrand('edit')" :disabled="busy||previewLoading" @click="edit">配置待机动画</el-button>
      <el-button v-if="canBrand('preview')&&item.animation" :loading="previewLoading" :disabled="busy" @click="preview">
        预览待机动画
      </el-button>
    </div>
  </section>
  <el-alert v-if="previewError" :title="previewError" type="error" :closable="false"/>
  <el-dialog v-model="editing" title="配置待机动画" width="min(760px,95vw)" destroy-on-close
    :close-on-click-modal="false" :before-close="closeEdit" :show-close="!saving" :close-on-press-escape="!saving">
    <el-form v-if="form" label-position="top" :disabled="saving">
      <el-form-item label="动画图集" required>
        <BrandAssetPicker v-model="form.assetId" sprite :disabled="saving"/>
      </el-form-item>
      <div class="grid-fields">
        <el-form-item label="列数"><el-input-number v-model="form.columns" aria-label="列数" :min="1" :max="64" :precision="0"/></el-form-item>
        <el-form-item label="行数"><el-input-number v-model="form.rows" aria-label="行数" :min="1" :max="64" :precision="0"/></el-form-item>
        <el-form-item label="帧数"><el-input-number v-model="form.frames" aria-label="帧数" :min="1" :max="512" :precision="0"/></el-form-item>
        <el-form-item :label="form.timed?'兼容帧率 (FPS)':'帧率 (FPS)'">
          <el-input-number v-model="form.fps" aria-label="帧率 (FPS)" :min="1" :max="30" :precision="0"/>
        </el-form-item>
      </div>
      <el-form-item label="播放方式">
        <el-radio-group v-model="form.timed">
          <el-radio-button :value="false">等帧间隔</el-radio-button>
          <el-radio-button :value="true">逐帧时间轴</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <template v-if="form.timed">
        <el-form-item label="总时长 (毫秒)" required>
          <el-input-number v-model="form.durationMs" aria-label="总时长 (毫秒)" :min="1" :max="120000" :precision="0"/>
        </el-form-item>
        <el-form-item label="帧起点 (毫秒)" required>
          <el-input v-model="form.timeline" type="textarea" :rows="4" maxlength="6000"
            aria-label="帧起点 (毫秒)" placeholder="粘贴 JSON 数组，例如 [0, 100, 200]"/>
          <small>每帧一个起点。第一帧从 0 开始，时间递增，末帧保持到总时长。</small>
        </el-form-item>
        <el-form-item label="动画原声（可选）">
          <BrandAssetPicker v-model="form.audioAssetId" kind="audio" :disabled="saving"/>
        </el-form-item>
      </template>
    </el-form>
    <el-alert v-if="formError" :title="formError" type="error" :closable="false"/>
    <template #footer>
      <el-button v-if="item.animation" type="danger" plain :disabled="saving" @click="remove">移除动画</el-button>
      <el-button :disabled="saving" @click="closeEdit(()=>editing=false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存动画草稿</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="previewOpen" title="待机动画预览" width="min(760px,95vw)" destroy-on-close>
    <BrandAnimationPlayer v-if="previewOpen&&previewData" :token="previewData.token" :animation="previewData.animation"/>
  </el-dialog>
</template>

<script setup>
import {ElMessage,ElMessageBox} from 'element-plus'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '@/api/operations'
import {brandPayload,canBrand} from '@/api/brand-ip'
import BrandAssetPicker from './BrandAssetPicker.vue'
import BrandAnimationPlayer from './BrandAnimationPlayer.vue'

const props=defineProps({item:{type:Object,required:true},busy:Boolean})
const emit=defineEmits(['saved'])
const editing=ref(false),saving=ref(false),form=ref(null),formError=ref('')
const previewOpen=ref(false),previewLoading=ref(false),previewError=ref(''),previewData=ref(null)
const summary=computed(()=>{
  const a=props.item.animation
  return a?`${a.columns} 列 × ${a.rows} 行 · ${a.frames} 帧 · ${a.audioAssetId?'含原声':'无原声'}`:'默认造型尚未配置待机动画。'
})
let original, draftVersion
watch(form,()=>{formError.value=''},{deep:true})
function edit(){
  const a=props.item.animation
  form.value={
    assetId:a?.assetId||'',columns:a?.columns??1,rows:a?.rows??1,frames:a?.frames??1,fps:a?.fps??12,
    timed:!!a?.frameTimesMs,durationMs:a?.durationMs,timeline:a?.frameTimesMs?JSON.stringify(a.frameTimesMs):'',
    audioAssetId:a?.audioAssetId||''
  }
  original=JSON.stringify(form.value)
  draftVersion=props.item.draftVersion
  formError.value=''
  editing.value=true
}
async function discard(){
  if(!editing.value||JSON.stringify(form.value)===original)return true
  try{
    await ElMessageBox.confirm('动画草稿的修改尚未保存，确定放弃？','未保存修改',{type:'warning'})
    return true
  }catch{return false}
}
async function closeEdit(done){if(!saving.value&&await discard())done()}
onBeforeRouteLeave(async()=>!saving.value&&await discard())
function animationInput(){
  const {assetId,columns,rows,frames,fps,timed,durationMs,timeline,audioAssetId}=form.value
  if(!assetId)throw new Error('请上传或选择动画图集。')
  if(![columns,rows,frames,fps].every(Number.isInteger)||frames>columns*rows)
    throw new Error('请检查行列、帧数和帧率。帧数不能超过图集格数。')
  const animation={assetId,columns,rows,frames,fps}
  if(!timed)return animation
  let time
  try{time=JSON.parse(timeline)}catch{throw new Error('请按 JSON 数组填写每帧的起点，例如 [0, 100, 200]。')}
  if(!Number.isInteger(durationMs)||!Array.isArray(time)||time.length!==frames||time[0]!==0||
    time.some((value,index)=>!Number.isInteger(value)||value>=durationMs||(index>0&&value<=time[index-1]))){
    throw new Error('时间轴必须与帧数一致，从 0 严格递增，且最后一帧起点小于总时长。')
  }
  Object.assign(animation,{durationMs,frameTimesMs:time})
  if(audioAssetId)animation.audioAssetId=audioAssetId
  return animation
}
async function persist(animation){
  saving.value=true
  formError.value=''
  try{
    const updated=await api('/brand-ips/'+encodeURIComponent(props.item.id),{
      method:'PUT',body:{expectedVersion:draftVersion,content:{...brandPayload(props.item),animation}}
    })
    emit('saved',updated)
    editing.value=false
    ElMessage.success(animation?'动画草稿已保存':'动画已从草稿移除')
  }catch(e){formError.value=e.message}finally{saving.value=false}
}
async function save(){
  try{await persist(animationInput())}catch(e){formError.value=e.message}
}
async function remove(){
  try{await ElMessageBox.confirm('从草稿中移除待机动画？发布后生效。','移除动画',{type:'warning'})}catch{return}
  await persist(null)
}
async function preview(){
  previewLoading.value=true
  previewError.value=''
  try{
    const id=props.item.id
    const {content,token}=await api('/brand-ips/'+encodeURIComponent(id)+'/preview',{method:'POST'})
    const appearanceId=content.characters.find(character=>character.id===id)?.defaultAppearanceId
    const animation=content.appearances.find(appearance=>appearance.id===appearanceId)?.animation
    if(!animation||!token)throw new Error('预览版本没有可播放的待机动画。')
    previewData.value={token,animation}
    previewOpen.value=true
  }catch(e){previewError.value=e.message}finally{previewLoading.value=false}
}
</script>

<style scoped>
.animation-panel{border:1px solid #dfe6ee;border-radius:8px;padding:16px;margin:20px 0;display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
.animation-panel h3{font-size:16px;margin:0 0 8px}
.animation-panel p{margin:0;color:#606a78}
.grid-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(155px,1fr));gap:12px}
.grid-fields :deep(.el-input-number){width:100%}
.el-form-item :deep(.picker){width:100%}
small{display:block;color:#606a78;line-height:1.4;margin-top:6px}
</style>
