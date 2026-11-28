<template><main class="ops-login"><section><p class="brand">卜叽岛 · 运营工作台</p><h1>让每只小鸟拥有自己的世界</h1><p>管理角色、造型与页面内容，预览确认后再发布。</p><el-form @submit.prevent="submit"><el-form-item label="账号"><el-input v-model="username" autocomplete="username" /></el-form-item><el-form-item label="密码"><el-input v-model="password" type="password" show-password autocomplete="current-password" @keyup.enter="submit" /></el-form-item><el-button type="primary" :loading="busy" @click="submit">登录运营后台</el-button><p role="alert" class="error">{{error}}</p></el-form></section></main></template>
<script setup>
import {nestTest} from '@/api/nest-test'
import useUserStore from '@/store/modules/user'
const store=useUserStore(), router=useRouter(),route=useRoute();const username=ref(''),password=ref(''),busy=ref(false),error=ref('');
function testDestination(){const target=route.query.redirect;return nestTest && typeof target==='string' && /^\/brand-ip-nest\/[0-9a-f]{32}$/.test(target)?target:'/index'}
watch(()=>route.query.nestError,reason=>{if(nestTest && reason)error.value='后台会话暂未建立。'+String(reason)},{immediate:true})
async function submit(){if(busy.value)return;busy.value=true;error.value='';try{await store.login({username:username.value,password:password.value});await router.push(testDestination())}catch(e){error.value=e.message||'登录失败，请检查账号与密码'}finally{busy.value=false}}
</script>
<style scoped>.ops-login{min-height:100vh;display:grid;place-items:center;background:#f4f6f1;color:#25443d}.ops-login section{width:min(540px,90vw);background:white;padding:48px;border-radius:24px;box-shadow:0 16px 60px #173b3012}.brand{letter-spacing:3px;color:#6b8e79}h1{font-size:28px;margin:28px 0 12px}.el-form{margin-top:36px}.el-button{width:100%}.error{color:#a24136}</style>
