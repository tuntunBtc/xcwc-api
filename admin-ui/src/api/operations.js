import { getToken } from '@/utils/auth'
const messages={
 ADMIN_LOGIN_REQUIRED:'登录已失效，请重新登录。',ADMIN_PERMISSION_DENIED:'当前账号没有操作权限。',CONTENT_VERSION_CONFLICT:'内容已被其他操作更新，请刷新后重新编辑。',IDEMPOTENCY_CONFLICT:'这次操作的内容已变化，请刷新后重试。',FREE_DEFAULT_APPEARANCE_REQUIRED:'每个角色必须设置一套可领取的免费默认造型。',INVALID_ASSET_ID:'有素材槽位尚未选择图片，请补齐后再预览或发布。',ASSET_NOT_FOUND:'找不到引用的素材，请重新选择。',ASSET_UNAVAILABLE:'素材缺失或已损坏，请重新上传并替换引用。',ASSET_STILL_REFERENCED:'素材仍被草稿、发布历史或有效预览引用，不能移除。',ASSET_LICENSE_REQUIRED:'请填写素材来源与授权说明。',ASSET_SIZE_INVALID:'请上传非空且不超过 10 MB 的图片。',ASSET_DIMENSIONS_INVALID:'图片尺寸超过限制，请先生成适合展示的优化文件。',ASSET_FORMAT_UNSUPPORTED:'当前支持 PNG 和 JPEG 图片。',ASSET_CORRUPT:'无法读取该图片，请检查文件后重新上传。',INVALID_ANIMATION_GRID:'图集的行列、帧数或图片尺寸不匹配。',PRODUCT_DETAILS_REQUIRED:'请至少选择一张商品详情图。',INVALID_PRODUCT_PRICE:'商品价格须为非负金额，最多两位小数。',ACCESSORY_COMPATIBILITY_REQUIRED:'请为饰品至少配置一套适配造型。',INVALID_ACCESSORY_COMPATIBILITY:'饰品的角色与造型不匹配，请检查适配配置。',EXISTING_COMPATIBILITY_MUST_REMAIN:'已发布的适配关系不能直接移除，请保留用户已有使用权。',PUBLISHED_ITEM_CANNOT_BE_DELETED:'已发布的角色或装扮不能删除，可关闭新领养或新获取。',PUBLISHED_IDENTITY_CANNOT_CHANGE:'已发布的角色编码、默认造型或装扮归属不能更换。',DUPLICATE_CHARACTER_CODE:'角色编码重复，请使用不同编码。',PUBLISH_CHARACTER_FIRST:'请先发布该角色，再关联实体。',ENTITY_CHARACTER_IMMUTABLE:'已有实体关联不能直接改绑。',EXISTING_IP_CODE_MISMATCH:'角色编码与实体已有 IP 编码不一致，请核实后再关联。',ADMIN_USERNAME_TAKEN:'此账号名称已被使用。',ADMIN_PASSWORD_INVALID:'密码至少 12 个字符，且 UTF-8 编码不超过 72 字节。',CURRENT_PASSWORD_INCORRECT:'当前密码不正确。',CANNOT_DISABLE_SELF_OR_OWNER:'不能停用自己或初始管理员。'
}
export async function api(path,options={}) {
 const headers={Authorization:'Bearer '+getToken(),...options.headers};
 if(options.body && !(options.body instanceof FormData)){headers['Content-Type']='application/json';options.body=JSON.stringify(options.body)}
 const response=await fetch('/api/admin'+path,{...options,headers,signal:AbortSignal.timeout(20000)});
 if(!response.ok){const e=await response.json().catch(()=>({message:'请求失败'}));const error=new Error(messages[e.code]||messages[e.message]||'操作未完成，请检查必填内容和素材配置后重试。');error.code=e.code;throw error}
 return response.status===204?null:response.json()
}
export const id=()=>crypto.randomUUID().replaceAll('-','');
export async function assetUrl(assetId){if(!assetId)return '';const r=await fetch('/api/admin/assets/'+assetId,{headers:{Authorization:'Bearer '+getToken()}});if(!r.ok)throw new Error('素材加载失败');return URL.createObjectURL(await r.blob())}
