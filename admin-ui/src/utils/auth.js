const key = 'xcwc-admin-token';
export const getToken = () => sessionStorage.getItem(key);
export const setToken = token => sessionStorage.setItem(key, token);
export const removeToken = () => sessionStorage.removeItem(key);
