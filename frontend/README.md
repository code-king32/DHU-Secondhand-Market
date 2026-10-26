# DHU 校园二手市场 · 前端工程

前端使用 Vue 3 + TypeScript + Vite 构建，配合后端 Spring Boot（日常开发库 8080 / 验收库 8081）通过同源 `/api` 代理联调。

## 一、环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| Node.js | `^20.19.0` 或 `>=22.12.0`（推荐 LTS 22/24） | Vite 8 的硬性要求 |
| npm | 随 Node.js 自带 | 也可用 pnpm / yarn |

后端联调另需 JDK 17+、Maven、MySQL 8.0（见根目录 `README.md`）；只看页面的话可不装。

## 二、安装工具 / 插件

唯一的硬性依赖是 **Node.js**；IDE 只影响编辑体验，不影响能否运行。

1. 安装 Node.js：https://nodejs.org/ 下载 **LTS** 版，一路默认安装。

```bash
node -v   # 需为 20.19+ 或 22.12+
npm -v
```

### 用 IntelliJ IDEA（本项目默认）

1. 建议使用 **IntelliJ IDEA Ultimate**：Vue.js 插件已内置，Vue/TS 智能提示完整。Community 版不含 Vue 插件，只能编辑文件 + 用内置 Terminal 跑，提示会缺失。
2. 确认 Vue.js 插件已启用：`Settings → Plugins → Installed` 搜 **Vue.js**（Ultimate 已内置；没有则在 Marketplace 安装并重启）。**不需要**装 Volar——那是 VS Code 的插件。
3. 配置 Node：`Settings → Languages & Frameworks → Node.js`，确认 Node interpreter 指向已安装的 Node（一般自动检测到）。
4. 安装依赖：底部 `Terminal` 里 `cd frontend && npm install`，或在 `package.json` 上右键 → `Run 'npm install'`。
5. 启动 dev server（二选一）：
   - 最简单：底部 `Terminal` 里 `npm run dev`；
   - 或 `Run → Edit Configurations → + → npm`，`package.json` 选 frontend 下的，`Command` 填 `run dev`，点绿色运行按钮，日志显示在 Run 窗口。

### 用 VS Code（可选）

用 VS Code 打开后会提示安装推荐扩展（`.vscode/extensions.json` 已配好）：**Vue - Official**（ID `Vue.volar`）；⚠️ 若装过旧版 **Vetur** 请卸载，二者冲突。

## 三、获取项目并安装依赖

```bash
git clone <仓库地址>
cd DHU-Secondhand-Market/frontend

# 安装依赖，生成 node_modules（该目录已被 .gitignore 忽略，不要提交）
npm install
```

## 四、配置后端代理

前端不直接连数据库，也不在代码里写死后端地址，而是统一请求同源 `/api`，由 Vite 转发到后端（避开跨域，并保证登录 Cookie 同源）。

编辑 `vite.config.ts` 里的 `target`：

```ts
'/api': {
  target: 'http://localhost:8080', // 日常开发库用 8080；验收库演示账号改成 8081
  changeOrigin: false,
},
```

## 五、启动

需要两个终端：

```bash
# 终端 1 —— 后端（在 backend 目录，需先按根目录 README 配好 application-local.properties 并启动 MySQL）
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local"

# 终端 2 —— 前端（在 frontend 目录）
npm run dev
```

浏览器打开 Vite 输出的地址（默认 http://localhost:5173，端口被占用时会自动 +1）。

> 只看页面不连后端时前端也能启动，但接口会报网络错误。

## 六、目录结构

```
frontend/
├── src/
│   ├── api/        # axios 封装（http.ts）与各模块接口
│   ├── stores/     # Pinia 状态（登录态等）
│   ├── router/     # 路由与页面权限守卫
│   ├── layouts/    # 整体布局
│   ├── views/      # 页面（含 admin/ 管理页）
│   ├── constants.ts
│   ├── main.ts
│   └── style.css
├── index.html
├── vite.config.ts
└── package.json
```

## 七、常用命令

| 命令 | 作用 |
|------|------|
| `npm run dev` | 启动开发服务器（热更新） |
| `npm run build` | 类型检查 + 生产构建（`vue-tsc -b && vite build`） |
| `npm run preview` | 本地预览构建产物 |

## 八、协作注意事项

- 所有请求经 `src/api/http.ts` 发出，已开启 `withCredentials: true`，靠 `JSESSIONID` Cookie 保持登录态；**不要**在页面里手动拼接 Cookie。
- 只请求同源 `/api`，不要在代码里写死 `http://localhost:8080`。
- `node_modules/`、`dist/`、`.env.local` 等已被 `.gitignore` 忽略，提交时不要 `git add` 它们。
- 本地代理端口与验收库演示账号见根目录 `tools/acceptance/README.md`；接口文档见根目录 `docs/`。
- 提交信息遵循仓库规范：`feat:` / `fix:` / `docs:` / `refactor:` 前缀。
