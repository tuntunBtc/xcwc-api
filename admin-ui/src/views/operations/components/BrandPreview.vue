<template>
  <el-dialog :model-value="modelValue" @update:model-value="v=>emit('update:modelValue',v)" title="IP 表情预览" width="min(860px,96vw)" destroy-on-close>
    <el-alert title="此预览不创建领养、不发放奖励。检查比例和脚底位置后，再发布正式内容。" :closable="false"/>
    <div v-if="item" class="preview-layout">
      <div class="preview-stage" :class="background"><ProtectedImage v-if="active?.assetId" :asset-id="active.assetId"/><span v-else>此状态尚未配置图片</span></div>
      <div class="preview-controls"><h3>{{item.name}}</h3><el-radio-group v-model="background"><el-radio-button value="grass">草地底色</el-radio-button><el-radio-button value="checker">透明网格</el-radio-button></el-radio-group><el-select v-model="state" aria-label="预览表情"><el-option v-for="e in item.expressions" :key="e.type" :value="e.type" :label="e.name"/></el-select><p>切换表情时，观察主体大小、透明边缘和脚底位置是否一致。</p><p>这是后台视觉检查，实际小程序效果仍需客户端联调验证。</p></div>
    </div>
  </el-dialog>
</template>
<script setup>
import ProtectedImage from './ProtectedImage.vue'
const props=defineProps({modelValue:Boolean,item:Object}),emit=defineEmits(['update:modelValue']),state=ref('normal'),background=ref('grass')
const active=computed(()=>{
  const expressions=props.item?.expressions||[]
  const selected=expressions.find(entry=>entry.type===state.value)
  if(selected?.assetId&&selected.listed!==false)return selected
  return expressions.find(entry=>entry.type==='normal'&&entry.assetId&&entry.listed!==false)
})
</script>
<style scoped>
.preview-layout{display:flex;gap:24px;margin-top:20px}.preview-stage{height:400px;flex:1;min-width:200px;display:flex;align-items:flex-end;justify-content:center;padding:30px;border-radius:10px;border:1px solid #ddd}.grass{background:linear-gradient(#ecf6f1 0 55%,#d3e6b9 55%)}.checker{background:repeating-conic-gradient(#eee 0% 25%,white 0% 50%) 50%/20px 20px}.preview-stage :deep(img){max-width:100%;max-height:100%;object-fit:contain}.preview-controls{width:250px}.preview-controls .el-select{margin-top:20px}.preview-controls p{line-height:1.8;color:#657082}@media(max-width:650px){.preview-layout{flex-direction:column}.preview-stage{height:300px;flex:auto}.preview-controls{width:100%}}
</style>
