# T-SYNCGIT-P3B 框架端

已完成：框架实现与隔离验收。

## 实现计划

1. 从 bebf552 实现通用资源日志。
2. 应用与资源日志共用短事务。
3. 删除正文前，应用必须保留占位。
4. 资源查询每批最多 32 项。
5. 应用回调复核续做的删除授权。
6. 原生层返回实际释放字节数。
7. 执行框架全量测试与合成模拟器验收。

## 改动

- `packages/framework/src/resource-journal.ts`：分页、引用、退休状态与持久配额。
- 退休资源不得重新附加。
- 同资源同元数据幂等。
- 同资源不同元数据返回失败。
- 应用提供事务外授权回调。
- 应用在自己的 CAS 事务中批准退休。
- 资源日志保留游标。失败候选不得阻塞后续候选。
- `PodContentFiles.java`：锁内计算 `freedBytes`。重放返回 0。
- `PodDeviceServices.java`：`file.stat` 返回 `usableBytes`。
- `docs/getting-started.md`：登记导出与调用约束。

## 共同条件

- 不选择业务清理策略。
- 不解释云 wire。
- 不改其他仓库主工作区。
- 不操作真实账号、真机和生产。
- 崩溃准备占用的配额保守保留。
- 调用方必须核对暂存记录后再释放配额。

## 验证

- 已验证：框架全量 `bun run test`。命令退出 0。
- 已验证：新增 9 项资源日志测试。结果为 9 项通过。
- 已验证：Rust、JVM 和原生服务脚本。全量命令通过。
- 已验证：应用最终专项测试。结果为 110 项通过。
- 已验证：模拟器 `P3BSpaceGuestTest.spaceGuest`。结果为 `OK (1 test)`。
- 已验证：原生重复删除返回 `freedBytes=0`。
- 已验证：Android SQLite 拒绝配额超额申请。
- 未验证：物理手表与后端联调。任务卡未授权。

## 提交

- 框架代码：`849aa6f62bc87d03313cb4f10c616934f17b2de4`。
- 应用代码：`b4649b53ba3bf0eb14faff7e9fdf8bd82a2df137`。
- 报告提交只增加交付记录。

## 资源

- 元数据批量上限：32 项。
- 文件读取批量上限沿用 256 KiB。
- N07 正文加密段上限：64 KiB。
- 模拟器扫描 96 条记录。
- 元数据分页查询：4 次。
- 扫描全部 SQL 查询：8 次。
- 逐条查询基线：96 次。
- 扫描正文读取量：0 字节。
- 模拟器扫描耗时：76 ms。
- 模拟器进程峰值 RSS：157488 KiB。
- 该 RSS 包含 JVM 与原生宿主。
- QuickJS 内存上限：32 MiB。该值不是峰值测量。
- 最大宿主请求：155650 字节。

## 未做

- 不增加业务策略和后台定时器。
- 不自动释放崩溃准备占用的配额。
- 调用方必须核对暂存记录后再释放配额。
- 不推送、不合并、不部署。

## 证据

- `/home/wilf/data/OPPO_Develop/.codex-tmp/cloud-v2/evidence/p3b-watch/`。
- 应用交付：`reports/T-SYNCGIT-P3B-watch-evidence.json`。
- 首轮框架脚本使用仓库默认 Gradle 配置。
- 最终轮次使用本地 `org.gradle.workers.max=2`。
- 模拟器构建显式使用 `--max-workers=2`。
- Cargo 使用 `CARGO_BUILD_JOBS=4`。
- 本地限值配置不进入提交。

## 新测试：9 项

- `resource journal detaches owner before bounded physical collection`。
- `resource journal shared current and recovery owners protect a resource`。
- `resource journal lost deletion reply retries without double counting freed bytes`。
- `resource journal retired identities cannot be reattached or revived`。
- `resource journal immutable identity allows same metadata and rejects replacement`。
- `resource journal CAS rolls back stale metadata and reference detach together`。
- `resource journal pages metadata and resources at 32 items`。
- `resource quota reserves persistently and rejects overcommit`。
- `resource journal staging reference prevents collection before owner commit`。

