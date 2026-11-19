import { createWebHistory, createRouter } from 'vue-router'
import Layout from '@/layout'
export const constantRoutes = [
 {path:'/login',component:()=>import('@/views/login.vue'),hidden:true},
 {path:'/',redirect:'/brand-ips/list',hidden:true},
 {path:'/index',redirect:'/brand-ips/list',hidden:true},
 {path:'/user',component:Layout,hidden:true,children:[{path:'profile/:activeTab?',name:'Profile',component:()=>import('@/views/operations/account.vue'),meta:{title:'账号与权限'}}]},
 {path:'/401',component:()=>import('@/views/error/401.vue'),hidden:true},
 {path:'/:pathMatch(.*)*',component:()=>import('@/views/error/404.vue'),hidden:true}
]
export const dynamicRoutes=[]
export default createRouter({history:createWebHistory('/admin/'),routes:constantRoutes,scrollBehavior:()=>({top:0})})
