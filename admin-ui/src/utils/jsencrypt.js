import JSEncrypt from 'jsencrypt/bin/jsencrypt.min'

// 不在前端仓库硬编码 RSA 密钥对；如启用前端非对称加密，由运行配置注入公钥。
const publicKey = import.meta.env.VITE_APP_RSA_PUBLIC_KEY || ''
const privateKey = ''

// 加密
export function encrypt(txt) {
  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(publicKey) // 设置公钥
  return encryptor.encrypt(txt) // 对数据进行加密
}

// 解密
export function decrypt(txt) {
  const encryptor = new JSEncrypt()
  encryptor.setPrivateKey(privateKey) // 设置私钥
  return encryptor.decrypt(txt) // 对数据进行解密
}

