<template><img v-if="url" :src="url" alt="素材预览" loading="lazy"/><span v-else>{{error?'素材无法加载':'加载中…'}}</span></template>
<script setup>
import {assetUrl} from '@/api/operations'
const props=defineProps({assetId:String}),url=ref(''),error=ref(false);let sequence=0
watch(()=>props.assetId,async value=>{const seq=++sequence;if(url.value)URL.revokeObjectURL(url.value);url.value='';error.value=false;if(!value)return;try{const next=await assetUrl(value);if(seq!==sequence)URL.revokeObjectURL(next);else url.value=next}catch{if(seq===sequence)error.value=true}},{immediate:true})
onBeforeUnmount(()=>{sequence++;if(url.value)URL.revokeObjectURL(url.value)})
</script>
