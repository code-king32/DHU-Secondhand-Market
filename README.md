# DHU Secondhand Market

DHU 校园二手交易平台，三人团队课程项目。支持校园邮箱认证、商品发布与检索、线下交易、信誉评价、举报申诉、收藏和求购。

后端核心业务已实现并有自动化测试。前端入口见 [frontend/README.md](frontend/README.md)；当前验证结果与未完成验收统一记录在[联调验收清单](docs/06-联调验收清单.md)。

## 技术与范围

| 项目 | 当前实现 |
|---|---|
| 后端 | Java 17、Spring Boot 3.5.16、Spring MVC |
| 数据访问 | MyBatis-Plus 3.5.17、JdbcTemplate |
| 数据库 | MySQL 8.0；默认测试使用 H2 |
| 认证 | 校园邮箱验证码、BCrypt、Session Cookie |
| 邮件与图片 | SMTP 持久化投递任务、本地 JPG/PNG 文件及引用管理 |
| 前端 | Vue 3、TypeScript、Vite，独立工程 |

交易在线下完成，当前不涉及在线支付、物流、实时聊天、Redis 或消息中间件。身份、状态流转和权限以接口及实现为准；待确认业务规则列在验收清单中。

## 快速启动后端

需要 JDK 17、Maven、MySQL 8.0；仓库 PowerShell 脚本要求 PowerShell 7。以下命令均从仓库根目录执行。

1. 为新环境初始化开发库：

   ```powershell
   ./tools/Initialize-Database.ps1 -DatabaseUser root
   ```

   MySQL 客户端交互读取密码。固定创建 `dhu_secondhand`，库已存在时拒绝初始化；已有库按[数据库与迁移](docs/03-数据库与迁移.md)核对增量结构。`-Preview` 只输出 SQL，不连接数据库。

2. 复制本地配置示例：

   ```powershell
   Copy-Item backend/config/application-local.properties.example backend/src/main/resources/application-local.properties
   ```

   已有本地配置时直接编辑原文件，保留自己的凭据。设置 `DB_USERNAME`、`DB_PASSWORD`、`MAIL_USERNAME`、`MAIL_PASSWORD` 环境变量；也可在被忽略的本地文件中填写连接信息。模板默认连接本机开发库和 QQ SMTP SSL，其他邮件服务按[运行与部署](docs/07-运行与部署.md)调整。

3. 启动服务：

   ```powershell
   cd backend
   mvn spring-boot:run "-Dspring-boot.run.profiles=local"
   ```

   默认地址为 `http://localhost:8080`，后端提供 API，不提供首页。检查公开接口：

   ```powershell
   curl.exe -i "http://localhost:8080/api/goods"
   ```

## 测试与验收

默认测试统一从仓库根目录运行：

```powershell
./tools/Test-Backend.ps1
```

仅使用本机已缓存的 Maven 依赖时可加 `-Offline`；`-Preview` 仅显示实际命令。默认套件显式关闭所有手动测试开关，使用 H2 和邮件替身，不连接真实 MySQL、不发信。

真实数据库、可靠性、验收包、迁移和邮件各有独立入口。参数、影响范围和最新结果见[联调验收清单](docs/06-联调验收清单.md)。准备演示账号和 Postman 请求集合见[组长验收包](tools/acceptance/README.md)。

## 文件职责

| 位置 | 用途 |
|---|---|
| `backend/src/main/java/com/dhu/secondhand/` | 认证、账号、商品交易、社区治理及公共支持 |
| `backend/src/main/resources/application.properties` | 可提交的公共默认配置，不含连接凭据 |
| `backend/config/` | 开发和生产配置示例，不自动加载 |
| `backend/src/main/resources/sql/runtime-schema.sql` | 邮件投递、图片归属和引用三张运行时表 |
| `backend/src/test/` | 自动化测试及独立 H2 建表脚本 |
| `sql/` | MySQL 业务建表与历史增量脚本，顺序见清单文件 |
| `tools/` | 开发库初始化、测试入口和独立验收工具 |
| `docs/` | 设计、接口、数据库、验收及部署说明 |

## 文档索引

- [项目设计](docs/01-项目设计文档.md)：当前架构、模块和统一接口约定。
- [身份认证与账号接口](docs/02-身份认证接口.md)。
- [数据库与迁移](docs/03-数据库与迁移.md)：14张应用表、初始化顺序和迁移边界。
- [商品与线下交易接口](docs/04-商品交易接口.md)。
- [信誉治理及扩展接口](docs/05-信誉治理及扩展接口.md)。
- [联调验收清单](docs/06-联调验收清单.md)：测试入口、当前状态和历史结果。
- [运行与部署](docs/07-运行与部署.md)：环境配置、生产运行、代理和备份。
- [后端审查与优化记录](docs/08-后端审查与优化记录.md)：2026-10-31确认问题、修复证据及后续优先级。
- [课程任务书](docs/软件项目管理课程任务书.docx)：需求与分工依据。

## 协作约定

A 负责统筹、质量保障、集成与部署；B 负责前端；C 负责数据库、后端架构、业务接口、权限事务和接口测试。业务规则变更同步修改实现、测试和对应文档。

使用功能分支和 Pull Request 协作，`main` 保持可运行；提交信息使用 `feat:`、`fix:`、`docs:` 或 `refactor:`。数据库密码、SMTP 授权码、本地生产配置、上传图片和密钥不提交。部署备份需同时覆盖数据库、图片目录及邮件任务密钥。
