# DHU Secondhand Market

DHU 校园二手闲置物品交易系统 —— 《工程项目管理》课程项目（3 人团队，vibe coding）。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.2 + Spring MVC |
| ORM | MyBatis-Plus 3.5 |
| 数据库 | MySQL 8.0 |
| 缓存/消息 | Redis 7（缓存 + 延迟队列） |
| 前端 | Thymeleaf + Bootstrap 5 |
| 构建 | Maven 3.8+，JDK 17 |

## 功能模块

- **用户模块**：注册、登录/退出、个人资料、个人主页
- **商品模块**：发布/编辑/下架、图片上传、分类、搜索、详情、收藏
- **交易模块**：下单、订单状态流转、我的买入/卖出、取消订单
- **评价/留言**：交易后互评、商品留言（加分）
- **后台管理**：用户管理、商品审核（加分）

## 三人分工

| 成员 | 分支 | 模块 | 技术亮点 |
|------|------|------|----------|
| A（组长） | `feature/user` | 用户模块 + 工程基础 | Redis 缓存（穿透/击穿/雪崩） |
| B | `feature/goods` | 商品模块 | 关键词搜索、慢查询优化 |
| C | `feature/order` | 交易模块 + 评价 | 防超卖、订单超时取消 |

## 目录结构

```
campus-secondhand/
├── docs/                    # 设计文档、接口契约
│   └── 01-项目设计文档.md
├── src/                     # 源码（开发中）
├── sql/                     # 建表脚本（开发中）
├── .gitignore
└── README.md
```

## 本地运行

```bash
# 1. 准备 MySQL 8，新建数据库 campus_secondhand
# 2. 建表（sql/init.sql，开发后提供）
# 3. 配置数据库连接（见 application.yml）
# 4. 启动
mvn spring-boot:run
```

访问 http://localhost:8080

## 文档

- [项目设计文档](docs/01-项目设计文档.md)

## 协作规范

- **分支**：`main` 只放能跑通的代码；每人一个功能分支，靠 Pull Request 合并。
- **提交信息**：`feat: xxx` / `fix: xxx` / `docs: xxx` / `refactor: xxx`。
- **谁负责哪个模块，谁负责把它跑通**（vibe coding 底线）。
- 数据库密码不要提交（用 `application-local.yml`，已被 .gitignore 忽略）。
