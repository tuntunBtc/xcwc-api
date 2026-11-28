// Run from admin-ui. Public mode explicitly selects the protected HTTPS test routes.
import path from 'node:path'
import { build, preview } from 'vite'

const mode = process.env.NEST_TEST_UPSTREAM || 'local'
if (!['local','public-test'].includes(mode)) throw Error('测试上游须为 local 或 public-test')
const remote = mode === 'public-test'
const backendPort = Number(process.env.NEST_TEST_BACKEND_PORT || 18946)
const uiPort = Number(process.env.NEST_TEST_UI_PORT || (remote ? 18949 : 18948))
for (const port of [backendPort,uiPort]) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw Error('测试端口无效')
}
const local = 'http://127.0.0.1:'+backendPort
const hex = '[a-f0-9]{32}'
const adminPrefix = '/test/admin/v1/'
const mediaPath = new RegExp('^/test/bird/v1/oss/uploads/'+hex+'\\.(png|jpg|jpeg|webp|mp4)$')
const methods = {
  GET: new RegExp('^(?:captchaImage|getInfo|getRouters|assets/'+hex+'(?:/thumbnail)?|bird/brand-ips(?:/'+hex+'(?:/nest-interactions)?)?)$'),
  POST: new RegExp('^(?:login|logout|oss/uploads|bird/brand-ips/'+hex+'/nest-interactions/publish)$'),
  PUT: new RegExp('^bird/brand-ips/'+hex+'/nest-interactions$')
}
function deny(_req,res){res.statusCode=404;res.end();return false}
const admin = {
  target:remote ? 'https://47.114.216.18' : local, changeOrigin:true,secure:true,followRedirects:false,proxyTimeout:220000,timeout:230000,
  bypass(req,res) {
    const pathname=req.url.split('?')[0], rule=methods[req.method]
    if (!pathname.startsWith(adminPrefix) || !rule?.test(pathname.slice(adminPrefix.length))) return deny(req,res)
  },
  rewrite:url=>remote ? url : url.slice('/test'.length)
}
const media = {
  target:remote ? 'https://api.xinchengip.com' : local, changeOrigin:true,secure:true,followRedirects:false,
  bypass(req,res) {
    if (!['GET','HEAD'].includes(req.method) || !mediaPath.test(req.url.split('?')[0])) return deny(req,res)
  },
  configure(proxy) {
    proxy.on('proxyReq',req=>{
      req.removeHeader('Authorization');req.removeHeader('Cookie');req.removeHeader('X-Preview-Token')
    })
  }
}
process.env.VITE_NEST_INTERACTIONS_TEST = 'true'
process.env.VITE_APP_BASE_API = '/test/admin/v1'
const outDir = path.resolve(process.env.NEST_TEST_UI_DIST || '../../output/nest-server-admin-dist')
if (!process.argv.includes('--serve-built')) await build({build:{outDir}})
const app=await preview({
  build:{outDir},preview:{host:'127.0.0.1',port:uiPort,strictPort:true,open:false,proxy:{
    '/test/admin/v1/':admin,
    '/test/bird/v1/oss/uploads/':media,
    '^/(?:test/)?(?:admin|bird)/v1(?:/|[?]|$)':{target:local,bypass:deny}
  }}
})
console.log(remote ? '上游：公网独立测试服务（验证 HTTPS 证书）' : '上游：本机独立测试服务')
app.printUrls()
