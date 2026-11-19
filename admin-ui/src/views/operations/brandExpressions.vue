<template>
<div class="expression-page" v-loading="loading">
  <el-breadcrumb separator="/"><el-breadcrumb-item :to="{name:'BrandIps',query:route.query}">品牌 IP 管理</el-breadcrumb-item><el-breadcrumb-item>{{item?.name||'当前 IP'}}</el-breadcrumb-item><el-breadcrumb-item>表情管理</el-breadcrumb-item></el-breadcrumb>
  <div class="heading"><div class="identity"><BrandImage :asset-id="item?.mainAssetId"/><div><h2>{{item?.name}} · 表情管理</h2><p>{{item?.city||'城市待完善'}} · IP ID：{{item?.id}}</p></div></div><el-button @click="back">返回 IP 列表</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
  <el-alert v-if="item?.unmappedEntities" type="warning" :closable="false" :title="'发现 '+item.unmappedEntities+' 个同编码实体尚未关联此 IP。上下架暂不覆盖这些旧实体，请先核对实体关联。'"/>
  <el-alert v-if="pending" type="warning" title="有操作结果尚未确认。" :closable="false"><el-button @click="retry" :loading="busy">重试原操作</el-button></el-alert>
  <div class="toolbar"><el-input v-model="search" clearable placeholder="搜索表情名称 / ID" style="width:220px"/><span>默认造型 · 已配置 {{item?.configured||0}} / 10 <el-tag v-if="item?.dirty" type="warning">有未发布修改</el-tag></span><div><el-button v-if="canBrand('edit')" :disabled="busy || !emptySlots.length" @click="edit(emptySlots[0], true)">新增表情</el-button><el-button v-if="canBrand('preview')" @click="preview" :disabled="busy">预览表情</el-button><el-button v-if="canBrand('publish')" type="primary" @click="publish" :disabled="busy||!!pending||!item?.dirty">发布更新</el-button></div></div>
  <el-table :data="visibleExpressions" border stripe row-key="id"><el-table-column label="图片" width="100"><template #default="{row}"><BrandImage :asset-id="row.assetId"/></template></el-table-column><el-table-column prop="name" label="表情名称" min-width="150"/><el-table-column label="表情 ID" min-width="220"><template #default="{row}"><el-button link @click="copy(row.id)">{{row.id}} ⧉</el-button></template></el-table-column><el-table-column label="状态类型" width="130"><template #default="{row}">{{stateNames[row.type]}}</template></el-table-column><el-table-column label="上下架状态" width="120"><template #default="{row}"><el-tag :type="row.listed?'success':'info'">{{row.listed?'已上架':'已下架'}}</el-tag></template></el-table-column><el-table-column label="操作" width="225" fixed="right"><template #default="{row}"><template v-if="canBrand('edit')"><el-button link type="primary" :disabled="busy" @click="edit(row)">编辑</el-button><el-button link type="primary" :disabled="busy" @click="setListed(row)">{{row.listed?'下架':'上架'}}</el-button><el-button link type="danger" :disabled="busy" @click="remove(row)">删除</el-button></template><span v-else>只读</span></template></el-table-column></el-table>
  <p class="hint">每种状态可配置一张表情图。发布需要一张已上架的「正常」图。其他表情可按需添加，未配置时小窝使用正常图。</p>
  <BrandAnimationPanel v-if="item?.animationEditingEnabled&&(canBrand('edit')||canBrand('preview'))"
    :item="item" :busy="busy||loading" @saved="value=>item=value"/>
  <el-dialog v-model="editOpen" :title="creating?'新增表情':'编辑表情'" width="min(640px,95vw)" :before-close="closeEdit" :close-on-click-modal="false" destroy-on-close><el-form label-position="top"><el-form-item label="表情 ID"><el-input :model-value="form.id" readonly/></el-form-item><el-form-item label="状态类型"><el-select v-if="creating" :model-value="form.type" @update:model-value="selectSlot"><el-option v-for="slot in emptySlots" :key="slot.type" :value="slot.type" :label="stateNames[slot.type]"/></el-select><el-input v-else :model-value="stateNames[form.type]" readonly/></el-form-item><el-form-item label="表情名称" required><el-input v-model="form.name" maxlength="60" show-word-limit/></el-form-item><el-form-item label="表情图片"><BrandAssetPicker v-model="form.assetId"/></el-form-item></el-form><el-alert v-if="reused" type="warning" :closable="false" title="这张图片已用于本 IP 的其他表情。保存表示明确复用，请确认符合该状态。"/><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><template #footer><el-button @click="closeEdit(()=>editOpen=false)" :disabled="busy">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存表情草稿</el-button></template></el-dialog>
  <BrandPreview v-model="previewOpen" :item="item"/>
</div>
</template>
<script setup>
import {ElMessage,ElMessageBox} from 'element-plus'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '@/api/operations'
import {brandDetail,brandPayload,brandAction,canBrand,pendingBrandAction,retryBrandAction} from '@/api/brand-ip'
import BrandImage from './components/BrandImage.vue'
import BrandAssetPicker from './components/BrandAssetPicker.vue'
import BrandPreview from './components/BrandPreview.vue'
import BrandAnimationPanel from './components/BrandAnimationPanel.vue'
const route=useRoute(),router=useRouter(),item=ref(null),loading=ref(false),busy=ref(false),error=ref(''),pending=ref(pendingBrandAction()),form=ref({}),formError=ref(''),editOpen=ref(false),previewOpen=ref(false)
const stateNames={normal:'正常',happy:'开心',hungry:'饥饿',thirsty:'口渴',lonely:'需要陪伴',shy:'害羞',angry:'生气',crying:'大哭',confused:'疑惑',veryHappy:'很开心'}
const search=ref(''),creating=ref(false)
const emptySlots=computed(()=>(item.value?.expressions||[]).filter(e=>!e.assetId))
const visibleExpressions=computed(()=>{
  const keyword=search.value.trim().toLowerCase()
  return (item.value?.expressions||[]).filter(e=>e.assetId&&[e.name,e.id,stateNames[e.type]].some(value=>value.toLowerCase().includes(keyword)))
})
function selectSlot(type){
  const slot=emptySlots.value.find(e=>e.type===type)
  if(slot)form.value={...slot,name:stateNames[slot.type],listed:true}
}
let original='';const dirty=computed(()=>editOpen.value&&JSON.stringify(form.value)!==original)
const reused=computed(()=>form.value.assetId&&item.value?.expressions.some(e=>e.id!==form.value.id&&e.assetId===form.value.assetId))
async function load(){loading.value=true;error.value='';try{item.value=await brandDetail(String(route.params.id))}catch(e){error.value=e.message}finally{loading.value=false}}
function back(){router.push({name:'BrandIps',query:route.query})}
async function copy(value){try{await navigator.clipboard.writeText(value);ElMessage.success('已复制表情 ID')}catch{ElMessage.warning('复制失败，请选择完整 ID')}}
function edit(row,add=false){creating.value=add;form.value={...row};if(add)selectSlot(row.type);original=JSON.stringify(form.value);formError.value='';editOpen.value=true}
async function discard(){if(!dirty.value)return true;try{await ElMessageBox.confirm('表情修改尚未保存，确定放弃？','未保存修改',{confirmButtonText:'放弃修改',cancelButtonText:'继续编辑',type:'warning'});return true}catch{return false}}
async function closeEdit(done){if(!busy.value&&await discard())done()}
onBeforeRouteLeave(async()=>!busy.value&&await discard())
async function persist(row){
  const current=item.value
  const payload=brandPayload(current)
  payload.expressions=current.expressions.map(e=>e.id===row.id?row:e)
  item.value=await api('/brand-ips/'+encodeURIComponent(current.id),{method:'PUT',body:{expectedVersion:current.draftVersion,content:payload}})
}
async function save(){
  if(!form.value.name?.trim()){formError.value='请填写表情名称。';return}
  if(!form.value.assetId){formError.value='请上传或选择表情图片。';return}
  busy.value=true;formError.value=''
  try{await persist({...form.value,name:form.value.name.trim()});editOpen.value=false;ElMessage.success('表情草稿已保存')}
  catch(e){formError.value=e.message}finally{busy.value=false}
}
async function updateRow(row,message){
  busy.value=true;error.value=''
  try{await persist(row);ElMessage.success(message)}
  catch(e){error.value=e.message}finally{busy.value=false}
}
function setListed(row){return updateRow({...row,listed:!row.listed},row.listed?'已下架，发布后生效':'已上架，发布后生效')}
async function remove(row){
  try{await ElMessageBox.confirm('删除「'+row.name+'」的配置？素材仍可在素材库中选用。','删除表情',{type:'warning'})}
  catch{return}
  await updateRow({...row,assetId:'',listed:false,name:stateNames[row.type]},'表情已从草稿删除')
}
async function preview(){busy.value=true;try{await api('/brand-ips/'+item.value.id+'/preview',{method:'POST'});previewOpen.value=true}catch(e){error.value=e.message}finally{busy.value=false}}
async function publish(){busy.value=true;error.value='';try{await brandAction(item.value,'publish');ElMessage.success('当前 IP 内容已发布，上下架状态保持不变');await load()}catch(e){error.value=e.message}finally{busy.value=false;pending.value=pendingBrandAction()}}
async function retry(){busy.value=true;try{await retryBrandAction();await load();ElMessage.success('已确认操作成功')}catch(e){error.value=e.message}finally{busy.value=false;pending.value=pendingBrandAction()}}
onMounted(load)
</script>
<style scoped>
.expression-page{padding:24px;background:#fff;min-height:calc(100vh - 84px)}.heading{display:flex;justify-content:space-between;align-items:center;margin:24px 0}.identity{display:flex;gap:16px;align-items:center}.identity h2{font-size:21px;margin:0 0 8px}.identity p{font-size:13px;color:#7b8492;margin:0}.toolbar{display:flex;justify-content:space-between;align-items:center;margin:20px 0}.hint{font-size:13px;color:#7b8492;line-height:1.8}.el-alert{margin-bottom:16px}.el-form-item :deep(.picker){width:100%}@media(max-width:700px){.expression-page{padding:14px}.heading,.toolbar{align-items:flex-start;gap:12px;flex-wrap:wrap}.identity p{overflow-wrap:anywhere}}
</style>
