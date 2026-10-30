# MySQL 脚本入口

新建开发库从仓库根目录执行 `./tools/Initialize-Database.ps1`；先加 `-Preview` 可查看完整 SQL。独立演示库使用 [Manage-Acceptance.ps1](../tools/acceptance/Manage-Acceptance.ps1)，两个初始化入口共用 [schema-manifest.json](schema-manifest.json)。

历史编号脚本保持原路径，供已有环境升级及 MySQL 测试引用。初始化顺序、14张应用表、重复执行边界和手工 `SOURCE` 命令统一见[数据库与迁移](../docs/03-数据库与迁移.md)。

运行时辅助表仅维护在 [runtime-schema.sql](../backend/src/main/resources/sql/runtime-schema.sql)。不要将测试目录中的 H2 SQL 用于 MySQL，也不要在已有库上重复执行整套初始化。
