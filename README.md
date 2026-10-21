# DHU Secondhand Market

DHU 校园二手交易平台，“咸鱼翻新队”三人团队课程项目。支持校园邮箱认证、商品发布与检索、线下交易、信誉评价及举报申诉。

当前仓库以后端 REST API 为主，核心业务已有实现和自动化测试，并提供接口验收包。前端工程尚未纳入本仓库，前后端联调及团队独立验收结果待登记。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17、Spring Boot 3.5.16、Spring MVC |
| 数据访问 | MyBatis-Plus 3.5.17、JdbcTemplate |
| 数据库 | MySQL 8.0 |
| 认证 | Session Cookie、校园邮箱验证码、BCrypt 哈希 |
| 邮件 | Spring Boot Mail、SMTP |
| 测试 | JUnit 5、Spring Boot Test、MockMvc、H2 |
| 构建 | Maven |
| 前端规划 | Vue 3 + TypeScript + Vite，独立开发 |

## 功能模块

- **身份与账号**：校园邮箱验证码、注册、登录/退出、本人信息、昵称修改、公开资料、账号注销。
- **商品管理**：发布、编辑、上下架、分类、关键词搜索、分页、公开详情与本人管理。
- **线下交易**：买家申请、卖家接受、买卖双方确认完成、取消、本人买入/卖出记录。
- **信誉与治理**：交易互评、信誉摘要、举报、申诉、管理员处理、账号禁用/恢复、商品隐藏及审计。
- **扩展功能**：收藏、求购、教材分类筛选。

商品图片目前保存 HTTP/HTTPS 地址，尚未提供文件上传。交易在线下完成，当前不涉及在线支付、物流或实时聊天。

## 三人分工

团队按前端、后端、测试与建议分工协作：

| 成员 | 职责 | 协作状态 |
|------|------|------|
| A（组长） | 项目统筹、独立测试与问题建议、质量保障、集成与部署 | 接收验收材料，组织独立测试及交付 |
| B | 前端页面、交互、接口调用及前端测试 | 对接接口，维护前端工程与联调进度 |
| C | 数据库、后端架构、业务接口、权限与事务、接口测试 | 提供后端实现、接口及验收材料 |

后端自动回归是交接证据，组长负责独立运行接口用例、登记缺陷和改进建议，并组织前端联调与部署验收。当前架构与交接约定见[项目设计文档](docs/01-项目设计文档.md)。

## 目录结构

```
DHU-Secondhand-Market/
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/dhu/secondhand/
│       │   ├── identity/    # 邮箱认证、注册与登录
│       │   ├── account/     # 账号与用户资料
│       │   ├── marketplace/ # 商品与线下交易
│       │   ├── community/   # 信誉、治理、收藏与求购
│       │   └── common/      # 会话、异常与 Web 配置
│       ├── main/resources/ # 应用配置
│       └── test/           # 自动化测试与测试建表脚本
├── docs/                   # 课程任务书、设计文档、接口说明
├── sql/                    # MySQL 建表与迁移脚本
├── tools/acceptance/        # 独立测试库、演示账号、Postman 验收集合
├── .gitignore
└── README.md
```

## 本地运行

### 1. 准备环境与数据库

安装 JDK 17、Maven 和 MySQL 8.0。先在仓库根目录打开终端，进入 MySQL 客户端（按本机实际账号替换 `root`）：

```powershell
mysql -u root -p
```

新环境在 MySQL 客户端执行：

```sql
CREATE DATABASE dhu_secondhand
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SOURCE sql/01_user.sql;
SOURCE sql/02_registration_email_code.sql;
SOURCE sql/03_user_role.sql;
SOURCE sql/04_marketplace.sql;
SOURCE sql/05_community.sql;
SOURCE sql/06_marketplace_visibility.sql;
SOURCE sql/07_session_version.sql;
```

`SOURCE` 的相对路径基于启动 MySQL 客户端时的目录。脚本包含 `USE dhu_secondhand`；已有数据库应先核对表结构，只执行缺失的迁移，不要重复执行整套脚本。`03` 和 `07` 添加字段，不能重复运行；`06` 更新商品状态约束，使旧表支持管理员隐藏状态。

### 2. 配置本地连接

新建 `backend/src/main/resources/application-local.properties`，填写本机数据库和 SMTP 配置。该文件已被 Git 忽略。

以下示例使用环境变量提供连接信息；也可在本地文件中替换为实际值。SMTP 示例对应 QQ 邮箱 SSL 连接：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/dhu_secondhand?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}

spring.mail.host=smtp.qq.com
spring.mail.port=465
spring.mail.username=${MAIL_USERNAME}
spring.mail.password=${MAIL_PASSWORD}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.ssl.enable=true
spring.mail.properties.mail.smtp.connectiontimeout=5000
spring.mail.properties.mail.smtp.timeout=5000
spring.mail.properties.mail.smtp.writetimeout=5000
```

使用环境变量时，在启动进程的环境中设置 `DB_USERNAME`、`DB_PASSWORD`、`MAIL_USERNAME`、`MAIL_PASSWORD`。QQ 邮箱的邮件密码填写 SMTP 授权码。使用其他发件服务时，按其要求调整 SMTP 主机、端口和加密方式。

### 3. 启动后端

在仓库根目录执行：

```powershell
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

默认地址为 `http://localhost:8080`。当前提供 JSON 接口，没有首页；可以在另一个终端查询商品列表：

```powershell
curl.exe -i "http://localhost:8080/api/goods"
```

登录后通过 Session Cookie 保持身份，使用 Postman 等工具联调时需保留 Cookie。注册邮箱限于 `mail.dhu.edu.cn` 和 `dhu.edu.cn`。

## 测试

组长快速验收可使用[组长验收包](tools/acceptance/README.md)：独立测试库、四类演示账号、多种商品/交易状态、Postman 集合及初始化/重置/清理脚本。演示身份通过正常登录使用，不改动日常开发库。

在 `backend` 目录执行：

```powershell
mvn test
```

默认测试使用 H2 和邮件替身，覆盖认证、账号、商品交易、信誉治理、权限、部分并发场景及 HTTP 会话，不依赖真实邮箱或 MySQL。真实数据库、发信和迁移测试默认跳过，H2 测试通过不代表真实环境已验收。

已配置本地连接且完成相应建表后，可单独运行真实 MySQL 测试：

```powershell
mvn "-Dtest=ManualDatabaseConnectionTests,ManualMarketplaceTests,ManualAcceptanceFlowTests" "-Ddb.test.enabled=true" test
```

上述测试使用邮件替身。数据库连接测试的写入会回滚；商品并发与接口闭环测试会创建独立随机数据，结束时按本次记录精确清理。接口闭环启动真实 HTTP 端口，为每个用户保持独立 Cookie，验证 MySQL 下跨请求提交的注册登录、交易、评价、治理和注销；浏览器页面及实际收件仍需单独验收。

真实邮件测试需额外在本地配置 `app.mail.test-recipient`，再执行：

```powershell
mvn "-Dtest=ManualMailSendTests" "-Dmail.test.enabled=true" test
```

邮件测试会向配置的收件地址发送固定测试验证码；它用于验证发信链路，实际注册流程使用随机验证码。

## 文档

- [课程任务书](docs/软件项目管理课程任务书.docx)：需求与分工依据。
- [身份认证与账号接口](docs/02-身份认证接口.md)：验证码、注册登录、Session、用户资料与账号注销。
- [商品与线下交易接口](docs/04-商品交易接口.md)。
- [信誉治理及扩展接口](docs/05-信誉治理及扩展接口.md)。
- [联调验收清单](docs/06-联调验收清单.md)：当前验证记录、手工流程及待确认业务规则。
- [组长验收包](tools/acceptance/README.md)：独立测试环境、样例账号、自动化请求及数据恢复操作。
- [项目设计与协作分工](docs/01-项目设计文档.md)：当前架构、团队分工、交接边界和里程碑。

## 协作规范

- **分支**：`main` 只放能跑通的代码；每人一个功能分支，靠 Pull Request 合并。
- **提交信息**：`feat: xxx` / `fix: xxx` / `docs: xxx` / `refactor: xxx`。
- **验收**：模块负责人验证正常流程、权限和异常场景；业务规则变更同步更新代码、测试与接口文档。
- **交接**：C提供接口及可复现测试，B对接前端，A独立验收、记录缺陷和建议；执行结果与联调进度及时登记。
- **本地配置**：数据库密码、邮箱授权码等仅保存在本地配置或环境变量中，不提交凭据。
