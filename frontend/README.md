# EventFlow 前端

Vue 3 + TypeScript 的同源 Web 客户端。浏览器请求 `/api/v1`，Vite 开发服务器将 API 转发到本地 Spring Boot；完整部署时由 Nginx 代理相同路径。

```powershell
$env:TEMP = 'D:\JavaWeb_Project\.build-cache\frontend\tmp'
$env:TMP = $env:TEMP
$env:npm_config_cache = 'D:\JavaWeb_Project\.build-cache\frontend\npm'
pnpm install --frozen-lockfile --store-dir 'D:\JavaWeb_Project\.build-cache\pnpm-store'
pnpm dev
```

前端只保存当前会话的 access token 到 Pinia 内存。刷新页面后需重新登录。订单幂等键按登录用户和票种放入 `sessionStorage`；会话存储可用时，网络超时或同标签页刷新后可以安全重放同一预约请求，成功收到订单后移除该键。若浏览器禁用了会话存储，键只留在当前页面内存，页面刷新后无法保证复用；遇到结果不明确时应先查看订单，不要直接重复预约。随机核销码只存在于当前票页的内存中，离开或刷新页面即清除。

常用检查：`pnpm typecheck`、`pnpm lint`、`pnpm format:check`、`pnpm test`、`pnpm build`。
