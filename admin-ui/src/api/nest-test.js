// A test build must explicitly use the separate test gateway before it can edit assets.
export const nestTest = import.meta.env.VITE_NEST_INTERACTIONS_TEST === 'true'
  && import.meta.env.VITE_APP_BASE_API === '/test/admin/v1'

// Both calls are read-only. A transient gateway failure can be retried without
// repeating login or a management write.
export async function readNestSession(resource, token) {
  if (!nestTest || !['getInfo','getRouters'].includes(resource)) throw Error('测试会话入口不可用')
  let lastError
  for (let attempt=0;attempt<3;attempt++) {
    try {
      const response=await fetch(import.meta.env.VITE_APP_BASE_API+'/'+resource,{
        headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(10000)
      })
      if (!response.ok) throw Object.assign(Error(resource+' 返回 HTTP '+response.status),{status:response.status})
      const data=await response.json()
      if (data.code!==200) throw Object.assign(Error('测试后台未能读取账号信息'),{status:Number(data.code)||400})
      return data
    } catch (error) {
      lastError=error
      if (error.status && error.status<500) throw error
      if (attempt<2) await new Promise(resolve=>setTimeout(resolve,300*(attempt+1)))
    }
  }
  throw lastError
}
