<template>
  <span ref="host" class="thumb" :aria-label="label">
    <img v-if="blobUrl" :src="blobUrl" :alt="label" @error="decodeError">
    <button v-else-if="failed" type="button" :aria-label="'重试读取'+label" @click="load">重试</button>
    <span v-else aria-hidden="true">加载中</span>
  </span>
</template>
<script setup>
import {onMounted,onBeforeUnmount,ref} from 'vue'
const props=defineProps({url:String,label:String,queue:Object})
const host=ref(),blobUrl=ref(''),failed=ref(false),life=new AbortController()
let observer,pending=false
function decodeError(){if(blobUrl.value)URL.revokeObjectURL(blobUrl.value);blobUrl.value='';failed.value=true}
async function load(){
  if(pending || life.signal.aborted)return
  observer?.disconnect()
  failed.value=false;pending=true
  try {
    const blob=await props.queue.request(props.url,life.signal)
    if(!life.signal.aborted)blobUrl.value=URL.createObjectURL(blob)
  }catch{if(!life.signal.aborted)failed.value=true}
  finally{pending=false}
}
onMounted(()=>{
  observer=new IntersectionObserver(entries=>{if(entries.some(entry=>entry.isIntersecting))load()},{rootMargin:'80px'})
  observer.observe(host.value)
})
onBeforeUnmount(()=>{life.abort();observer?.disconnect();if(blobUrl.value)URL.revokeObjectURL(blobUrl.value)})
</script>
<style scoped>
.thumb{display:grid;place-items:center;width:37px;height:52px;flex:none;background:#eef2ee;border-radius:4px;color:#526258;font-size:10px}.thumb img{height:100%;width:100%;object-fit:contain}.thumb button{padding:2px;border:none;color:#28583e;background:none;text-decoration:underline;cursor:pointer}
</style>
