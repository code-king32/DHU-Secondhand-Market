# 组长验收包

用于本地后端演示、接口验收和前端联调。固定使用独立库 `dhu_secondhand_acceptance`，建议后端运行于 `http://localhost:8081`。初始化通过 SQL 准备已认证的演示账号，接口仍使用正常的密码登录、Session 和角色校验。

## 1. 初始化测试库

需要 JDK 17、Maven、MySQL 8.0 客户端及 PowerShell 7。在仓库根目录执行：

```powershell
./tools/acceptance/Manage-Acceptance.ps1 -Action Init -DatabaseUser root
```

密码由 MySQL 客户端交互读取，不填进脚本。如果 `mysql` 不在 PATH 中，追加 `-MySql 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'`；其他连接可指定 `-DatabaseHost`、`-DatabasePort`。

`Init` 创建独立库，复用仓库 `sql/01` 至 `07` 的结构，随后写入样例。若同名库已存在，初始化直接失败，不覆盖。执行中途失败时先检查 MySQL 错误和权限；脚本不会自动删除已建库。正常已初始化的库使用 `Reset` 恢复。

## 2. 启动验收后端

先按[项目 README](../../README.md)准备自己的 `application-local.properties`，本地凭据不提交。随后在仓库根目录执行：

```powershell
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--spring.datasource.url=jdbc:mysql://localhost:3306/dhu_secondhand_acceptance?serverTimezone=Asia/Shanghai --server.port=8081"
```

连接地址覆盖为验收库，用户名和密码仍来自自己的本地配置。MySQL 主机、端口应与上一步一致。预置账号和请求集合不调用发信接口，不要求实际收件；校园邮箱验证码和真实注册需另按[验收清单](../../docs/06-联调验收清单.md)验证。

## 3. 账号和样例

以下均为本地演示身份，不代表真实邮箱，不能用于收件。统一演示密码为 `DhuDemo123!`，只用于此独立测试库。

| 身份 | ID | 邮箱 | 角色 |
|---|---|---|---|
| 卖家 | 1 | demo.seller@mail.dhu.edu.cn | USER |
| 买家 | 2 | demo.buyer@mail.dhu.edu.cn | USER |
| 管理员 | 3 | demo.admin@mail.dhu.edu.cn | ADMIN |
| 其他用户 | 4 | demo.other@mail.dhu.edu.cn | USER |

基线数据包含5件商品，分别为在售、下架、交易中、售出、管理隐藏；交易1已接受、交易2已完成并有双方评价、交易3待接受。另有待处理举报、收藏和开放求购各1条。卖家用 `/api/goods/mine` 看完整状态，公开列表只显示在售商品。

管理员由初始化脚本在验收库中预置；正常注册接口仍只能创建普通用户。不要将这些固定账号和演示密码导入正式使用的库。

## 4. 运行请求集合

在 Postman 中导入同目录下的：

- `DHU-Acceptance.postman_collection.json`
- `DHU-Acceptance.postman_environment.json`

选择“DHU 本地验收”环境，核对 `baseUrl`，使用默认 Cookie jar，按顺序运行完整“DHU 组长验收”集合。不要启用禁用 Cookie 的选项，也不要并发运行同一集合。集合通过明确的退出、登录请求切换身份，自动记录本轮新建数据编号，无需手动复制 Cookie 或编号。

54个请求覆盖：未登录拒绝访问、发布、普通用户管理权限限制、越权修改拒绝、收藏幂等、交易申请及重复拒绝、接受、双方确认、双向评价及重复拒绝、求购关闭、举报处理、申诉重开、审计、账号禁用与恢复、退出会话。每个请求都包含状态码断言，关键步骤还检查状态或返回数据。

集合可重复运行，每轮新建商品和交易，结果保留在验收库中；单独运行中间请求前，需要先完成对应登录及前置步骤。若中途失败，先查看失败断言，再按第5节重置，恢复环境后完整重跑。

不安装 Postman 时，也可使用 Node.js 22 或更新版本执行附带的轻量运行器，无需安装 npm 包：

```powershell
node tools/acceptance/run-collection.mjs
# 自定义本地端口：
node tools/acceptance/run-collection.mjs --base-url http://localhost:8081
```

运行器读取同一集合及环境，执行其中响应断言并维护 Session Cookie；仅支持本集合用到的接口，不是完整 Postman 运行时。当前 HTTP/SQL 自动回归使用此运行器；Postman 导入及其界面操作需组长本机确认。

## 5. 重置与清理

先停止连接验收库的后端，避免执行中的请求与重置相互影响，再在仓库根目录执行：

```powershell
# 清除本库所有业务数据并恢复基线账号、商品及交易
./tools/acceptance/Manage-Acceptance.ps1 -Action Reset -DatabaseUser root

# 只清除本库业务数据，保留表结构和验收标记
./tools/acceptance/Manage-Acceptance.ps1 -Action Clean -DatabaseUser root
```

操作后重新启动验收后端。`Clean` 后需要 `Reset` 才能重新使用演示账号，不能重复 `Init`。

两个操作固定选择 `dhu_secondhand_acceptance`，并在事务中检查 `_acceptance_meta` 标记后才清理业务数据；标记缺失或不匹配时拒绝执行。重置会推进账号会话版本，避免复用账号 ID 后使旧会话重新生效。脚本不接受其他目标库名，不删除数据库，也不修改日常开发库 `dhu_secondhand`。本库中的手工测试结果会被清除，需要保留的验收记录请先导出。

## 6. 自动回归入口

已配置正常 `local` 连接且安装 Node.js 后，在 `backend` 目录执行：

```powershell
mvn "-Dtest=ManualAcceptanceKitTests" "-Dacceptance.kit.test.enabled=true" test
```

此测试显式创建独立验收库；库已存在时只接管带有正确标记的库并重置。随后在随机 HTTP 端口运行请求集合，检查样例、重复播种拒绝、错误标记拒绝清理、重置后的旧会话失效，以及日常库用户数未变化。结束后保留干净的基线样例。使用邮件替身，不发送邮件；默认 `mvn test` 跳过此项。该命令会重置验收库中的旧结果。

这份包用于后端验收；前端页面、浏览器 Cookie/CORS、实际收件和正式部署仍按[联调验收清单](../../docs/06-联调验收清单.md)单独记录。
