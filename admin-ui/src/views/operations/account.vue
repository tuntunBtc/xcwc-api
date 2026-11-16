<template><div class="app-container"><h1>账号与权限</h1><el-alert v-if="error" type="error" :title="error"/><h2>修改我的密码</h2><el-input v-model="oldPassword" type="password" placeholder="当前密码"/><el-input v-model="newPassword" type="password" placeholder="新密码，12–72 字节"/><el-button @click="change">修改并重新登录</el-button><template v-if="canManage"><h2>创建运营账号</h2><el-input v-model="form.username" placeholder="登录账号"/><el-input v-model="form.nickname" placeholder="显示名称"/><el-input v-model="form.password" type="password" placeholder="初始密码"/><el-select v-model="form.roles" multiple><el-option v-for="(name,id) in roleNames" :key="id" :label="name" :value="Number(id)"/></el-select><el-button @click="create">创建账号</el-button><h2>已有账号</h2><el-table :data="accounts"><el-table-column prop="user_name" label="账号"/><el-table-column prop="nick_name" label="名称"/><el-table-column label="权限调整"><template #default="{row}"><el-select v-model="row.roles" multiple><el-option v-for="(name,id) in roleNames" :key="id" :label="name" :value="Number(id)"/></el-select><el-switch v-model="row.status" active-value="0" inactive-value="1" active-text="启用"/><el-button :disabled="Number(row.user_id)===1" @click="update(row)">保存</el-button></template></el-table-column></el-table></template></div></template>
<script setup>
import {api} from '@/api/operations'
import useUserStore from '@/store/modules/user'
import {ElMessage} from 'element-plus'
const store=useUserStore(),router=useRouter(),canManage=computed(()=>store.permissions.includes('system:manage'));const error=ref(''),accounts=ref([]),oldPassword=ref(''),newPassword=ref(''),form=ref({username:'',nickname:'',password:'',roles:[2]}),roleNames={1:'系统管理',2:'内容编辑',3:'内容发布',4:'只读查看'}
async function run(fn){try{error.value='';await fn()}catch(e){error.value=e.message}}
async function load(){if(canManage.value)accounts.value=await api('/accounts')}
async function change(){await run(async()=>{await api('/password',{method:'PUT',body:{oldPassword:oldPassword.value,newPassword:newPassword.value}});await store.logOut();router.push('/login')})}
async function create(){await run(async()=>{await api('/accounts',{method:'POST',body:form.value});form.value={username:'',nickname:'',password:'',roles:[2]};await load();ElMessage.success('账号已创建')})}
async function update(row){await run(async()=>{await api('/accounts/'+row.user_id+'/roles',{method:'PUT',body:{roles:row.roles,status:row.status}});ElMessage.success('权限已更新，原会话已失效')})}
onMounted(()=>run(load))
</script>
<style scoped>.app-container{max-width:1100px;margin:auto}.el-input{display:block;max-width:500px;margin:12px 0}.el-select{min-width:240px}h2{margin-top:36px}</style>
