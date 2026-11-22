<template>
<div class="brand-page">
  <div class="page-heading"><div><h2>品牌 IP 管理</h2><p>按 IP 独立维护资料与表情，管理上架状态。</p></div><el-button v-if="canBrand('edit')" type="primary" @click="edit()">新增 IP</el-button></div>
  <el-alert v-if="pending" title="有一次操作结果尚未确认，请重试原操作。" type="warning" :closable="false"><el-button @click="retry" :loading="busy">重试原操作</el-button></el-alert>
  <el-form inline class="filters" @submit.prevent="search">
    <el-form-item label="名称 / ID"><el-input v-model="filters.query" placeholder="输入 IP 名称或 ID" clearable @keyup.enter="search"/></el-form-item>
    <el-form-item label="城市"><el-select v-model="filters.city" clearable filterable placeholder="全部城市" style="width:150px"><el-option v-for="city in cities" :key="city" :label="city" :value="city"/></el-select></el-form-item>
    <el-form-item label="状态"><el-select v-model="filters.status" clearable placeholder="全部状态" style="width:140px"><el-option label="未上架" value="new"/><el-option label="已上架" value="listed"/><el-option label="已下架" value="delisted"/></el-select></el-form-item>
    <el-form-item><el-button type="primary" @click="search">搜索</el-button><el-button @click="reset">重置</el-button></el-form-item>
  </el-form>
  <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
  <el-table v-loading="loading" :data="items" row-key="id" border stripe empty-text="暂无符合条件的 IP，请调整筛选或新增 IP">
    <el-table-column label="主图" width="80" fixed="left"><template #default="{row}"><BrandImage :asset-id="row.mainAssetId"/></template></el-table-column>
    <el-table-column prop="name" label="IP 名称" min-width="145" show-overflow-tooltip/>
    <el-table-column label="IP ID" min-width="160"><template #default="{row}"><el-tooltip :content="row.id"><el-button link @click="copy(row.id)">{{row.id.slice(0,12)}}… 复制</el-button></el-tooltip></template></el-table-column>
    <el-table-column label="所属城市" width="90"><template #default="{row}">{{row.city||'待完善'}}</template></el-table-column>
    <el-table-column label="上架状态" width="100"><template #default="{row}"><el-tag :type="row.status==='listed'?'success':'info'">{{statusLabel[row.status]}}</el-tag></template></el-table-column>
    <el-table-column label="内容状态" width="135"><template #default="{row}"><el-tag :type="row.dirty?'warning':'success'" effect="plain">{{row.status==='new'?'未发布':row.dirty?'有未发布修改':'已同步'}}</el-tag></template></el-table-column>
    <el-table-column label="表情" width="80"><template #default="{row}">{{row.configured}} / 10</template></el-table-column>
    <el-table-column label="更新时间" min-width="150"><template #default="{row}">{{row.updatedAt||'历史资料'}}</template></el-table-column>
    <el-table-column label="操作" fixed="right" width="250"><template #default="{row}">
      <el-button v-if="canBrand('edit')" link type="primary" @click="edit(row)">编辑</el-button><el-button link type="primary" @click="expressions(row)">表情管理</el-button>
      <el-button v-if="canBrand('status')" link :type="row.status==='listed'?'danger':'primary'" :disabled="busy||!!pending" @click="operate(row,row.status==='listed'?'delist':'list')">{{row.status==='listed'?'下架':'上架'}}</el-button>
      <el-dropdown @command="cmd=>more(row,cmd)"><el-button link>更多 ▾</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="canBrand('preview')" command="preview">预览</el-dropdown-item><el-dropdown-item v-if="canBrand('publish')" command="publish" :disabled="!row.dirty||busy">发布更新</el-dropdown-item><el-dropdown-item v-if="canBrand('audit')" command="history">版本记录</el-dropdown-item><el-dropdown-item v-if="canBrand('edit')&&row.status==='new'" command="delete">删除未发布草稿</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
    </template></el-table-column>
  </el-table>
  <div class="pagination"><span>共 {{total}} 个 IP</span><el-pagination v-model:current-page="filters.page" :page-size="20" :total="total" layout="prev,pager,next" @current-change="searchPage"/></div>
  <el-dialog v-model="editOpen" :title="form.id?'编辑 IP':'新增 IP'" width="min(640px,95vw)" :close-on-click-modal="false" :before-close="closeEdit" destroy-on-close>
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item v-if="form.id" label="IP ID"><el-input :model-value="form.id" readonly/></el-form-item>
      <el-form-item label="IP 名称" required><el-input v-model="form.name" maxlength="60" show-word-limit/></el-form-item>
      <el-form-item label="所属城市"><el-select v-model="form.city" filterable allow-create default-first-option clearable placeholder="选择或输入归属城市" style="width:100%"><el-option v-for="city in cities" :key="city" :label="city" :value="city"/></el-select><small>统一保存城市名称；不改变实体编码和旅行目的地。</small></el-form-item>
      <el-form-item label="实体关联编码（发布后固定）"><el-input v-model="form.code" :readonly="!!form.id&&form.status!=='new'" maxlength="32" placeholder="可留空自动生成；已有实体须填写对应 IP 编码"/><small>这是角色业务编码，区别于上方 IP ID 和每个实体的完整 NFC 编码。</small></el-form-item>
      <el-form-item label="简介（选填）"><el-input v-model="form.description" type="textarea" maxlength="500" show-word-limit/></el-form-item>
      <el-form-item label="小窝背景图">
        <BrandAssetPicker v-model="form.backgroundAssetId" background :disabled="saving"/>
        <small>保存草稿后发布更新，才会在该 IP 的小窝显示。</small>
      </el-form-item>
    </el-form>
    <el-alert v-if="formError" :title="formError" type="error" :closable="false"/>
    <template #footer><el-button @click="closeEdit(()=>editOpen=false)" :disabled="saving">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
  </el-dialog>
  <BrandPreview v-model="previewOpen" :item="previewItem"/>
  <el-drawer v-model="historyOpen" title="版本记录" size="min(820px,95vw)">
    <p>{{historyItem?.name}} · 回滚只恢复内容，不改变当前上下架状态。</p>
    <el-table :data="history" row-key="release_id">
      <el-table-column prop="created_at" label="时间" min-width="180"/>
      <el-table-column label="动作" width="100">
        <template #default="{row}">{{actionLabel[row.action]||row.action}}</template>
      </el-table-column>
      <el-table-column label="版本 ID" min-width="155">
        <template #default="{row}">
          <el-tooltip :content="row.release_id">
            <el-button link :aria-label="'复制版本 ID '+row.release_id" @click="copy(row.release_id,'版本 ID')">
              {{row.release_id.slice(0,12)}}… 复制
            </el-button>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="nick_name" label="操作者" min-width="100"/>
      <el-table-column label="操作" min-width="100">
        <template #default="{row}">
          <el-button v-if="canBrand('rollback')" link type="primary" :disabled="busy" @click="rollback(row)">回滚内容</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-drawer>
</div>
</template>
<script setup>
import {ElMessage,ElMessageBox} from 'element-plus'
import {api} from '@/api/operations'
import {brandDetail,brandPayload,brandAction,canBrand,pendingBrandAction,retryBrandAction} from '@/api/brand-ip'
import BrandImage from './components/BrandImage.vue'
import BrandPreview from './components/BrandPreview.vue'
import BrandAssetPicker from './components/BrandAssetPicker.vue'
const route=useRoute(),router=useRouter(),items=ref([]),cities=ref([]),total=ref(0),loading=ref(false),busy=ref(false),error=ref(''),pending=ref(pendingBrandAction())
const filters=reactive({query:String(route.query.query||''),city:String(route.query.city||''),status:String(route.query.status||''),page:Math.max(1,Number(route.query.page)||1)})
const statusLabel={new:'未上架',listed:'已上架',delisted:'已下架'},actionLabel={list:'上架',delist:'下架',publish:'发布更新',rollback:'回滚'}
const editOpen=ref(false),saving=ref(false),form=ref({}),formError=ref('');let formOriginal=''
const previewOpen=ref(false),previewItem=ref(null),historyOpen=ref(false),history=ref([]),historyItem=ref(null)
async function load(){loading.value=true;error.value='';try{const r=await api('/brand-ips?'+new URLSearchParams({...filters,size:20}));items.value=r.items;total.value=r.total;cities.value=r.cities}catch(e){error.value=e.message}finally{loading.value=false}}
async function searchPage(){await router.replace({query:{...filters,page:String(filters.page)}});await load()}
function search(){filters.page=1;searchPage()}function reset(){Object.assign(filters,{query:'',city:'',status:'',page:1});searchPage()}
async function copy(value,label='IP ID'){try{await navigator.clipboard.writeText(value);ElMessage.success('已复制完整 '+label)}catch{ElMessage.warning('复制失败，请从提示中选择完整 ID')}}
async function edit(row){formError.value='';try{form.value=row?await brandDetail(row.id):{name:'',description:'',city:'',coverAssetId:'',backgroundAssetId:''};formOriginal=JSON.stringify(form.value);editOpen.value=true}catch(e){error.value=e.message}}
async function closeEdit(done){if(saving.value)return;if(JSON.stringify(form.value)!==formOriginal){try{await ElMessageBox.confirm('修改尚未保存，确定关闭？','未保存修改',{type:'warning',confirmButtonText:'放弃修改',cancelButtonText:'继续编辑'})}catch{return}}done()}
async function save(){if(!form.value.name?.trim()){formError.value='请填写 IP 名称。';return}saving.value=true;formError.value='';try{const item=form.value,payload=brandPayload(item);await api('/brand-ips'+(item.id?'/'+item.id:''),{method:item.id?'PUT':'POST',body:item.id?{expectedVersion:item.draftVersion,content:payload}:payload});editOpen.value=false;ElMessage.success('草稿已保存，正式内容尚未改变');await load()}catch(e){formError.value=e.message}finally{saving.value=false}}
function expressions(row){sessionStorage.setItem('brand-ip-list-scroll',String(window.scrollY));router.push({name:'BrandExpressions',params:{id:row.id},query:{...filters,page:String(filters.page)}})}
async function operate(row,action,releaseId){busy.value=true;error.value='';try{await brandAction(row,action,releaseId);ElMessage.success(action==='list'&&row.status!=='new'&&row.dirty?'已上架，仍使用最近正式版本，草稿未发布':actionLabel[action]+'成功');await load()}catch(e){error.value=e.message}finally{busy.value=false;pending.value=pendingBrandAction()}}
async function retry(){busy.value=true;try{await retryBrandAction();ElMessage.success('已确认操作成功');await load()}catch(e){error.value=e.message}finally{busy.value=false;pending.value=pendingBrandAction()}}
async function more(row,command){try{if(command==='publish'){await operate(row,'publish');return}if(command==='preview'){await api('/brand-ips/'+row.id+'/preview',{method:'POST'});previewItem.value=await brandDetail(row.id);previewOpen.value=true}else if(command==='history'){historyItem.value=row;history.value=await api('/brand-ips/'+row.id+'/history');historyOpen.value=true}else if(command==='delete'){await ElMessageBox.confirm('仅删除未发布草稿，不删除素材原文件。确定删除？','删除草稿',{type:'warning'});await api('/brand-ips/'+row.id+'?expectedVersion='+encodeURIComponent(row.draftVersion),{method:'DELETE'});await load()}}catch(e){if(e!=='cancel'&&e!=='close')error.value=e.message}}
async function rollback(release){try{await ElMessageBox.confirm('恢复版本 '+release.release_id+' 的内容。当前上下架状态与草稿会保留，确定继续？','回滚内容',{type:'warning'});const row=await brandDetail(historyItem.value.id);await operate(row,'rollback',release.release_id);history.value=await api('/brand-ips/'+row.id+'/history')}catch(e){if(e!=='cancel'&&e!=='close')error.value=e.message}}
onMounted(async()=>{await load();await nextTick();window.scrollTo(0,Number(sessionStorage.getItem('brand-ip-list-scroll')||0))})
</script>
<style scoped>
.brand-page{padding:24px;min-height:calc(100vh - 84px);background:#fff}.page-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px}.page-heading h2{font-size:22px;margin:0 0 8px}.page-heading p{margin:0;color:#7b8492;font-size:14px}.filters{padding:18px 18px 0;background:#f7f9fc;border:1px solid #edf0f5;border-radius:6px;margin-bottom:18px}.pagination{display:flex;justify-content:space-between;align-items:center;margin-top:20px;color:#7b8492;font-size:14px}.el-alert{margin-bottom:16px}.el-dropdown{margin-left:8px}small{display:block;color:#909399;line-height:1.8}.el-form-item :deep(.picker){width:100%}@media(max-width:700px){.brand-page{padding:14px}.page-heading p{max-width:220px}.filters{padding:10px}.pagination{gap:10px;flex-wrap:wrap}}
</style>
