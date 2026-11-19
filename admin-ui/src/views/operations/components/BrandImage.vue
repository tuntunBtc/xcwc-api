<template><button v-if="assetId" class="brand-image" type="button" aria-label="查看图片" @click="open"><img v-if="url" :src="url" alt="素材缩略图" loading="lazy"/><span v-else>{{error?'加载失败':'加载中'}}</span></button><span v-else class="missing">待上传</span><el-dialog v-model="visible" title="图片预览" width="min(700px,95vw)" append-to-body><div class="image-preview"><ProtectedImage v-if="visible" :asset-id="assetId"/></div></el-dialog></template>
<script setup>
import {getToken} from '@/utils/auth'
import {adminPath} from '@/api/operations'
import ProtectedImage from './ProtectedImage.vue'
const props=defineProps({assetId:String}),url=ref(''),error=ref(false),visible=ref(false);let sequence=0
async function load(value){const seq=++sequence;if(url.value)URL.revokeObjectURL(url.value);url.value='';error.value=false;if(!value)return;try{const r=await fetch(adminPath('/assets/'+encodeURIComponent(value)+'/thumbnail'),{headers:{Authorization:'Bearer '+getToken()}});if(!r.ok)throw new Error();const next=URL.createObjectURL(await r.blob());if(seq!==sequence)URL.revokeObjectURL(next);else url.value=next}catch{if(seq===sequence)error.value=true}}
watch(()=>props.assetId,load,{immediate:true});function open(){visible.value=true;if(error.value)load(props.assetId)}
onBeforeUnmount(()=>{sequence++;if(url.value)URL.revokeObjectURL(url.value)})
</script>
<style scoped>.brand-image{width:64px;height:64px;border:1px solid #e2e8f0;border-radius:6px;background:repeating-conic-gradient(#f3f4f6 0% 25%,white 0% 50%) 50%/14px 14px;cursor:zoom-in;display:grid;place-items:center;padding:3px}.brand-image img{max-width:100%;max-height:58px;object-fit:contain}.missing{font-size:12px;color:#909399}.image-preview{min-height:220px;text-align:center;background:repeating-conic-gradient(#eceff3 0% 25%,white 0% 50%) 50%/20px 20px}.image-preview :deep(img){max-width:100%;max-height:65vh;object-fit:contain}</style>
