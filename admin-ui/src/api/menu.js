import {nestTest,readNestSession} from '@/api/nest-test'
import {getToken} from '@/utils/auth'
import request from '@/utils/request'

// 获取路由
export const getRouters = () => {
  if(nestTest) return readNestSession('getRouters',getToken())
  return request({
    url: '/getRouters',
    method: 'get'
  })
}