<template>
  <div class="picker">
    <BrandImage v-if="!isAudio" :asset-id="modelValue"/>
    <div class="picker-controls">
      <el-select :model-value="modelValue||undefined" filterable clearable :placeholder="placeholder"
        @update:model-value="value=>emit('update:modelValue',value||'')">
        <el-option v-for="asset in assets" :key="asset.id" :value="asset.id" :label="assetLabel(asset)"/>
      </el-select>
      <el-button v-if="more" size="small" :loading="loading" @click="loadMore">加载更多素材</el-button>
      <el-button :disabled="disabled" @click="openUpload">{{isAudio?'上传原声':'上传图片'}}</el-button>
    </div>
  </div>
  <el-dialog v-model="uploadOpen" :title="isAudio?'上传动画原声':'上传图片'" width="min(520px,94vw)"
    append-to-body :close-on-click-modal="false" :close-on-press-escape="!uploading" :show-close="!uploading" destroy-on-close>
    <el-alert :title="uploadHint" type="info" :closable="false"/>
    <el-form label-position="top">
      <el-form-item :label="isAudio?'音频文件':'图片文件'">
        <input type="file" :accept="accept" :aria-label="isAudio?'音频文件':'图片文件'" @change="selectFile"/>
      </el-form-item>
      <el-form-item v-if="isAudio" label="素材来源与授权说明">
        <el-input v-model="license" maxlength="500" placeholder="例如：自有原创素材，已获得使用授权"/>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <template #footer>
      <el-button :disabled="uploading" @click="uploadOpen=false">取消</el-button>
      <el-button type="primary" :loading="uploading" @click="upload">上传并选用</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { api } from '@/api/operations'
import BrandImage from './BrandImage.vue'

const props=defineProps({
  modelValue:String,
  disabled:Boolean,
  kind:{type:String,default:'image'},
  sprite:Boolean
})
const emit=defineEmits(['update:modelValue'])
const assets=ref([]), more=ref(true), loading=ref(false)
const uploadOpen=ref(false), uploading=ref(false), license=ref(''), error=ref('')
const isAudio=computed(()=>props.kind==='audio')
const placeholder=computed(()=>isAudio.value?'不使用原声':props.sprite?'从素材库选择图集':'从素材库选择')
const accept=computed(()=>isAudio.value?'.m4a,audio/mp4':'image/png,image/jpeg,image/webp')
const uploadHint=computed(()=>{
  if(isAudio.value)return '支持单音轨 AAC/M4A，最大 10 MB。原声时长需与动画时长匹配。'
  if(props.sprite)return '支持 PNG、JPEG 和 WebP，最大 10 MB。图集按横向从左到右逐行读取。'
  return '支持 PNG、JPEG 和 WebP，最大 10 MB。表情可使用透明 WebP 或 PNG，并保持同一画布比例和脚底位置。'
})
let file=null
function assetLabel(asset){
  return isAudio.value?asset.original_name:asset.original_name+' · '+asset.width+'×'+asset.height
}
async function loadMore(){
  loading.value=true
  try{
    const next=await api('/assets?offset='+assets.value.length+'&kind='+encodeURIComponent(props.kind))
    assets.value.push(...next)
    more.value=next.length===100
  }catch(e){ElMessage.error(e.message)}finally{loading.value=false}
}
function openUpload(){
  file=null
  license.value=''
  error.value=''
  uploadOpen.value=true
}
function selectFile(event){
  file=event.target.files?.[0]||null
  error.value=''
}
async function upload(){
  if(!file){error.value='请选择要上传的文件。';return}
  if(isAudio.value&&!license.value.trim()){error.value='请填写原声来源与授权说明。';return}
  if(file.size>10*1024*1024){error.value='文件不能超过 10 MB。';return}
  uploading.value=true
  error.value=''
  try{
    const body=new FormData()
    body.append('file',file)
    if(isAudio.value)body.append('license',license.value)
    const result=await api('/assets',{method:'POST',body,timeoutMs:props.sprite?120000:20000})
    if(isAudio.value?!result.mime?.startsWith('audio/'):!result.mime?.startsWith('image/')){
      throw new Error(isAudio.value?'请选择 M4A 原声文件。':'请选择支持的图片文件。')
    }
    assets.value.unshift({id:result.assetId,original_name:file.name,width:result.width,height:result.height})
    emit('update:modelValue',result.assetId)
    uploadOpen.value=false
  }catch(e){error.value=e.name==='TimeoutError'?'上传结果暂未确认，请先在素材库检查文件是否已上传。':e.message}finally{uploading.value=false}
}
onMounted(loadMore)
</script>

<style scoped>
.picker{display:flex;gap:14px;align-items:center}
.picker-controls{display:flex;gap:8px;flex-wrap:wrap;flex:1}
.picker-controls .el-select{min-width:180px;flex:1}
.el-alert{margin-bottom:16px}
</style>
