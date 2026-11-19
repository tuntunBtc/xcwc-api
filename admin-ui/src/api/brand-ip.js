import { api, id } from './operations'
import useUserStore from '@/store/modules/user'
export const brandDetail = key => api('/brand-ips/'+encodeURIComponent(key))
export const canBrand = key => useUserStore().permissions.includes('brand:'+key)
const pendingKey = () => 'brand-ip-pending-'+useUserStore().id
export function pendingBrandAction(){try{return JSON.parse(sessionStorage.getItem(pendingKey())||'null')}catch{return null}}
export async function brandAction(item, action, releaseId){
  const pending=pendingBrandAction()
  if(pending && (pending.id!==item.id || pending.body.action!==action || pending.body.releaseId!==releaseId))throw new Error('上一次操作结果尚未确认，请先重试原操作。')
  const command=pending||{id:item.id,body:{action,requestId:id(),draftVersion:item.draftVersion,stateVersion:item.stateVersion,releaseId}}
  sessionStorage.setItem(pendingKey(),JSON.stringify(command))
  try{const result=await api('/brand-ips/'+encodeURIComponent(command.id)+'/actions',{method:'POST',body:command.body});sessionStorage.removeItem(pendingKey());return result}
  catch(error){if(error.code)sessionStorage.removeItem(pendingKey());throw error}
}
export async function retryBrandAction(){const p=pendingBrandAction();if(p)return brandAction({id:p.id},p.body.action,p.body.releaseId)}
export const brandPayload = item => ({code:item.code,name:item.name,description:item.description,city:item.city,coverAssetId:item.coverAssetId})
