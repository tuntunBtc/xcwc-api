import { createWebHistory, createRouter } from 'vue-router'
import Layout from '@/layout'
export const constantRoutes = [
 {path:'/login',component:()=>import('@/views/login.vue'),hidden:true},
 {path:'/',component:Layout,redirect:'/index',children:[{path:'index',component:()=>import('@/views/operations/content.vue'),name:'Index',meta:{title:'内容管理',icon:'edit',affix:true}}]},
 {path:'/user',component:Layout,hidden:true,children:[{path:'profile/:activeTab?',name:'Profile',component:()=>import('@/views/operations/account.vue'),meta:{title:'账号与权限'}}]},
 {path:'/401',component:()=>import('@/views/error/401.vue'),hidden:true},
 {path:'/:pathMatch(.*)*',component:()=>import('@/views/error/404.vue'),hidden:true}
]
export const dynamicRoutes=[]
export default createRouter({history:createWebHistory('/admin/'),routes:constantRoutes,scrollBehavior:()=>({top:0})})
