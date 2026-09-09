# PodJS 手表平台能力补全计划

## Quota-stop handoff — 2026-09-09

用户设定的剩余额度 35% 已到达，停止开发并提交当前全部更改；计划未完成。
最新已完成验证为 Swift/Linux 93 项、Rust runtime 55 项。Apple 已有真实
QuickJS incoming 文件同意/保存与状态读写的宿主基础，但正常 watchOS 接入、
状态事件/认证 synchronize、混合及其余 guest 服务、iOS 示例、OS 传输和
Apple SDK/真机验收仍未完成。Android/Harmony 与通知/后台/无障碍的完整
剩余边界见 [implementation-status.md](implementation-status.md)。此次停止后
未继续实现状态事件；恢复工作应从未完成项继续，而非把源码测试视为全平台验收。

## Implementation progress — 2026-09-08

- Apple guest state get/set/delete 已接持久状态与框架 entry 格式，区分缺失、JSON null 和删除墓碑；新增 state-only queue/runtime-pump 入口，public runtime 入口要求原生 state capability。请求信封上限升为 128 KiB 以容纳有效状态值，文件 args 仍 4 KiB。Swift/Linux 93 项通过，覆盖 16,000 字符排队写入、真实重开、身份字段拒绝与预取消。状态事件、混合路由和认证 synchronize 等待尚未接入，synchronize 明确 unsupported 而非拿本地游标冒充对端应用。

- Apple public runtime-pump 构造现检查原生已挂载 runtime 的 companion.sync.file；共享 `pod_runtime_has_capability` 在包校验/挂载前返回 false，挂载后授权不可变直至销毁。incoming service 构造同时拒绝文件 owner 与 guest storage 的 app 不同。Rust 全量 55 项、Swift/Linux 92 项通过；首次测试误将挂载后重校验当作可撤权，已按实际不可重校验语义修正。宿主仍须可信绑定 runtime/app/root 和安装策略，不能据查询视为完成授权 UI 或正常 watchOS 接线。

- Apple incoming guest 实链路已用真实 QuickJS 验证：文件事件触发 guest 同意，宿主认证入口写块/完成，完成事件触发 guest 保存至同一 runtime 的 files/result；核对实际字节、接收源保留和两条成功回执，frame 使用同一 IO gate 的独立描述符。Swift/Linux 92 项通过；pump 前后 flush 已共享单次 64 回执预算。此测试的传输入口由宿主注入，非 OS 手机/手表连接或正常安装授权验收。

- Apple `SyncGuestRuntimePump` 已连真实 runtime effect poll/event post：复制借用原文、保留堵塞请求、仅原生入队成功确认回执/文件事件，每通道每次最多 64 项。Swift/Linux 91 项通过，新增真实 QuickJS 发请求→填满原生事件队列→保留回执→guest 排空→重试且仅收到一次回执。宿主须同一 runtime executor、guest IO gate 外调用并在销毁前 close；FIFO 背压可能延迟排队取消，生命周期直接 close。watchOS 正常宿主、授权与 Apple SDK/真机仍未接入/验收。

- 接 runtime 队列时发现外部 post_event 原无背压，已增加单 JSON 1 MiB、队列 256 条/总 4 MiB 准入；满队列返回 -2 且不入队，宿主保留原文待 guest 排空重试。内部输入/lifecycle 生产者不受此外部入口限额约束。Rust runtime 全量 54 项、Swift/Linux 90 项通过，新增计数/字节边界与 FIFO 重试；各平台二进制需重建，watchOS runtime 接线仍未完成。

- Apple 新增 foreground `SyncGuestServiceQueue`：串行 worker 执行 incoming 服务，运行/排队/回执共 64 项；取消信号及回执资格分离，取消的排队任务排空前不释放容量，重复 live ID 不覆盖，精确回执 token 隔离 ID 复用。Swift/Linux 90 项通过，新增取消 churn 容量边界、回执背压、重复 ID、复用和错误包验证。宿主仍须在 submit=false 时保留 effect、入队成功才确认，runtime poll/post 与 watchOS 生命周期尚未接入；未宣称已安装 capability。

- Apple incoming 事件交付新增订阅开关、单条待发 token 和显式入队确认：背压保留精确 JSON，确认后才记录状态/暴露 ID；退订使旧确认失效，重新订阅重放快照。每次最多扫描 64 个 ID，nil 可能只是安静分页。Swift/Linux 88 项通过，覆盖背压、过期确认、重新订阅及接收进度变化；尚未接 watchOS event queue，宿主必须在锁外投递且只对入队成功确认。

- Apple incoming guest 服务适配器已路由 status/accept/cancel/save JSON，状态不暴露 peer/私有路径，统计已验证块，完成副本损坏报告 failed；变更要求本次 adapter 生命周期先暴露状态，拒绝 guest peer 参数和 outgoing 方法。Swift/Linux 87 项通过，覆盖同意门控、进度、远端取消后的完成副本真实保存、重建后暴露重置及损坏拒绝。宿主仍须安装授权并在 runtime IO gate 外调度；请求/回执 pump、事件、outgoing guest 服务和 watchOS 接入未完成，未新增 capability 声明。

- Apple 导入在获得共享 owner、恢复各项旧意图及分配保留 ID 前再次检查取消，缩小等锁期间取消后仍分配 ID 的窗口；新增空文件携带有效 token 的导入/注册测试。Swift/Linux 全量 86 项通过，仍为协作取消而非与所有 IO 原子化的强取消。

- Apple 宿主导入/保存新增线程安全可选 `SyncCancellation`，原生扫描、配额/恢复遍历及分块复制协作检查取消；发布前清理私有暂存，原子 link 或 outgoing 注册提交后不因迟到取消撤销成功。Swift/Linux 85 项、Rust runtime 全量 53 项通过，含确定性首块复制后取消、预取消无任务、注册提交后取消保留任务。不是 OS IO 抢占，系统文件授权、guest 服务路由和 Apple SDK/真机验收仍缺。

- Apple 宿主保存桥已接共享 Unix 发布器：完整 incoming 在同一源租约/guest IO 锁下，经描述符相对路径检查、与 runtime 一致的 files/ 16 MiB 配额扫描、流式整体 hash 后原子无覆盖发布；同内容目标幂等，不删除接收副本，恢复/失败清理私有暂存。Swift/Linux 83 项通过，覆盖未完成拒绝、远端取消后保留副本可保存、路径/符号链接拒绝、不同内容不覆盖、坏源清理、锁竞争、满配额和已发布暂存恢复。仍缺 guest 服务路由、复制中取消及 Apple SDK/真机文件系统验收，不能声称完整 watch sync 已启用。

- guest save 前置共享 Unix IO 锁已加入 Rust C ABI：runtime data root/sync-save/owner.lock，独立描述符互斥，检查目录权限/owner 和锁硬链接。watchOS 源码已在初始化/eval 与 guest frame 持锁，busy 延后且保留输入。Swift/Linux 80 项、Rust runtime 全量 52 项通过，含锁互斥/释放/FFI；未编译 watchOS 条件分支或更新 Apple runtime slice。此为配额安全发布前置条件，`syncFiles.save` 发布器和 guest 服务仍未完成，不能视为 watchOS 真机能力。

- Apple 文件准入支持声明/profile 取最小值，仅能收紧 16 MiB/32 MiB/128 ID 上限；schema 7 持久有效限额，重开须一致，旧日志按默认限额解释，不自动迁移已有 namespace 限额。补上损坏完成文件修复前的双副本持久预留，防止小 profile 配额被修复绕过。Swift/Linux 79 项通过，覆盖取交集、双向准入、保留 ID 上限、错误重开和修复超限无变更；宿主仍须从可信 manifest/profile 提供配置，尚非 guest 安装策略实现。

- Apple `importOutgoing` 合并源冻结与 peer 注册：schema 6 持久 registering/目标 peer；重读任务日志，匹配已提交注册则保留源，无记录才清理，读取失败/身份冲突不猜测。client 重开自动恢复中断导入。Swift/Linux 77 项通过，新增注册前失败/提交后报错及两种真实日志重开，续传测试已改走组合 API。提交不确定仍需查看历史，不承诺错误即未创建任务；系统文件授权、取消集成和 Apple 真机仍未完成。

- Apple `stepOutgoing(peer:)` 已接注册任务自动步骤：优先恢复原 pending，回执先持久观察再精确消费，按 offer/status/missing/chunk/finish/cancel 每次最多排一项；消费与排入之间中断可幂等 reoffer，等待同意由宿主节流。Swift/Linux 75 项通过，新增首回执丢失→真实 client 重开→新会话重放→多块完整字节验收，以及首次 offer 前取消且保留源。需独占该 peer 请求队列；仍不包含 OS 网络调度/后台适配或真机验收。

- Apple 新增独立 outgoing transfer CAS 日志及客户端注册：共享源锁内重验完整源后永久绑定 peer/manifest；保存 queued/cancelRequested/complete/cancelled、缺块快照和块确认，先落任务观察再由调用者消费精确回执，块内容再次绑定源清单。Swift/Linux 73 项通过，覆盖真实 client 重开、peer 冲突、取消意图非终态、错误块及观察写失败/精确重复。自动请求选择/消费和导入注册协调仍待接入；宿主显式删源不等于远端任务取消。

- Apple 新增真实宿主文件导入：原生描述符贯穿两遍 64 KiB 流式扫描/校验暂存，源路径替换不重定向读取，块或大小改变（含空文件增长）拒绝；同一共享锁/配额内完成冻结。schema 5 的 importing 意图重开时清理未发布数据，提交不确定时重读并保护已完成记录，删除仍保留 ID。Swift/Linux 71 项、Rust runtime 全量 51 项通过；首次 C bool 头文件编译错误已修复。尚无系统选择器/security-scoped 授权、取消令牌、peer 任务注册或自动发送，Apple SDK/真机仍未验收。

- Apple 客户端新增 `outgoingFiles` 源暂存：与 incoming 共用 journal/native root/租约/锁、128 个保留 ID 和 32 MiB 准入，活跃源完成后仍保守保留双副本空间；完成源不可再写、删除不释放 ID。schema 4 可读 1–3，IO 前持久 preparing/completing/removing。Swift/Linux 68 项通过，新增真实重开读取/意图恢复、双向配额拒绝与跨方向 ID 冲突；仍是宿主给定 manifest/chunks 的暂存接口，缺 OS 文件导入、peer 任务账本及自动发送状态机，Apple SDK/设备仍未验证。

- 共享 Rust/Apple 新增宿主专用完成文件分块读取：同一描述符流式校验整文件与目标块后才返回至多 64 KiB，Unix 拒绝符号链接/硬链接/非普通文件，损坏读取不修复或删除；未加入远端 wire。Rust 文件 10 项、Swift/Linux 65 项通过，覆盖目标块未变但其他块损坏、尾块、越界、路径及链接拒绝。发送源快照/共同配额/任务驱动仍未实现，每块重验整文件的真机 IO 成本待测。

- Apple 混合会话新增文件回执 CAS 失败注入：确认全部通道关闭、pending 原文保留、客户端存储可继续使用；不完整文件服务/广授权独立 pump 的构造失败不抢占会话。Swift/Linux 64 项通过，README 已清理已过时的“file pump 尚未接线”说明。发送源快照、跨收发身份和共同 32 MiB 配额仍待实现，未据窄测试宣称完整文件 SDK。

- Apple 混合会话与持久客户端已接 file：三通道模式保持原授权，四通道需完整文件服务与显式授权；私有子 pump 共用锁/序号，detach 或任一路由失败一起关闭。Swift/Linux 63 项测试覆盖真实客户端交错 state/message/file、应用 ACK、同意后 chunk/finish 及完成字节，独立 file pump 仍拒绝广授权。OS 连接、outgoing 源快照/全局 ID、配对和 Apple SDK/真机仍未完成。

- Apple 新增独立认证 `SyncFilePump`：绑定同 app/device 的文件请求队列与接收器，仅允许 file 授权；先持久执行回执再提交传输序号，重试复用原始签名帧，回执严格绑定 peer/ID/请求原文。未知或已消费回执关闭会话，宿主必须保留观察至传输排空且不复用 ID。Swift/Linux 62 项通过，新增同意后精确重放/后续状态/旧请求拒绝、回执存储失败及身份隔离测试；尚未并入混合 client，也不包含 OS 传输、outgoing 快照或 Apple SDK/设备验收。

- Apple incoming 文件 journal 升为 schema 3，保留每 peer 最新 request ID/原文/精确回执；先存 pending 并预留最大回执空间，再执行幂等文件操作、持久回执。已完成重放返回首次回执而非后来同意状态，pending 仅允许同 ID 重试，未知旧传输重复不执行。Swift/Linux 59 项通过，含实际重开与同意后恢复 pending chunk。依赖顺序请求/不重用宿主 ID，不是无限历史归档；可读旧 schema 1/2，旧实现不能回读。认证 file pump 仍待接入。

- 修复共享 Rust 文件接收器的完成副本损坏修复配额：分块写入/重新组装前重新检查双副本保留空间，超额时不写入、不删除；通过准入后移除已验证损坏的私有 complete/旧组装暂存，避免三副本并存。16 MiB＋8 MiB 实盘损坏测试覆盖拒绝与释放空间后恢复，Rust 文件 8 项、Swift/Linux 58 项通过。新增检查会重验其他完成文件并增加 IO，真机时延/功耗尚未验收；各平台已安装二进制未据此视为更新完成。

- Apple 文件 wire 执行已接同意门控操作与无路径回执；远端 cancel 单独持久 remoteCancelled，使协议状态为 cancelled，但已完成本地副本仍保留在 complete 历史，重开后拒绝远端写入。以真实 journal 区分传输终态与本地副本，不再伪造回执；incoming 写入 schema 2、可读 schema 1（旧实现不能回读）。Swift/Linux 58 项通过，含 offer/同意/分块/完成/远端取消/重开及副本不变。仍缺 request-ID 重放 journal 和认证 file pump。

- Apple 客户端新增独立文件请求 journal：保存宿主 ID/peer/请求原文/摘要/精确回执，单 peer 一个 pending、128 条/8 MiB 计费上限，不自动淘汰；保留 ID 相同原文可调和 enqueue 不确定提交，消费必须匹配完整观察。Swift/Linux 全套 57 项通过，含写失败、peer/ID 冲突、变更重复回执拒绝及客户端两次真实重开。未知回执当前拒绝，宿主不得重用已消费 ID；此非跨方向任务身份或接收重放 journal，file 网络调度仍缺。

- Apple 文件请求/回执已接共享 Rust 严格校验与 Swift 编解码：拒绝未知/重复嵌套字段、非规范 Base64、块越界及方法/回执 phase 不兼容；保留原始请求字节，回执摘要绑定原文而非重序列化结果。Swift/Linux 全套 54 项通过，无远端 accept/path/save 方法。仍需认证 peer/request-ID 持久关联、请求重放日志和 file pump；wire cancel 要求 cancelled，而本地 guest cancel 保护完成品，接线时必须明确区分，不能伪造成功回执。

- Apple 客户端已纳入 incomingFiles 独立租约/关闭生命周期，app 隔离及所有日志重开测试覆盖待同意文件；补充 32 MiB 组装配额拒绝不授予同意、整文件 hash 失败后的显式本地清理。Swift/Linux 全套 51 项通过，Rust runtime 全量库测试 46 项通过，implementation-status 已更新当前实际能力及缺口。完成意图遇坏 hash 时普通取消保守拒绝，宿主可显式移除；文件 wire/outgoing/跨方向身份及 guest save 仍未完成。

- Apple 文件接收新增独占 journal/receiver 同意工作流：offer 不分配文件、local accept 先存 accepting 再 reserve；completing/cancelling 可重开恢复，分块/缺块/完成受同意门控制，普通取消保护完整副本，显式本地 remove 才删除。128 个跨 peer incoming ID 保留、按临时组装空间计 32 MiB 上限。修正原生取消已缺失目录的幂等处理并补父目录 fsync；最初恢复测试两项失败后定位修正，最终 Swift/Linux 50 项与 Rust 文件 7 项通过。尚未绑定 SDK/网络、跨收发身份账本或 guest 安全保存，不是真机断电验收。

- Apple 新增持久 `SyncCompanionClient`：按宿主 app 隔离目录、父目录独占租约与 app/device 身份落盘，状态/outbox/inbox 独立子日志，至多一个混合连接；detach 关闭会话，close 先释放子日志再释放父租约。Swift/Linux 全套 48 项通过，含真实 app 隔离、所有日志重开、错误设备身份拒绝、重复 owner 与借用 pump 关闭。实例不自动联网或配对，文件同意服务、OS 传输、iOS 示例和 Apple SDK/设备验证仍缺。

- Apple 新增同会话 state/message/ACK 混合调度，子 pump 私有、共享调用顺序，ACK 在原始认证后按 kind 路由独立日志，任一路径失败关闭整条连接；独立公开 pump 仍只接受窄授权。Swift/Linux 全套 46 项通过，含双向混合状态/消息及共享序列 ACK、非法 ACK kind 关闭所有路由。文件 channel、OS IO/调度和真机验收仍未接线，状态订阅唤醒须排队，不能同步重入收发器。

- Apple 认证消息 pump 已接独立 inbox/outbox 与精确 message/ACK 授权，验证原始帧后再路由；持久 pending 只推进传输序列，不发应用 ACK，宿主显式完成并落盘 applied 后才 ACK。当前待发帧/最新匹配 ACK 重用精确签名字节，失败关闭会话。Swift/Linux 全套 44 项通过，含延迟处理、ACK 丢失重放、过期零效果、applied 存储失败无 ACK；尚未接真实 OS IO/guest 效果、发现配对或 Apple 设备验收。

- Apple 新增独立持久消息 inbox：pending 效果与 applied 回执分离、相同 live 重放保持状态、同 peer/ID 内容变化拒绝、过期不分配效果；digest token 匹配且完成落盘后才返回 applied。优先级/FIFO 分页恢复与 1000 条/8 MiB 配额，实际两次磁盘重开保留 pending/applied。Swift/Linux 全套 41 项通过，含接收/完成落盘失败与到期边界。宿主业务效果必须按认证 peer/ID 幂等，效果执行后、回执前崩溃仍可能重试；guest 效果适配与消息网络 pump 尚未接线。

- Apple 消息新增持久 TTL outbox：首次过期时间/重试身份和队列同 CAS，精确认证 ACK 仅移除待发内容，已确认请求重试不会重新入队；原生 SHA-256 校验内容与 envelope，优先级内 FIFO、1000 条/8 MiB（含每条 1 KiB）及 10000 条到期身份上限。首次 Swift/Linux 全套 37 项通过，含实际文件重开、ACK 写失败和首次 expiry 保持。接收 journal/效果交付及网络路由仍缺，尚非完整消息服务；Apple SDK/真机未验证。

- Apple 消息新增 Android/Harmony 二进制 envelope 与 kind-1/2 ACK 编解码，校验 256 KiB、优先级、安全整数过期时间和精确长度；快照 store 可在独立消息 namespace 显式选 18 MiB 上限，状态默认仍 4 MiB，小上限重开拒读写但保留大快照。Swift/Linux 34 项与 C11 警告即错误检查通过，含黄金字节、切片 Data 和 5 MiB 实际重开。此为消息协议/存储底座，持久队列、去重/效果确认和网络路由尚未完成，不据此声明消息服务可用。

- Apple 状态订阅仅在实际条目变化且 CAS 成功后，在状态锁外发送原始已提交快照；发送游标/回执推进、重复或败选版本不通知。Swift/Linux 30 项通过，覆盖存储失败零回调、回调读状态/取消订阅无死锁。回调为同步宿主接口，不保证并发/重入通知顺序，已捕获回调可晚于取消；run 唤醒需排到 IO worker，不能在 receive 内重入 run。尚未接 OS/UI 调度或真实 WatchConnectivity。

- Apple 状态新增只读持久 ACK barrier 与显式前台 run：单个在途批次、签名/完整写入/接收处理共用序列化顺序、单调 100 ms–120 s 期限和失败关闭；双向原生认证状态收敛后才返回已同步，新修改使 barrier 失效。Swift/Linux 全套 29 项通过，含写失败保留批次与超时不再发送。此 run 为同步 IO-worker 接口，宿主必须提供有界非重入写入、定期 step 和后台/EOF close；无内部定时器/OS hook，不能中断阻塞写入，WatchConnectivity/Apple SDK/真机仍未完成。

- Apple 新增 state/ACK 专用认证调度器，绑定 app/local/peer 与精确通道，原始帧验证后路由、持久提交后才推进会话并返回 kind-3 ACK；保留待发签名帧和最新重复回复，异常关闭会话，重连复用持久批次。Swift/Linux 真实 Rust 认证收发、丢 ACK 精确重传、存储失败无 ACK 与新挑战重连测试通过（本轮首次全套 25 项）。宿主仍须提供有序可靠加密 IO 与生命周期，未接 WatchConnectivity/BLE，也非 Apple SDK/设备验收。

- Apple 状态新增持久发送 cycle/批次/ACK 日志：先 CAS 后返回待发送批次，重开重试保留精确 ID/字节，仅匹配 ACK 推进，发送中新修改进入下一 cycle；512 条/256 KiB 分页，4 MiB 总快照含冻结 cycle，超额失败不返回可发送批次。schema 1 可读、写入 schema 2（旧实现不能回读）。Swift/Linux 22 项与 Rust 状态 6 项测试通过，含落盘失败、513 条分页、篡改及周期中修改；尚未绑定 Apple 网络/订阅/synchronize，也非 Apple SDK/真机验收。

- Apple 新增持久状态 get/set/delete、LWW 合并与认证批次接收：共享 Rust 纯变换保留不同 Unicode JSON 键，状态与精确最新回执在同一次 CAS 落盘后才返回，失败不确认。Swift/Linux 真实原生库 20 项测试通过，覆盖 null/删除、重开、回放冲突、数字等价及 CAS 失败；既有 Rust 会话测试 2 项通过。尚无 outgoing 批次/ACK 日志、订阅调度或 synchronize 协调器，Apple SDK/设备未验收，能力保持关闭。

- Apple 新增 4 MiB 有界快照/CAS 存储，使用独占租约、目录 fd 相对操作、暂存 fsync/rename/目录同步、缺失与空值区分和安全暂存恢复；拒绝不安全文件及符号链接，FIFO 非阻塞拒绝。Swift/Linux 实际 C/Rust 联编回归 16 项通过，含 40 次并发 CAS 无丢失；C11 `-Wall -Wextra -Werror` 检查通过。此为状态/队列底座，尚非状态合并或完整消息队列；Apple SDK、设备文件系统/断电验收未完成。

- Apple Swift 新增低层宿主文件接收封装，调用 Rust 持久 manifest/分块/缺块重验/完成接口；持有安全 sibling 文件锁以排除重复接收器，关闭与 IO 串行，锁文件拒绝符号链接、多链接、外来 owner 和宽权限。真实 Rust 静态链接的 Swift/Linux 10 项测试通过，包含磁盘重开续传和错误哈希。此层无持久同意/身份账本、跨 peer 总配额、guest save 或网络路由；清理原语会删除完整宿主副本，不直接映射 guest cancel。Apple SDK/真机未验证，capability 不开放。

- Apple 新增 Swift package 会话基础层，复用 Rust ABI 2 认证/帧校验/显式 commit，锁序列化原生句柄和 close、复制借用回复；原始输入帧交给 Rust 严格解析。可复现脚本使用固定 Swift 6.0.3 容器和真实 Rust 静态库，6 项测试通过（含篡改、错误 proof/channel、未知字段、重复投递及并发关闭）。本机动态库首次链接因 glibc 版本不符失败，已改静态链接；未进行 Apple SDK 构建或真机测试，尚缺持久三通道、配对/传输、iOS 示例和 watchOS 服务接线，不能据此开放 Apple capability。

- 更新后完整 Harmony TS 回归通过：349 项测试、4790 断言（80 文件，2.34 秒）。2026-09-09 重新核实设备：ADB 仅 OWW242 在线，DevEco HDC 无设备；同步设置/邀请/guest 服务源码已接线，但不据此开放能力或宣称真机验收。同步更新 implementation-status，移除已过时的“Harmony 页面未接同步”描述；Apple SDK/宿主同步入口仍是当前实际缺口。

- Harmony 新 guest 发送快照与任务登记共用导入租约，新增持久 `registering`/peer：恢复查询登记，匹配已提交任务则保留完整源，未登记则删除，登记不可读/冲突时保留并报错；读块会先恢复该中间态。原生回归通过，覆盖提交后返回丢失、提交前失败、子进程提交后退出再恢复；相关 TS 10 项通过（70 断言），DevEco ArkTS/HAP 构建通过（5.955 秒）。旧流程留下的无归属 complete 副本仍不能自动推断归属；新 phase 不支持旧实现回读，真机恢复/时延和完整跨端验收仍待完成，capability 保持关闭。

- Harmony guest 新建发送快照使用整段原生独占导入租约与持久 `importing` 标记：受控失败尝试删除，进程退出后可在新租约下恢复删除，活跃导入不可被并发清理；普通可恢复导入不变，迟到 writer 失效。原生存储全套回归通过，包含子进程持锁写入后退出/重开清理；相关 TS 10 项通过（68 断言），实际 DevEco ArkTS/HAP 构建通过（6.473 秒）。新 phase 不能由旧实现读取；完成快照到任务登记之间的孤立副本窗口仍未关闭，真机导入暂停时延/恢复验收未完成，capability 保持关闭。

- Harmony 前台已认证文件 driver 新增终态源副本回收：本机持久 complete/cancelled、对端队列/回执为空、全局身份与 manifest 一致时，每轮最多删除一份宿主 outgoing 快照，保留登记身份与 guest 原文件；移除失败保留重试依据。8 项专项测试通过（56 断言），实际 DevEco ArkTS/HAP 构建通过（5.793 秒）。变更前完整 Harmony TS 回归 345 项通过（4770 断言）；尚未回收未登记的失败导入孤立副本，也未验证真机清理/无线链路，capability 保持关闭。

- Harmony 正常宿主新增新设备邀请页：真实 app/device 身份、五分钟临时二维码、指定对端 BLE responder 验证、双方显式确认和部分保存提示；取消/后台/退出销毁租约与图片，迟到图片不重新显示。15 项组件事件/邀请/初始配对测试通过（87 断言），实际 DevEco ArkTS/HAP 构建通过（5.657 秒，修正 ArkUI 保留方法名）。未验证真机扫码可读性、圆屏输入和物理配对收发，主动发现/平台专用链路及完整验收仍未完成，capability 保持关闭。

- Harmony 同步设置新增已批准设备的 BLE 等待连接操作：原生权限请求后按真实配对身份进入 responder 认证，取消/页面退出/Ability 后台使旧权限回调失效并停止等待；已连接后返回应用保留前台会话，不自动重连。8 项相关测试通过（66 断言），实际 DevEco ArkTS/HAP 构建通过（5.592 秒）。当前手表作为被连接端；新设备邀请、主动发现及物理无线/界面验收仍未完成，capability 保持关闭。

- Harmony 正常宿主页面新增能力门控的同步设置入口：圆屏单列配对记录/恢复/二次确认撤销、连接状态刷新与断开、返回应用焦点恢复；退出或后台后旧确认和异步读取不会更新页面或发起撤销。7 项相关测试通过（59 断言，含真实组件事件方法但不含 ArkUI 渲染），实际 DevEco ArkTS/HAP 构建通过（5.706 秒）。邀请二维码、无线连接操作及真机显示/表冠/读屏验收仍待接入，capability 保持关闭。

- Harmony 宿主新增共享配对 owner 入口，使用已安装应用的持久 app/device 身份；并发打开只取得一份配对租约，失败可重试，跨目录和 capability 关闭时拒绝访问，读取入口不自动邀请或连接。懒加载测试通过（24 断言），实际 DevEco ArkTS/HAP 构建通过（6.039 秒）。配对/连接界面仍未接入，尚未做真机配对，capability 保持关闭。

- Harmony guest 消息 ACK 已接正常页面的即时回执路由：本地业务确认持久提交后，异步尝试同一前台/对端/message 授权连接；网络失败不改变本地成功，后台或对端不匹配不路由。认证内存链路验证 guest ACK 后发送端立即出队且不依赖重发；相关 23 项测试通过（141 断言），实际 DevEco ArkTS/HAP 构建通过（5.854 秒）。尚未完成正常配对/连接 UI 与真机消息收发验收，capability 保持关闭。

- Harmony guest `sync.state.synchronize` 已接现有批准连接的持久 ACK 等待：最多 8 个独立等待、最长 30 秒，取消/超时不关闭共享连接，当前状态全部确认后返回同一持久快照的 incoming cursor；新写入仍须继续确认，不声称对端没有尚未发送的变更。31 项相关测试通过（179 断言），包含 513 条跨批次确认、只读 ACK 查询、容量回收及 guest 取消；实际 DevEco ArkTS/HAP 构建通过（7.198 秒）。正常宿主配对/连接 UI 与真机验收仍未完成，capability 保持关闭。

- Harmony 正常宿主新增显式连接入口和前台控制器：从原生能力声明生成通道，页面/Ability 退后台断开，能力变化中止连接；每秒唤醒消息与文件 driver，单连接调度串行且不延长两分钟期限，不自动重连。5 项相关测试通过（57 断言），最终 DevEco ArkTS/HAP 构建通过（6.397 秒）。入口仍需实际宿主配对/连接 UI 调用，尚未验证真机生命周期与无线收发，失败副本清理仍待补齐，capability 保持关闭。

- Harmony 连接 owner 可接收精确宿主通道授权并在异步前复制，拒绝缺 ACK、重复或未知通道；传输层自动发送只访问已授权通道，显式未授权发送失败不关闭其他正常通道，文件 driver 也检查 file 授权。认证 state-only 测试证明消息队列零读取且拒绝消息/文件后状态仍能同步。全部 328 项 Harmony TS 测试通过（4663 断言），实际 DevEco ArkTS/HAP 构建通过（6.625 秒）。正常手表宿主的配对/连接 UI 与生命周期调度仍未接入，capability 保持关闭。

- Harmony outgoing 进度改为持久缺块清单/分块 ACK：消费请求回执前先登记，保留精确尾块字节数，全部字节确认仍等待 finish 回执才完成；状态和事件共享该进度。登记 schema 1 可读、写入迁移 schema 2（旧实现不能回读新格式）。16 项专项测试通过（90 断言），原生跨进程进度保存及完整 background-store 回归通过，实际 DevEco ArkTS/HAP 构建通过（5.588 秒）。正常宿主连接调度、失败副本清理及真机进度验收仍待完成，capability 保持关闭。

- Harmony outgoing 文件事件已接正常页面/Ability 投递循环：每轮最多 64 条游标分页、状态去重、拒收重试、跨方向冲突跳过及前后台 generation 隔离，原生队列接受后才登记取消操作凭据。收发相关 15 项测试通过（108 断言）；修正 ArkTS 要求显式实现事件接口后，实际 DevEco ArkTS/HAP 构建通过（5.914 秒）。精确 ACK 进度、正常宿主连接/调度、失败副本清理和真机事件验收仍待完成，capability 保持关闭。

- Harmony 登记发送器/任务选择器新增认证内存传输集成验证：真实协议与 incoming 状态机在明确同意后收齐 65,539 字节并校验一致，随后处理第二项未分配空间的取消任务；两端终态及 outgoing 登记一致、请求回执消费、队列归于 idle。传输测试共 9 项通过（63 断言）。接收存储为测试内存 port，不是真机文件落盘、正常宿主连接或双设备无线验收；这些边界仍待完成。

- Harmony 新增持久发送任务选择器：优先恢复当前对端请求，等待同意时复用 sender，终态后选择下一项；SDK 仅在已有对应连接时提供单实例 driver，关闭客户端同步关闭选择器，迟到初始化不再调用 transport。相关 4 项测试通过（38 断言），实际 DevEco ArkTS/HAP 构建通过（5.591 秒）。当前仍需宿主按前台生命周期调用 step，未自动连接/重连；正常宿主连接 UI、事件、ACK 进度与真机验收仍未完成，capability 保持关闭。

- Harmony 新增持久登记版文件发送器与 SDK 工厂：终态先写 outgoing 登记再消费请求回执，提交失败保留恢复依据，终态重开不重新 offer；取消意图等待当前请求，必要时先建立幂等 offer 再取消，不读取文件块。相关 5 项测试通过（57 断言），实际 DevEco ArkTS/HAP 构建通过（5.562 秒）。发送器尚未绑定正常宿主认证连接的自动任务选择，精确 ACK 进度、outgoing 事件和真机验收仍待完成，capability 保持关闭。

- Harmony 正常页面已接 guest `sync.files.offer`、outgoing status/cancel：系统随机 ID、固定输入快照、取消隔离、已查看身份门、本地取消意图与未知进度标记；incoming 状态/取消仍路由原服务，能力检查保持在最外层。全部 313 项 Harmony TS 测试通过（4559 断言），实际 DevEco ArkTS/HAP 构建通过（5.707 秒）。尚缺 outgoing 事件、认证发送驱动、精确 ACK 进度及失败副本清理，未做真机 guest 文件发送，capability 保持关闭。

- Harmony SDK 新增 `queueGuestFile` 本地发送编排：宿主提供新 ID，先校验 peer/元数据、拒绝已有身份及源副本，再导入固定来源，导入后复查身份并登记 queued；取消或新出现冲突不登记发送。客户端测试通过（21 断言），实际 DevEco ArkTS/HAP 构建通过（5.427 秒）。尚未接 guest 服务、ID 生成、网络驱动或失败导入清理；中断副本保留，queued 不表示对端收到，capability 保持关闭。

- Harmony incoming 状态/接受/取消/保存与事件入口新增跨日志身份检查：同时查询 incoming、outgoing 登记及请求回执，终态仍占用 ID，跨方向/对端冲突和未登记请求拒绝操作；正常页面已接此门。12 项相关测试通过（95 断言），实际 DevEco ArkTS/HAP 构建通过（5.598 秒）。检查是只读快照，不是跨日志原子锁；outgoing guest offer/status、网络驱动和真机验收仍未完成，capability 保持关闭。

- Harmony 正常页面的已安装同步 owner 已统一为 `NativeCompanionClient`，状态/消息/incoming 共用实例，新增受文件能力门控制的宿主客户端入口供后续 outgoing 编排使用；构造不启动网络。owner/客户端两项测试通过（25 断言），覆盖并发初始化、失败重试、目录隔离和能力撤销；实际 DevEco ArkTS/HAP 构建通过（5.424 秒）。guest offer、跨方向身份解析、网络连接与真机验收仍未完成，capability 保持关闭。

- Harmony outgoing 登记的 SDK 接线已通过实际 DevEco ArkTS/HAP 构建（6.515 秒）；首次编译发现 ArkTS 不支持构造参数字段声明，已改成显式字段后重跑。客户端/登记 5 项 TS 测试再次通过（36 断言）。这补齐编译证据，不代表 guest offer、网络发送恢复或真机收发已经完成；capability 保持关闭。

- Harmony outgoing 登记已绑定独立原生 CAS namespace，并导出 HAR 工厂、装入 `NativeCompanionClient`。实际 N-API 回归验证 SDK 登记、子进程重开并更新取消意图、app/请求队列隔离及过期 CAS 拒绝；完整 background-store 脚本通过，客户端/登记 5 项 TS 测试通过。初次测试暴露 namespace 白名单缺失，已补齐并重跑通过；尚未完成本轮 DevEco 编译、guest offer、网络发送恢复及真机验收，capability 保持关闭。

- Harmony 新增独立 outgoing 传输登记核心：固定 peer/manifest、128 条保留身份、取消意图及单调终态、CAS 冲突拒绝与重开恢复；请求队列另增跨 peer 的只读身份查询，包含终态但不冒充完整传输历史。11 项相关 TS 测试通过（70 断言），覆盖并发 CAS、容量、损坏与返回快照隔离。登记核心尚未绑定原生持久存储、SDK owner、guest offer 或网络驱动；本轮未做 DevEco 编译与真机验证，capability 保持关闭。

- Harmony 新增 guest 文件源租约：固定私有根、逐层 no-follow、打开 FD 固定来源、16 MiB/64 KiB 分块限制，与 guest 帧/保存共用 IO 锁。SDK `importGuestFile` 在两遍校验导入后无论成功/失败均释放租约；源路径改写不影响已完成的 outgoing 副本。原生 N-API/SDK 实际落盘测试通过，包含重复占用拒绝、关闭中读取、越界与源修改隔离；最终 DevEco SDK/HAP 构建通过（6.875 秒）。尚未接 guest offer 或持久 peer 传输登记，未验证真机导入暂停时延，capability 保持关闭。

- Harmony incoming 文件事件已接正常页面/Ability 生命周期：每秒按 ID 游标最多检查 64 项，状态内容去重，拒收不登记操作凭据，歧义 ID 和单项读取失败不阻塞后续项；退后台隔离迟到快照，恢复重新提供最新状态。全部 301 项 Harmony TS 测试通过（4462 断言），DevEco SDK/HAP 构建通过（6.743 秒）。当前只覆盖 incoming，outgoing 来源快照/传输登记、统一身份解析与网络宿主仍待实现；真机 guest 文件事件和性能未验收，capability 保持关闭。

- Harmony `sync.files.save` 已接通 guest 服务、SDK 完成态租约与原生 worker。guest boot/执行帧和保存共用 `sync-save/owner.lock`，保存期间不执行 guest 帧，避免配额扫描与 guest 写入交错；确认 runtime 写入实时扫描用量，无缓存需要刷新。补齐同进程/跨进程锁、SDK→N-API 落盘、损坏源拒绝和 guest 完成态门测试；原生回归及 ASan/UBSan 脚本通过，299 项 Harmony TS 测试通过（4450 断言），最终 SDK 原生宿主/HAP 构建通过（3.713 秒）。未验证真机保存及帧暂停时延；文件发送、事件和网络宿主仍待完成，capability 保持关闭。

- Harmony 原生存储新增完整文件保存核心：固定 guest 私有根、已存在相对目录逐层 no-follow、独立锁与精确 staging 恢复、完整副本及暂存流 SHA-256 校验、`renameat2(RENAME_NOREPLACE)` 发布、同内容重试及目录 fsync；路径穿越/目标冲突/符号链接拒绝。原生存储/N-API 回归脚本通过，DevEco companion C++/HAP 构建通过（3.816 秒）。目前仅内部 C++ 方法，尚未导出 N-API 或接 guest save；16 MiB 配额为扫描快照，仍需与 guest FS 写入协调，不能据此开放 capability 或宣称保存端到端完成。

- 文件保存前置检查发现 Harmony 普通 boot 原先传 `data_dir=null`，guest FS 为内存模式。现由宿主传入系统 filesDir，在独立 `podjs-guest` 下初始化私有 files/tmp 并传给 runtime，隔离宿主同步/配对日志；固定目录拒绝符号链接和宽权限，重开不清已有文件。新增原生目录测试，ASan/UBSan host-contract 脚本通过；DevEco 重编 C++ 宿主与 ArkTS、HAP 打包通过（6.467 秒）。Rust 预编译 runtime 未重建，尚未验证真机重启持久化，`sync.files.save` 本身仍待实现。

- Harmony 正常页面新增 incoming 文件 status/accept/cancel：先由状态查询明确唯一来源再允许操作，不采信 guest peer；进度读取验证实际分块，损坏副本不只凭 complete 日志报完成；guest 取消与完成检查同锁，已完成文件不删除。此适配尚只覆盖收件，offer/save、文件事件及跨 outgoing 的全局身份解析仍待接入。七项相关测试通过，最终 DevEco SDK/HAP 构建通过（5.709 秒）；未开启 capability，未做真机收发/保存验收。

- Harmony 正常页面已接消息收件事件与显式业务 ACK：严格 UTF-8/有限 JSON/重复键校验，100 条分页轮转，未确认重投；仅原生事件队列接受后登记复制的内容摘要，ACK 持久提交后可重试，隐藏/后台清除 guest 凭据并隔离迟到投递。非法消息不自动确认，也不阻挡后续页。全部 295 项 Harmony TS 测试通过（4421 断言），DevEco SDK/HAP 构建通过（6.384 秒）。这只完成本地宿主队列/事件/确认装配，未接网络 owner 的即时回执，未验证真机 guest 收件及双设备收发，capability 保持关闭。

- Harmony 正常页面接入 guest 消息发送服务：稳定有限 JSON 编码、256 KiB 载荷限制、宿主固定身份与 TTL 持久队列，异步 owner 初始化前后取消检查及请求快照隔离；成功只返回本地 `queued`。状态/消息共用已安装身份，各自能力门独立。修正宿主跨模块相对导入为 companion 包内 TS 子路径，避开 TS 导入 ArkTS 入口限制；最终 SDK 构建通过（5.595 秒）且无该类导入诊断。全套 Harmony TS 测试在调整前通过 291 项，最终包路径调整后三类宿主测试 15 项通过。消息收件事件/业务 ACK、网络宿主与真机验证仍待完成，capability 未开启。

- Harmony 消息队列新增 TTL 入队：首次过期时间与队列同一 CAS 提交，重试/重启保持原时间，ACK 后保留身份记录避免重新入队；变更内容/TTL/优先级和绝对过期请求冲突均拒绝。独立 10,000 条身份配额不驱逐未过期记录，schema 1 在写入时迁移至 2（旧实现不可回读新格式）。队列/消息链路 20 项测试通过，DevEco HAP 构建通过（5.887 秒）。guest 消息服务入口尚未接入，未完成真实设备收发，未开启 Harmony sync capability。

- Harmony 状态事件已接到正常页面和 Ability 前台生命周期，使用原生 `postEvent` 每秒有界投递；隐藏/后台/销毁关闭旧投递器并取消定时器，恢复时以新实例重新提供最新快照。实际 DevEco ArkTS 编译和未签名 HAP 打包通过（5.635 秒），11 项相关 TS 测试通过。仍有既有权限/设备能力警告；未验证真机 guest 收件或生命周期，未重建原生 runtime/guest，Harmony sync capability 仍关闭，消息/文件/网络宿主装配仍待完成。

- Harmony 新增状态事件投递核心：按持久快照合并最新条目和墓碑、每轮最多 64 条、原生队列拒绝后重试、已投递内容去重，关闭或授权撤销抑制异步迟到结果。事件核心与服务适配共 11 项 TS 测试通过；尚未接入页面生命周期/原生事件队列，未完成本轮 ArkTS SDK 编译，capability 保持关闭。这是最新状态流，不保证逐次历史变化或 guest 业务处理完成。

- Harmony 页面状态接线已完成实际 DevEco SDK 编译与未签名 HAP 打包（28.662 秒）；包内 ABC 确认包含身份工厂和状态服务。构建仍有设备能力/权限警告，Harmony sync capability 保持关闭；未验证原生 runtime 与新 guest 包的实际启动、备份迁移或同步收发，不把编译结果视为四端验收。

- Harmony 已安装 owner 异步初始化新增取消与失败重试测试：等待 owner 时取消不会随后写入状态；初始化失败仅返回通用错误，复用请求 ID 可正常重试。身份/适配/pump 共 17 项 TS 测试通过；不替代 SDK 编译或真机启动验证。

- Harmony 正常页面接入懒加载状态 owner 工厂：系统 bundle 名称绑定 app，随机 watch 身份经原生 CAS namespace 持久化，并发首次创建收敛，损坏/外来记录拒绝且不静默轮换。状态适配等待 owner 时仍检查取消。身份/服务/pump 共 15 项 TS 测试通过；本轮未完成 ArkTS SDK 编译、真实启动与备份迁移身份隔离验证，Harmony sync capability 仍关闭，消息/文件/网络服务尚未接通。

- Harmony 状态适配新增取消后复用同一请求对象的回归测试，先复现旧操作恢复并使新请求超时，再改为每次调用独立 operation token 与固定 ID；18 项相关 TS 测试通过。此修复不改变尚未绑定正常宿主 owner、未完成 SDK 编译和真机验收的状态。

- Harmony 手表新增 `SyncStateServices` 内部适配，复用持久 `CompanionState` 实现 get/set/delete，保留 null 与墓碑，忽略 guest 身份，异步前快照参数并隔离取消/复用 ID 的回调；补齐 `sync.files.save` 的文件能力授权映射。17 项相关 TS 测试通过。适配尚未绑定正常页面的已安装 owner，也未通过本轮 ArkTS SDK 编译；网络同步、事件、消息/文件宿主及安全保存目录仍待实现，未开启 Harmony sync capability。OWW242 充电遮挡仍存在，本轮未重跑权限 UI 验收。

- 新增正常 APK 的真实权限拒绝测试 `InstalledSyncPermissionTest`，检查弹窗期间设置关闭/密钥清空及拒绝后不自动扫描。当前未通过：OWW242 的 `SysUI.Charging` 长时间充电提示遮挡权限界面，返回键无法关闭，系统要求插拔 USB。测试显式识别该阻挡，不将其记为权限流程通过；需恢复设备界面后继续验证。

- 正常安装包测试补上宿主按钮认证闭环：实际设置面板填写测试身份/密钥、二次批准、LAN 连接，隔离对端写入状态后经认证会话和真实 guest 订阅写回 KV，再显式断开并撤销宿主测试配对。Android 与 Wear APK 各一项在 OWW242 上通过，全程不注入 owner。对端仍在同一物理设备内，未替代双设备无线、BLE 权限或实际 Wear OS 验收。

- 安装包同步测试改为解析目标 APK 的实际 launcher，Wear module 增加独立 instrumentation 配置；Wear APK 的正常 Activity/native boot、三通道本地 guest 服务和同步设置入口在 OWW242 上通过，Android APK 同测回归通过。两个测试包使用不同 application ID，未混用存储。OWW242 不是 Wear OS，此结果仅验证 Wear APK 通用宿主代码路径，不作为 Wear OS/Data Layer/无线验收。

- 正常 `sync-apk` 启动测试扩展覆盖三通道 guest 服务：状态读写之外，实际 guest 消息离线入队，以及五字节文件写入、offer/status/cancel/status 均经 MainActivity/native bridge 完成；OWW242 一项集成测试通过。使用无配对的测试 peer，只验证本地排队/文件状态，不宣称消息已送达或文件已无线传输。

- Android/Wear profile 与 JNI 同步开启通用 state/message/file 能力，新增独立 `sync-apk` fixture 与 `InstalledSyncTest`：从实际 MainActivity 冷启动经过 native 包校验，guest state set/get 完成并打开真实已安装 owner 的同步设置，不注入 owner。首次运行定位旧预编译 runtime revision 不匹配，按仓库脚本重建 ARMv7/ARM64 后 OWW242 测试通过；框架/target 六项通过。尚未做物理双设备 BLE、实际 Wear OS、平台专用通道或四端验收；能力开启不代表完整计划完成。

- BLE 候选列表新增真机控件测试，先复现取消后迟到点击恢复候选的问题，再以当前 dialog 身份检查和 dismiss 清理修复。验证选择后仍需单独连接、取消/关闭后的旧列表点击不能恢复候选；连同已有设置测试，OWW242 三项通过。候选由测试直接提供，未启动无线扫描或连接，不替代实际无线与授权弹窗验收。

- 同步设置新增原生 BLE 面板：显式权限请求、十秒有界扫描、候选选择、单独连接、等待连接和取消扫描；不使用广播名称作可信身份，选择不批准配对，关闭/退后台取消扫描并忽略迟到结果。沿用单列大触控目标；授权不自动启动操作。OWW242 两项设置测试通过，包含无 owner 时四个 BLE 操作拒绝、关闭清理及原 LAN 已批准按钮回归。真实扫描/授权弹窗/候选选择与 BLE 无线 UI 闭环、布局/读屏仍待验收；profile 仍关闭。

- Android 共享 runtime 补齐网络及 BLE manifest 权限，修复 Wear 宿主缺少 INTERNET 的声明缺口；合并后的 Android/Wear manifest 已核对。新增操作级权限检查：Android 12+ 连接/扫描/广播分开，旧系统仅发现要求定位；宿主 BLE 在访问无线前拒绝缺失授权，不自动弹窗、开蓝牙或续接。OWW242 权限策略与服务连接共七项通过；高版本分支仅策略测试，实际授权弹窗、设备选择 UI 和无线验收仍待完成。

- 手表共享宿主控制器接入 `PodSyncBleAttempt` 与原生 `connectCompanionBle`：LAN/BLE 共用单连接、前台门、generation、认证后服务挂载及异步取消；BLE 只使用已批准通道，不将无线候选身份当作配对授权。OWW242 原八项回归通过，新增后台/关闭拒绝测试后服务连接四项通过。尚未接权限/设备选择 UI，未验证宿主 BLE 实际无线交接；普通包 profile 仍未启用。

- BLE 单次认证连接移入共享 Android runtime `PodSyncBleAttempt`，手机 `PodBleAttempt` 保留兼容薄封装；不再要求手表依赖手机模块。原五项连接测试随实现迁入 runtime，在 OWW242 上全部通过，覆盖错误配对密钥、MTU 23 认证交接、总超时、取消与关闭后拒绝；手机示例编译通过。测试使用内存 BLE 帧链路，不代表真实无线验收；手表宿主 BLE 控制器与选择/权限 UI 装配仍待完成。

- Wear OS Activity 接入共享同步设置菜单与显式 intent（冷启动和已有 Activity），并补齐停止生命周期通知；`:wearApp:compileDebugJavaWithJavac --offline` 编译通过。未开启 profile 同步声明，普通包 native boot、Wear 菜单可达性与双设备无线仍待验收；此项仅补齐宿主入口，不代表完整同步能力可用。

- Android 同步设置的已批准流程新增真机仪表测试：隔离 app 存储与临时测试密钥，经实际按钮填写、二次确认、LAN 认证连接、远端状态同步和断开，提交后密钥清空；连同无 owner 拒绝用例，OWW242 两项通过。测试注入批准 owner，不验证普通包 manifest/启动路径；端点同在设备内，真实跨设备无线、布局/读屏与 profile 启用仍待完成。

- Android 宿主新增单列滚动同步设置面板：展示 app/device 身份，64 位十六进制共享密钥经二次确认批准，数字 IP/端口连接或等待、显式断开；密钥不保存 View 状态/自动填充，关闭与后台清空，窗口禁止截图。Android Activity 菜单及 `dev.podjs.action.COMPANION_SETTINGS` 显式 intent 接入，冷启动等待 native boot 后再打开。编译及 OWW242 无 owner 拒绝/密钥清理测试通过；当前 profile 仍关闭，正常包显示不可用。批准后的 UI 全流程、布局/读屏及真实无线验收尚未通过。

- Android 宿主 LAN 控制器已接入已安装 owner 与 `PodRuntimeView` 前台生命周期：认证成功借用前台会话给 guest，后台同步撤销路由、异步关闭，generation 隔离迟到握手/回调；通道仅取批准能力，驱动不再启动未授权通道。OWW242 上三项集成测试通过，包含 state-only 认证同步和退后台拒绝路由。原生 `connectCompanion` 入口已有，但应用配对/连接 UI 尚未调用；profile 仍关闭，不代表普通包已经可用。

- 完整缺口复核确认 Android guest 服务已具备内部收发闭环，但普通应用仍缺宿主配对/连接 UI；Apple SDK、远程推送及四端验收仍未完成。LAN 尝试实现已移入共享 runtime `PodSyncLanAttempt`，手机 `PodLanAttempt` 保留薄封装，不再要求手表依赖手机模块。OWW242 上共享 runtime 两项集成测试、手机 LAN 四项测试通过，手机示例编译通过；implementation status 已纠正旧的服务缺口描述。

- Android 保存 staging 增加同进程串行及跨进程文件锁：取得锁后只清理精确 `save-UUID` 普通文件，保留未知条目/链接，扫描限 256 项，清理与发布后同步目录。OWW242 上保存集成/路径测试通过，覆盖锁占用时不清理、模拟残留清理及未知文件保留。这不是实际强杀/双进程竞态验收；其他平台 save 与真实宿主无线/UI 仍待完成。

- 文件 API 补充显式 `syncFiles.save(transferId, path)` 交付入口：Android 只保存已暴露且完整校验的 incoming 文件到应用相对路径，私有 staging 流式校验后以 `renameat2(RENAME_NOREPLACE)` 原子发布，目标已有相同内容可重试，不覆盖不同内容/符号链接，也不随 status 自动复制。OWW242 上端到端保存/重试/拒绝覆盖与路径测试通过；框架 9 项测试通过。真机硬链接发布被拒绝后已改为上述不覆盖原子重命名；崩溃 staging 清理、其他平台 save 实现和真实无线/UI 验收仍待完成。

- Android 状态服务新增本地 set/delete 后唤醒已批准连接及 `sync.state.changed` 事件：比较条目稳定摘要，包含墓碑，不重复发未变化快照，每轮最多 64 条轮转处理。OWW242 上 9 项服务测试通过，覆盖 null/墓碑/去重/能力隔离和认证双端服务写入后自动抵达对端。事件为宿主观察到的变化，不承诺晚订阅重放历史；完成文件交付与真实宿主无线/UI 验收仍待完成。

- Android 文件事件已接 guest sink：收发日志按唯一 transferId 合并游标分页，每轮最多 64 条并循环重投以支持晚订阅；事件绑定身份但不授权接收，冲突/暂时不可读记录跳过。最近 256 条暴露身份保留，旧操作可通过 status 重新查看后执行。OWW242 上 9 项测试通过，覆盖 65 条跨页事件和 event → accept → 自动传输完成。完成文件的 guest 可访问路径交付与真实无线/UI 验收仍待补齐。

- Android 前台文件传输新增每秒授权状态查询，仅在现有已批准、最长两分钟的前台会话内运行，停止/失败/截止一起关闭，不重连也不续期。认证内存 BLE 服务测试已覆盖 offer → status → accept → 自动恢复 → 完成文件字节一致；宿主真实无线/UI、guest 文件事件与完成文件路径交付仍待接入。

- Android 文件服务新增显式 accept/cancel：只操作当前 guest 通过 status/offer 已暴露的身份，重新检查跨 peer/方向冲突，拒绝接受 outgoing；接收端取消在日志锁内拒绝删除已完成文件。OWW242 上接收/路径相关 8 项测试通过，覆盖未查看拒绝接受、状态查询后接受、取消 offered 和完成文件保留。文件事件、对端 consent 轮询驱动与完成文件交付仍待接入。

- Android `sync.files.status` 已接收发两侧唯一身份解析和真实字节进度：接收侧仅在已接受状态读取 native 缺块清单，不接受 offered 或恢复 accepting/cancelling 意图；全部字节收到但尚未 finish 时仍为 transferring，已完成却缺块显示 failed。OWW242 上接收日志/服务状态与身份共 8 项测试通过。显式 accept/cancel、文件事件、应用可访问的完成文件交付和宿主真实无线链路仍待实现。

- Android `sync.files.offer` 已接应用文件根与持久完整快照：逐级描述符相对打开、拒绝路径穿越/符号链接/非普通文件，快照直接读取已授权 descriptor；持久 offer 后唤醒批准连接，拒绝发给自己，失败时只释放无活动引用的快照。OWW242 上 4 项路径/快照测试通过，覆盖中间/末端链接、打开后重命名与服务入队。接收授权/status/cancel/event 和真实宿主无线 UI 仍待补齐。

- Android 文件身份新增只读解析：按 transferId 分别有界查询收发日志，跨对端/跨方向重名明确拒绝，终态记录也参与冲突判定，未发送快照不冒充传输。OWW242 上专项测试通过。此查询不是接收授权，也不提供跨日志原子 mutation 保证；guest 文件操作接入还需绑定具体已暴露传输及严格应用路径授权。

- Android 文件发送状态新增 totalBytes / acknowledgedBytes / progressKnown：仅依据对端已持久回复的缺块清单和逐块 ACK 计算进度，正确计入不足 64 KiB 的尾块；尚未取得清单时明确未知，字节全到但等待 finish 回执仍不标记完成。OWW242 上 3 项发送日志测试和 14 项 SDK 测试通过。文件 guest 服务路径授权、跨 peer 唯一传输解析、接收进度与 UI/真机双端验收仍待补齐。

- Android 消息服务已接认证前台驱动：持久入队后唤醒该 peer 的发送，显式业务确认先保存不可变摘要绑定的收件回执，再排队即时 ACK；停止/ACK 队列满不撤销本地已提交结果，对端重试仍可读取持久回执。OWW242 上 8 项服务测试通过，认证内存 BLE 双端测试覆盖服务 send → guest event → ack → 对端出队；仍不是真实双设备无线/宿主 UI 验收。

- Android `sync.state.synchronize` 已路由到宿主借用的认证前台会话：校验同一 client owner、精确 peer、最多 8 个活动 peer，已停止/未注入/已分离连接不路由；等待 30 秒持久 ACK 屏障，分离不关闭宿主连接。OWW242 上认证内存 BLE 双端服务测试与原服务测试共 8 项通过。仍缺真实宿主连接选择/UI 装配，测试不是双设备无线验收，能力声明保持关闭。

- Android 前台同步驱动新增状态 ACK 完成等待接口：已有认证会话发送当前状态，只有持久确认当前完整状态后返回本地 incoming cursor；限制等待预算，取消/超时不关闭共享连接，停止的连接失败。该接口不是双端未来写入收敛证明；guest 服务路由与已批准连接选择仍待装配。

- Android 新增 native boot 成功后的已安装 APK 同步 owner 接入点：限当前包名与精确 target/schema/capability，设备 UUID 原子保存到 no-backup 目录，损坏身份拒绝启动而不静默更换；guest 生命周期结束关闭自有 owner，不生成配对凭据。OWW242 上身份/声明与服务共 9 项测试通过。当前平台 profile 仍未声明同步能力，因此常规包不会创建此 owner；缺少实际连接/文件操作与完整能力验收，不能宣称启动后的同步功能已可用。

- Android 已批准同步 owner 的消息事件接入 guest sink：严格 UTF-8/JSON 解码、剩余 TTL、每秒最多 100 条轮转分页、未确认重投；非法消息保持待处理且不会挡住后续页，旧 owner 的排队事件在切换后丢弃。OWW242 上服务测试覆盖事件投递、显式确认、取消、非法载荷与分页。正常启动 owner 注入、即时网络回执和跨设备验证仍未完成，未开启完整 capability 声明。

- Android 同步服务新增显式业务消息确认：宿主仅能登记实际投递的消息，确认绑定不可变内容摘要；未暴露消息拒绝确认，取消不落盘，重复确认可重试，收件 payload 后续变更不改变已登记摘要。OWW242 上 4 项服务测试通过。此处只证明本地持久业务确认；guest 消息事件、启动注入与对端即时回执仍未装配，不能据此开启完整消息能力声明。

- Android 同步服务新增消息离线入队映射：稳定 JSON 编码、调用方 messageId、普通/高优先级与 TTL。首次入队和 TTL 身份记录同事务提交；重试/重启/ACK 后保留首次到期时间，不重新投递已确认消息，改内容或 TTL 拒绝；独立 10,000 条未过期身份记录配额不驱逐旧请求。OWW242 上队列与服务共 9 项测试通过。消息事件/回执、实际宿主注入和双端链路仍待接入。

- Android runtime 新增宿主批准的同步服务适配与 `PodServices` 分发入口，状态 get/set/delete 复用统一持久 owner 并返回准确条目/墓碑；能力集合复制冻结，未授权/取消/缺少连接操作明确拒绝。OWW242 上两项真实服务分发测试通过。正常启动尚未注入 owner，消息/文件/同步完成屏障与 guest 事件仍待接入，因此没有开启同步 capability 声明。

- Harmony 示例新增文本消息页：显式批准收件设备、一天 TTL 普通消息离线入队、未知格式拒绝处理、最多 50 条待发/100 条待处理刷新、明确“已读”确认后才持久标记并回执。入队不确定时复用原 ID/内容/到期时间，保留新草稿；放弃重试只清瞬态 UI，不撤回队列。实际页面/文本编解码 5 项测试通过，DevEco HAP 编译通过（6.821 秒）；跨进程重试状态不保留，需要检查持久队列，真实跨设备收发与读屏仍待验收。

- Harmony 示例文件列表已装配完整副本发送/恢复、对端授权后显式继续、进度刷新与终态回执确认。终态回执跨重启保留，确认后经传输串行队列与持久 CAS 核对 peer/请求 ID/transfer ID/digest/phase，再释放单对端队列；不删除文件、不清理在途请求、不把收到文件等同系统导出。实际页面测试及认证双文件连续传输测试通过，DevEco HAP 编译通过；真实双设备文件传输/授权/布局仍未验收，消息 UI 尚待接入。

- Harmony 示例状态页已订阅持久状态与连接状态：干净编辑框跟随最新笔记，未保存草稿不被远端写入覆盖；显式确认载入已保存内容，保存期间的新编辑保留，离页/旧快照/旧确认回调不能覆盖新状态。不把本机保存成功宣称为对端收到。实际页面方法测试覆盖上述边界，DevEco 编译通过；消息与文件发送 UI、真机状态同步仍待完成。

- Harmony 示例新增已配对 BLE 数据连接页：只列已批准的应用身份，显式等待或发现并选择临时路由，经已保存 signer 认证后交给独占连接 owner；权限/发现/握手取消、迟到连接释放、标签切换保留已建立会话，前台两分钟上限且不自动重连。状态和消息发送驱动已可按 ACK 推进；示例接收状态刷新、消息与文件发送入口仍需继续接入，跨设备/实际布局/读屏仍待真机验证。连接 owner 与页面方法测试通过，不能代替真实 BLE 和 HUKS 验收。

- Harmony 邀请页已默认装配独立 BLE：发行端等待/广播、扫码端五秒发现与显式选择、权限请求、三十秒候选有效期及授权返回后再检查；系统分布式链路保留为显式可选项，切换清除地址，不把 BLE 虚拟地址复用于 linkEnhance。离页/后台/邀请过期会取消扫描并隔离迟到结果，确认按钮绑定具体审批对象，旧按钮不能批准新请求。保持现有蓝白布局并明确显示候选不等于身份；全部 242 项 Harmony 测试、4141 个断言通过，最终 HAP 构建通过（6.687 秒）。页面实际布局/读屏、系统授权/扫码 UI 生命周期、双设备无线配对仍待真机验收；已配对的数据连接与消息/文件发送 UI 仍待实现。

- Harmony 独立 GATT 服务端/广播已实现：实例广播 ID、最长30秒广播、单 listener/单 central、CCC 成功响应前分配接收流、确认 indication 写入、异常请求拒绝和取消后迟到广播停止；SDK 的无 peer MTU 回调不跨连接混用，API26 可用时显式断连后关闭 server。增加 `ACCESS_BLUETOOTH` 前台权限 helper/示例声明，准确核对 grant/取消，不开启无线或申请真实 MAC。真实客户端与服务端源码经模拟 OS 总线完成双向分片数据传输；全部 237 项 Harmony 测试、4114 个断言通过，最终 HAR 6.995秒、权限 HAP 7.290秒构建通过并取回，包内蓝牙权限已核对。当前 hdc 无设备；BLE 页面装配、实际广播/订阅/无线传输及旧系统连接释放仍未验收，不将底层候选连接当作配对批准。

- Harmony 已实现独立 GATT 客户端及 Android 兼容 BLE 分片流：P/1+u32 分片序号、MTU 上限、精确重复幂等、间隙/改写重放关闭；实际服务/特征/CCC 校验，协商 MTU、等待 indication 订阅，逐片带响应写入与连接总截止。编译真实 Android Java codec 与 Harmony TS codec，在 MTU 23/185/517 双向互通通过；全部 235 项 Harmony 测试、3989 个断言通过，HAR 编译通过（6.749 秒）。SDK 的 ArrayBufferLike/ArrayBuffer 边界已改为显式复制，避免 ArkTS 结构类型错误。仍缺 BLE 权限/选择 UI、Harmony GATT 服务端与广播、双设备无线及加密链路验收；此客户端不自动配对，也不宣称 BLE 已加密。

- Harmony 新增独立 PodJS GATT 服务发现适配，使用实际 SDK 的实例扫描器并复用 Android service UUID；100–10000ms 总截止、32 候选上限、256 条回调批次上限、取消/迟到启动清理及显示名称控制字符过滤。实际源码模拟测试 24 个断言通过，HAR 编译通过（6.638 秒）。SDK 26 的地址可能虚拟化，结果不当作真实 MAC/应用身份，也不直接传给 linkEnhance；权限 UI、广播、GATT 数据链路、候选选择页面和真机验收仍待完成，发现接口不代表完整 BLE 传输可用。

- Harmony 邀请页已装配权限请求、指定 BLE 地址的 linkEnhance 连接/监听、初始配对核心和双方身份确认门；扫码不自动保存凭据，确认/拒绝为显式按钮，切页/退后台取消尝试并隔离迟到授权，最终回执失败提示检查可能已保存的凭据。确认门同时拒绝旧回调、重复回答和显示异常后的同步批准。实际组件方法模拟测试与协议测试通过；全部 230 项 Harmony TS 测试、3914 个断言通过，最终 HAP 编译通过（7.318 秒），已取回 unsigned HAP。当前 hdc 无设备，未宣称 UI/读屏/双设备无线验收；附近设备发现、已配对后的数据连接 UI 及消息/发送控制仍待完成。手工地址仅是当前连接入口，不替代计划要求的发现能力。

- Harmony 示例补齐 linkEnhance 所需 `DISTRIBUTED_DATASYNC` 声明与前台用途说明，并新增显式权限请求适配：能力检查、当前 grant、准确返回权限核对、并发拒绝及取消后迟到授权抑制，不自动联网/启用无线/切换链路。实际源码测试 36 个断言通过，HAP 编译通过（10.214 秒），取回包内 module.json 已核对权限与 inuse 场景。尚未在连接按钮调用此入口或真机验证，授权成功不代表设备存在未裁剪的 linkEnhance 能力。

- Harmony 手机示例新增邀请标签页：系统 QR 生成与扫码按钮、带白色静区的无滤波方形图像、临时对端身份显示、取消/超时/切换标签/离页清理及迟到图像释放；不显示密钥文本，不把扫码成功当作配对成功。HAP 编译通过（6.821 秒），当前 hdc 仍无设备，页面实际布局/图像生命周期/摄像头识别尚未验收。邀请页与连接监听、双端确认核心的装配仍待完成。

- Harmony 新增 ScanKit 字节数组 QR 图像生成适配，避免系统文本二维码接口的 512 字符上限；固定黑白 512×512，生成后清零输入字节，过期/取消/并发领取后释放迟到图像。实际源码模拟测试覆盖长身份与安全整数时钟边界的完整载荷、异常清理（21 个断言），HAR 编译通过（6.563 秒）。页面仍需接图像显示、白色静区与生命周期释放，生成图像尚未真机扫描验证。

- Harmony 已按实际 SDK ScanKit 接系统扫码适配：默认 UI、相机单 QR、禁用相册、结果来源/邀请校验、取消与迟到结果抑制、错误去敏，不自动连接/保存密钥，不额外申请 CAMERA。实际适配源码模拟测试及初始配对相关共 13 项、103 个断言通过，HAR 编译通过（6.537 秒）。该系统 API 无程序化关闭扫码 UI 接口，页面取消仅丢弃迟到结果；示例按钮、真实摄像头扫码和配对全流程仍待接入/验收。

- Harmony 新增首次配对双端确认核心：邀请密钥完成已有 challenge/HMAC 握手后，两端分别明确批准；严格核对邀请 ID 和 approved/stored 控制帧，按 session/invitation 域分离生成永久密钥，经本机持久存储并收到对端 stored 才报告完成。已存在配对不替换，取消/超时清理握手，存储阶段失败显式保留“本机可能已有凭据”状态。初始配对/邀请 lease 共 9 项、50 个断言通过，HAR 编译通过（7.448 秒）。QR/审批对话框及真实跨设备传输仍未接入；断线导致最终回执丢失时无法保证双设备原子提交，需检查持久配对记录。

- Harmony 配对邀请新增在内存中持有邀请的生命周期控制：独立计时、单次领取、领取后隐藏 QR 回调、超时/关闭清零在途密钥并取消尝试；重复领取与异常清理后复用拒绝。3 项测试、25 个断言通过，HAR 编译通过（6.782 秒）。实际 QR 页面回调、双端确认/认证交换和传输端候选验证仍未接入，不能将对象单次领取宣称为完整网络防重放。

- Harmony 新增首次配对邀请 QR 数据编解码：固定字段、独立随机 invitationId/secret、五分钟到期、app/设备核对、重复/未知字段拒绝及内存对象单次取密钥。3 项测试、29 个断言通过，HAR 编译通过（7.617 秒）。这仅是数据载体，重复扫描同一文本尚不能构成网络防重放；发行端邀请生命周期、双端明确确认/认证交换、扫码与二维码展示仍待实现，不自动导入永久配对。

- Harmony 手机示例新增配对管理标签页：共用惰性配对 owner、只显示 peer/phase、确认撤销、失败后恢复刷新和离页迟到回调隔离；明确区分已配对与已连接，首次配对入口仍标示未接入。实际 ExampleClient 源码测试验证并发共用/失败重试（21 个断言），HAP 编译通过（8.509 秒）。当前 `hdc list targets` 为空，页面布局/读屏/撤销对话框尚未真机验收；初始授权交换与连接 UI 仍待完成。

- Harmony 新增不含密钥 ID 的配对列表、可释放但不撤销持久配对的签名句柄，以及 `createCompanionPairedAttempt`：截止覆盖凭据读取和原握手，撤销取消待握手/关闭已交付连接，普通连接关闭释放句柄。真实 HMAC 双端流测试通过，认证/配对相关 24 项、257 个断言通过，HAR 编译通过（7.097 秒）。这仍是宿主 SDK 接入，示例尚未显示配对/连接管理，初始授权交换与真机链路未验收。

- Harmony 配对已接真实原生全生命周期 journal lease 与 `openCompanionPairings` 工厂，跨 HUKS await 持有 OS 文件锁，工厂返回前恢复中断意图。原生测试验证跨进程/普通 CAS 排他、不同 app 独立、进程退出释放锁、重开、并发调用拒绝、关闭时迟到读取拒绝与权限检查；全部原生回归通过。修正工厂 ArkTS 字段声明/异常类型后 HAR 编译通过（6.469 秒）。初始配对授权/密钥交换 UI、示例连接接入及 HUKS 真机验收仍待完成。

- Harmony 新增 `CompanionPairings` 生命周期核心：先持久化 importing 再导入 HUKS/批准，恢复只清理中断导入；撤销立即失效签名器并调用断连，再写 revoking、删除密钥和提交清理；签名前后核验身份记录，关闭等待在途操作后才释放所有权。4 项故障/恢复/迟到签名测试通过，认证/HUKS/配对共 15 项、119 个断言通过，HAR 编译通过（6.416 秒）。核心强制要求全生命周期独占 OS lease 接口，真实 native lease 工厂仍待接入，已有 CAS 存储不能冒充此锁；初始配对与真机验收尚未完成。

- Harmony 新增配对元数据专用原生 CAS namespace 与 `NativeCompanionPairingStore`，与同步状态/inbox/outbox 隔离，复用私有权限、锁、原子提交和 fsync 边界。真实 Linux 原生文件测试验证跨进程重读/修改、旧值冲突、app 隔离和不安全权限拒绝，全部后台/companion 原生回归通过；HAR 编译通过（6.581 秒）。目前仅存储层，尚未将 HUKS 导入/撤销意图与身份绑定状态机接入，不能视为配对闭环。

- Harmony 新增实际 HUKS 低层适配：256 位 HMAC-SHA256 密钥导入/存在检查/删除、64 KiB 分块签名、异常 abort、临时密钥清零和同进程重复导入保护，不提供密钥导出。实际适配源码的系统模拟测试通过（45 个断言），DevEco HAR 编译通过（6.464 秒）；原生握手/会话工厂也已接签名器类型。此层不等于配对授权：持久身份索引、跨进程串行化、撤销后关闭连接/拒绝迟到签名及配对 UI 仍待实现，HUKS 真机行为尚未验证。

- Harmony 认证握手/交换/连接 attempt 增加身份绑定的 `CompanionSyncSigner` 路径，允许系统不可导出密钥只执行 HMAC，保留原始字节密钥兼容入口和关闭后迟到结果隔离。与原始密钥对端的 proof/帧互操作、错误身份和撤销失败测试通过，21 项认证相关回归通过，HAR 编译通过（6.909 秒）。目前仅完成签名器接口和协议接入，HUKS 实际密钥导入/持久索引/撤销与配对 UI 仍待实现。

- Harmony 手机示例已接发送源“选择原文件继续导入”和确认清理副本；导入失败后刷新持久暂存列表，同一 ID 的不同内容仍拒绝，清理不删除系统原文件。实际 HAP 编译打包通过（6.668 秒）；底层源变更/续传/清理已有原生回归，新增页面按钮和确认对话框仍待真机交互验收。

- Harmony 已新增独立 phone/tablet `companion_example` entry，不修改 wearable 页面：持久随机设备身份、离线笔记保存、系统文档导入及同实例接收授权面板已有真实应用入口；明确标示未连接。构建脚本增加 `-Example` 并修复 HAP 打包 Java PATH，实际 ArkTS/原生依赖/HAP 打包通过（最终 3.269 秒）；已取回 unsigned HAP 并确认包含原生库和页面字节码。`hdc list targets` 当前为空，尚无安装/页面/读屏验收；配对、网络连接、消息/发送/源清理控制仍待实现。

- 修正 Harmony 收发各自计算文件配额的缺口：原生增加统一 app 文件锁和跨 incoming/outgoing 的 32 MiB 持久 reservation，按两倍文件大小覆盖组装，内容删除 fsync 后才释放。原生测试验证跨方向互斥、发送占满时拒绝接收、释放后恢复，以及无记账旧数据 fail closed；全部原生回归与 HAR 编译通过（4.034 秒）。旧原型数据缺少 reservation 时须显式删除/重新导入，不静默计为空闲；真机故障恢复仍待验证。

- Harmony `pickCompanionFile` 已调用系统单文档选择器，只读打开选定 URI，限制普通文件/16 MiB、按 64 KiB 定位读取并处理短读，所有导入退出路径关闭句柄。实际适配源码的系统模拟测试覆盖两遍定位、997 字节短读、取消/拒绝权限/过大文件/截断/导入异常；HAR 编译通过（6.959 秒）。尚未在应用页面触发系统选择器，也未完成真机权限和 UI 验收。

- Harmony `NativeCompanionClient.importFile` 已接系统流式 SHA-256，按 64 KiB 两遍读取自动生成 manifest、导入缺块并完成发布。真实原生测试验证源内容中途变化拒绝、取消保留首块、重开只补第二块及重复导入不重写；全部原生回归通过，HAR 编译通过（6.255 秒）。系统文件选择器/源句柄适配与真机导入仍待接入和验收。

- Harmony 发送源已增加独立原生 outgoing 目录、持久 manifest/分块暂存、整文件校验发布、重开读取、双份组装配额和删除意图恢复，`NativeCompanionClient.outgoingFiles/createStoredFileSender` 可通过 ID 重建发送器。原生联调从真实磁盘源驱动丢回复续传并验证最终字节，应用入口测试覆盖未完成/缺失源拒绝；HAR 编译通过（6.729 秒）。文件选择/import UI 与真实设备进程重启、认证无线全流程仍待验收。

- Harmony 新增应用级 `NativeCompanionClient`，按同一 app/local 身份装配原生状态、消息 inbox/outbox、文件请求和接收器，创建匹配队列的发送器，绑定既有连接时核对身份并限制单个前台 owner，关闭不删除持久工作。实际 SDK HAR 编译通过（6.023 秒）。该装配入口不创建配对/连接，也未替代真实应用导航、源文件管理或设备验收。

- Harmony 共享 `CompanionMessageTransport.driveFile` 已绑定同一 peer/请求队列的发送驱动，在认证回复持久化后自动推进；等待同意暂停，显式恢复后继续，不添加轮询。新增 HMAC 双端流测试覆盖暂停、恢复、缺块/完成和连续签名序号；全部 195 项 Harmony 测试通过，HAR 编译通过（6.129 秒）。调用说明明确源文件持久化、终态消费、连接失败和队列独占边界；实际应用会话及真机链路仍待验收。

- Harmony `CompanionFileSender.step` 已驱动持久请求队列完成 offer/等待同意/missing/chunk/finish；源分块长度与哈希变化拒绝推进，待回复保留原请求 ID，终态观察保留给应用消费。模拟回复测试及真实原生请求 journal＋接收存储联调通过，后者每步重建对象并丢弃一次分块回复，最终 65,539 字节逐字节一致；HAR 编译通过（6.053 秒）。联调直接调用已认证接收入口，不等于认证无线链路或跨进程全流程验收；应用仍须保存不可变源文件/manifest 并将驱动接入共享 transport。

- Harmony HAR 新增可嵌入 `CompanionIncomingFilesPanel`：使用应用传入的同一接收器，展示来源/大小/状态，显式接收、拒绝、取消清理和只读十六进制预览，不自动执行收到的内容、不增加后台轮询。组件和调用说明已加入 companion，实际 SDK HAR 编译通过（6.071 秒）。目前是可复用组件，尚未接入真实应用导航/认证会话，也未完成设备布局、读屏和点击验收。

- Harmony 接收端增加本地 `listLocal` / `cancelLocal` 和 `readCompleteChunk`：可列出授权/历史清单、持久拒绝/清理，只有完成状态可读取，单次最多 64 KiB，原生读取重新校验长度和分块 SHA-256；远程命令不开放读取。实际原生桥接验证完整读取、未完成/越界拒绝及损坏检测，3 项接收生命周期测试通过，新增读取接口 HAR 编译通过。授权 UI 和应用消费示例、真机验收仍待接入。

- Harmony 文件存储已接异步 N-API lease 与 ArkTS `createCompanionIncomingFiles`：一次接收事务覆盖 journal/内容更新的跨进程锁，关闭/回收句柄拒绝后续使用，IO 在 worker 执行。真实 Linux 原生桥接与接收生命周期测试覆盖本地同意前零分配、65,539 字节分块、重开缺块恢复、整文件发布、取消清理、并发/关闭/异常释放锁；原生回归通过。新增接口和原生 OH Crypto 一起通过 Harmony HAR 编译（6.281 秒），产物已取回；这不替代 Harmony 真机存储、授权 UI、无线续传与文件消费验收。

- Harmony 新增 C++ 文件存储后端：私有 app/peer/transfer 目录、生命周期 flock、原子 journal/分块写、64 KiB 流式逐块/整文件 SHA-256、校验后原子发布、已完成清理重试和仅删除已知文件。真实 Linux/OpenSSL 测试覆盖 65,539 字节重开恢复、跨进程锁、错误哈希不发布、取消及未知文件保留；SDK 原生 OH Crypto 接口编译与 HAR 构建通过（3.473 秒），既有原生回归通过。尚未接 N-API 句柄和 ArkTS 接收器，Harmony 真机 SHA/IO 与续传验收仍未完成。

- Harmony 新增 incoming-files 生命周期核心：offer 仅记清单、本地 accept 意图先落盘、32 MiB 预留配额、逐块 SHA-256 校验、取消意图与恢复顺序、完成后状态持久化。2 项内存后端测试及全部 192 项 Harmony 测试通过，HAR 编译通过（6.079 秒）。跨进程锁/原子块存储/整文件校验发布目前是后端契约，原生实现和真实文件续传仍未完成。

- Harmony 共享 pump/transport 接入可选文件通道配置，sendFile 与 state/message 独立在途 ID，文件回复只有持久化成功且 ID 匹配才解除在途限制。新增三通道测试验证延迟业务 ACK 期间状态/文件请求继续、同 ID 不串确认及统一发送序号；全部 190 项 Harmony 测试通过，HAR 编译通过（5.961 秒）。文件接收器仍为接口/测试替身，真实内容存储、续传和设备验收未完成。

- Harmony 新增认证文件 pump：原请求重发、回复先持久化再提交序号、接收器身份绑定/请求校验/回复字段限制，任何存储或执行失败关闭且不发成功回复。3 项真实 HMAC/注入接收器测试及全部 189 项 Harmony 测试通过，HAR 编译通过（5.961 秒）。接收器目前仅定义持久执行接口，实际文件存储、三通道传输整合和设备续传仍未完成。

- Harmony 文件请求队列接入独立原生 journal、系统随机 ID/SHA-256 和 `createCompanionFileRequests`。真实 Linux N-API 测试覆盖完整 64 KiB 分片请求跨进程恢复、回复落盘与消费删除、状态/消息隔离、CAS 和不安全文件拒绝；既有原生回归通过，HAR 编译通过（6.062 秒）。文件接收内容存储、收发 pump 与设备续传验收仍待完成。

- Harmony 新增文件请求 CAS 队列：生成稳定 ID、保留原始字节/摘要、每 peer 一个 pending、首个匹配回复持久化且重复回复不覆盖、completed/forget 显式消费、128 条/8 MiB 含回复预留配额。3 项注入存储测试和全部 186 项 Harmony 测试通过，HAR 编译通过（5.974 秒）。原生请求存储、文件收发 pump 和实际接收器仍待完成。

- Harmony 新增文件回复编解码：绑定原请求 SHA-256、4 KiB 回复上限、方法/phase 匹配、缺块索引排序与范围检查、终态缺块矛盾及额外字段拒绝。2 项测试和全部 183 项 Harmony 测试通过，HAR 编译通过（5.974 秒）。持久文件请求/回复记录、接收存储与完整文件传输仍待完成。

- Harmony 新增文件请求编解码：96 KiB 严格 UTF-8 JSON、五字段 manifest/16 MiB 上限、64 KiB 规范 Base64 分片、嵌套重复字段拒绝，远端 accept/路径/未知字段不可进入协议。3 项测试和全部 181 项 Harmony 测试通过，HAR 编译通过（5.895 秒）。文件回复校验、持久发送请求、接收存储/本地接受及三通道设备验收仍待实现。

- Harmony 消息 transport 新增可选 state 参数启用共享通道，sendState 与 sendNext 分别跟踪在途 ID，ACK 同时匹配通道和 ID；签名/读写继续统一串行。新增混合传输测试验证消息待业务确认时连续状态更新及同 ID ACK 隔离，全部 178 项 Harmony 测试通过，HAR 编译通过（5.759 秒）。文件通道、自动队列驱动、真实链路和设备验收仍待完成。

- Harmony 新增同会话 state/message 共享 pump，统一操作互斥与失败关闭，ACK kind 1/2 路由消息、kind 3 路由状态，完整签名验证后才进入存储。3 项测试覆盖相同 ID ACK 隔离、延迟消息期间状态通过、业务失败阻止后帧、篡改分流提示拒绝；全部 177 项 Harmony 测试通过，HAR 编译通过（6.011 秒）。共享 transport 驱动、文件通道与设备验收仍待完成。

- Harmony 新增前台消息 transport：独占已认证连接、帧解码/串行签名写入、最多 8 个排队操作、一条未 ACK 在途消息、接收持久化与显式业务确认、运行截止立即结束卡住调用并抑制迟到发送。3 项注入 packet-link 测试及全部 174 项 Harmony 测试通过，HAR 编译通过（5.803 秒）。宿主仍需调用 sendNext 驱动后续队列；多通道集成、真实加密链路和设备验收未完成。

- Harmony 新增专用认证消息 pump：绑定队列身份、发送不删除、先存 pending/业务回执再提交接收序号与签名 ACK、延迟业务确认、失败关闭和并发操作拒绝。3 项真实 HMAC/注入存储测试覆盖丢 ACK 新会话重发、业务回调不重复、延迟 ACK、业务/磁盘失败保留 pending；全部 171 项 Harmony 测试通过，HAR 编译通过（5.991 秒）。当前由宿主负责有界 transport IO，尚未接状态/文件多路复用或双设备验收。

- Harmony inbox 接入独立原生 journal 和 `createCompanionMessageInbox`。真实 Linux N-API 文件测试覆盖 256 KiB pending 跨进程读取/业务确认、再开进程重发得到 applied 回执，验证 inbox/outbox/state 隔离、CAS 及不安全权限拒绝；既有状态与后台回归通过，HAR 编译通过（5.902 秒）。认证消息 pump、业务端接入和双设备验收仍未完成。

- Harmony 新增独立 CAS inbox 核心：先存 pending、peer/ID/摘要绑定业务确认、applied 回执保留至过期、重发去重、优先级/FIFO、1,000 条/8 MiB 配额且不驱逐活跃回执。3 项注入存储测试和全部 168 项 Harmony 测试通过，HAR 编译通过（5.792 秒）。原生 inbox 存储、认证消息 pump 和设备投递仍未完成；业务副作用仍必须按 peer/ID 幂等。

- Harmony outbox 已接独立 app 私有原生 journal，新增读取/CAS N-API 和 `createCompanionMessageOutbox`。真实 Linux 原生文件后端验证完整 256 KiB 消息跨进程重开、ACK 删除落盘、状态/应用隔离、冲突写与不安全权限拒绝，既有后台/状态文件回归通过；HAR 编译通过（5.852 秒）。Harmony 真机存储、inbox、消息投递与业务确认仍待完成。

- Harmony 新增 CAS 消息 outbox 核心：离线读取不删除、优先级/FIFO、TTL、同 ID 内容约束、peer+摘要绑定 ACK 删除、1,000 条/8 MiB 配额、冲突写拒绝和快照身份/摘要校验。4 项注入存储测试及全部 165 项 Harmony 测试通过，HAR 编译通过（5.725 秒）。尚未接原生独立消息存储、inbox/业务回执和消息 pump，不能视为设备持久消息闭环。

- Harmony 新增 Android 格式消息/ACK 二进制编解码：大端安全整数 expiry、优先级、256 KiB 原始载荷、kind 1/2 与 32 字节摘要；复制输入并拒绝非法长度/元数据。3 项测试及全部 161 项 Harmony 回归通过，HAR 构建通过（5.867 秒）。当前仅线格式，持久消息队列、业务确认、认证路由和设备投递仍未完成。

- 系统分布式已接受连接的超时/远端断开现在自动释放监听服务资源，无需调用方再次 close；停止监听失败拒绝交付连接并清理双方资源。新增 2 项源码模拟测试，全部 158 项 Harmony 测试通过；HAR 构建通过（5.989 秒）。设备无线行为与整体计划仍未验收完成。

- Harmony 系统分布式服务端新增单次 `NativeCompanionDistributedAcceptor`：仅接受已批准 BLE 对端，拒绝额外/迟到连接，接受后停止监听，关闭会话时释放服务；系统停止、权限失败和取消均终止等待。3 项新增原生源码模拟测试通过，全部 156 项 Harmony 回归通过，DevEco HAR 编译通过（6.180 秒）。系统无线、权限及停止监听后连接存活的设备验收仍未完成；HMAC 应用认证仍为必需，不把地址匹配视为配对或加密保证。

- Harmony 新增 linkEnhance 主动 connector 与已连接/接受通道适配：固定已批准 BLE 地址，连接结果和实际对端二次匹配，1024 字节发送分片、每 8 KiB 让出事件循环，错误/取消关闭。4 项分片和 3 项原生适配源码模拟测试通过；全部 153 项 Harmony、77 项 companion 及双原生文件后端回归通过，HAR 编译通过（5.682 秒）。尚未完成系统服务端监听、设备权限/无线/加密保证验收，不将 MAC 匹配视为应用配对。

- Harmony 新增固定已批准 CA/叶证书 SHA256 的 TLS 客户端 connector：保留系统证书校验、仅 TLS 1.2/1.3、指纹通过后才交付字节流，无明文回退。真实 Linux TLS 验证正确/错误 CA 与指纹，全部 146 项 Harmony、70 项 companion 及双原生文件后端回归通过，HAR 编译通过（5.619 秒）。SDK 存在 API-20 linkEnhance 系统数据接口，不能以 SDK 缺失跳过；该适配、TLS 服务端/证书配置、消息/文件及设备验收仍未完成。

- Harmony 新增 `CompanionState.synchronize` 前台驱动与原生 timer 工厂：自动状态通知、单批在途、ACK 后续批、统一签名/写入顺序、独立运行截止和取消。600 条双向同步、后续编辑、丢 ACK 重连与卡住存储测试通过，真实 Linux TCP 也跑通完整状态驱动；全部 142 项 Harmony、66 项 companion 及双原生文件后端回归通过，HAR 编译通过（5.666 秒）。drain 只证明已知本地批次获 ACK，不宣称远端无新数据；加密 connector、消息/文件、手机示例及真机验收仍未完成。

- Harmony 新增统一建连/握手 attempt 截止与取消，覆盖 connector、随机数、HMAC 和全部 hello/proof IO；底层 Promise 卡住也立即结束调用，关闭迟到连接/会话，成功后独立移交连接所有权。5 项测试覆盖卡住/清理抛错/迟到与成功移交；全部 139 项 Harmony 回归和 HAR 编译通过（5.746 秒）。具体加密 connector、高层状态同步驱动与真机验收仍未完成。

- Harmony 新增有界 packet stream 和已连接 TCP socket 适配：单读者、串行写、收发配额、固定 IO 生命周期、超时/关闭立即拒绝等待者，迟到系统写结果不恢复连接。5 项测试含真实 Linux loopback TCP 分片握手/认证帧；全部 134 项 Harmony 回归、HAR 编译通过（5.668 秒）。Harmony 原生 socket 未真机执行；TCP 无加密，仅开发/独立加密链路使用，建连、覆盖 crypto 的端到端截止、高层 synchronize 与无线验收仍未完成。

- Harmony 新增 Android 格式 hello/proof 双向交换与系统随机挑战工厂，8 KiB 握手包严格 UTF-8/JSON、重复根字段拒绝、固定已批准对端、失败关闭和迟到结果抑制。6 项交换测试及全部 129 项 Harmony 回归通过，HAR 编译通过（5.800 秒）。测试使用注入 packet link/Node crypto；具体 socket、截止时间/背压、高层 synchronize 与双设备验收仍未完成。

- Harmony 新增兼容 Android 的 u32 大端长度分帧：1..2 MiB 上限、增量分片/粘包、单个有界未完成缓冲、截断 EOF/非法长度/回调异常关闭。5 项分帧测试和全部 123 项 Harmony 回归通过，HAR 编译通过（5.680 秒）。尚未接 hello/严格 JSON envelope 解码、socket IO/背压与 synchronize 高层驱动，不视为实际无线传输完成。

- Harmony 新增认证 state/ACK 路由 `CompanionStatePump`：校验 app/local 绑定、从 session 获取 peer、严格 UTF-8、状态和 ACK 进度落盘后提交瞬态序号，失败关闭。5 项路由测试覆盖新会话丢 ACK 重放及双侧写失败，全部 118 项 Harmony 回归通过；HAR 编译通过（5.398 秒）。测试为注入存储/密码学，hello/线分帧、transport IO、synchronize 高层驱动与真机无线验收仍待实现。

- Harmony 认证帧 session 已增加 send/verify/commit、授权通道、串行序号、待提交重放与失败关闭；严格拒绝额外字段/非法字节，排队前复制输入，状态写入成功前不会推进确认。现场 Rust 向量验证完整帧一致，9 项认证测试及全部 113 项 Harmony 回归通过，HAR 编译通过（5.950 秒）。hello 交换、严格 UTF-8 线解码、状态路由与 synchronize 驱动及真机无线验收仍待实现。

- Harmony HAR 新增双向 HMAC 握手原语与系统 CryptoFramework 工厂：应用/双方身份/新挑战绑定、角色隔离证明、失败关闭及迟到结果抑制。Rust 现场生成向量逐字节验证双方证明和 session ID；5 项认证测试及全部 109 项 Harmony 回归通过，HAR 编译通过（5.405 秒）。尚未实现认证帧 session、hello 交换与 synchronize；系统密码学真机执行和双设备无线验收仍未完成。

- Harmony 状态接收端新增 schema 3 持久 wire receipt 和 kind-3 ACK 编解码：状态/游标/批次 ID/摘要同事务落盘，重启后精确重发可确认，换 ID/字节、旧批次与跳跃拒绝；保留 schema 1/2 状态和待确认发送迁移。28 项 companion、104 项 Harmony 回归及实际文件后端子进程重放通过。认证 session、严格 UTF-8 传输解码与 synchronize 驱动仍待接入，不视为无线闭环。

- Harmony HAR 状态发送端新增持久 prepare/ACK 周期：512 条/256 KiB 批次、固定 ID/原始字节/SHA-256、断线重发、完整匹配 ACK 后推进，发送期间的新修改留待下一周期；schema 2 保留旧状态/游标迁移。23 项 companion 与 99 项 Harmony 回归、实际原生文件重开和 HAR 编译通过。仍未接认证传输、接收 wire receipt 或 synchronize 驱动，不代表无线状态闭环。

- Harmony 状态 SDK 已抽成独立 `companion` HAR 模块并生成 `dist/harmonyos-companion/podjs-companion.har`：包含 ArkTS API、类型声明及 ARM64 原生库，不依赖手表渲染/Rust runtime。DevEco HAR 构建与移除源码模块后的 archive 依赖编译通过；18 项状态测试、94 项 Harmony 回归和独立 N-API 库实际文件测试通过。当前是 API 23 / ARM64 的未签名状态层开发包，发送同步、消息/文件、手机示例及设备验收仍待完成。

- Harmony 新增可编译的 `CompanionState` 与原生工厂：状态读写/墓碑、本地订阅、认证后批次接收，app/device 绑定、同 revision 冲突拒绝、原子状态/游标和显式 CAS 竞争错误。18 项 companion/模型测试、94 项 Harmony 回归及真实文件后端跨进程重开通过；完整工厂/核心显式导入 DevEco 编译通过，移除探针后正常 HAP 再次成功（1.422 秒）。尚缺独立手机 SDK 模块、发送 synchronize/ACK 周期、其他通道及双设备验收，见 [harmony-companion-sdk.md](harmony-companion-sdk.md)。

- companion 状态存储原生桥已通过 Windows DevEco 编译链接，`NativeCompanionStateCas` 显式导入探针通过 ArkTS 编译；移除探针后正常未签名 HAP 再次构建成功（1.496 秒）。工作区锁文件注册 companion 包，新增 `bun run test:companion`，12 项单测及实际 N-API 文件回归通过。此构建只覆盖原生端口，共享 TS 状态核心的 ArkTS 兼容/完整手机 SDK 打包与设备验收仍待完成；既有权限、API 异常处理和弃用告警仍存在。

- Harmony 原生新增 app 隔离的 companion 状态 read/CAS 接口及 ArkTS 端口，复用私有目录、进程锁、原子替换和 fsync；异步 SDK 经 `CasStateDatabase` 已在 Linux N-API 实际文件后端通过进程重开、墓碑、隔离、竞争写拒绝、不安全文件拒绝及状态/游标持久化测试。原有后台 5 进程 CAS/任务日志回归也通过。尚未完成本轮 Harmony SDK 构建、ArkTS 核心打包或手机真机验收。

- 新增 `packages/companion` 异步 SDK 状态核心，复用确定性合并，支持 app 命名空间事务、get/set/delete、本地订阅及认证后状态接收；不缓存不确定提交，ACK 只在事务完成后返回。核心与状态模型 12 项测试通过。当前后端测试为内存事务，Harmony 原生持久化/ArkTS 打包、完整 synchronize 和其他通道尚未实现，不视为三平台 SDK 交付完成。

- 共享 TypeScript 帧解码器补齐连接终止与回调重入边界：正常 EOF 后拒绝所有新输入，重复干净 EOF 幂等；回调内递归 push/finish 即使被回调捕获，也终止外层后续帧交付。帧/状态/消息 21 项回归通过；这不是 Harmony/iOS 手机 SDK 或无线互通验收。

- Android SDK 新增 `PodRfcommAttempt`：公开 secure RFCOMM API、既有系统绑定检查、同一应用 HMAC 握手、统一截止/取消和 Session 移交；不自动绑定、不取消其他扫描，也不回退 insecure socket。OWW242 RFCOMM/BLE attempt/SDK/LAN 24 项通过（26.372 秒）；RFCOMM 生命周期测试注入 loopback 字节流，真实系统绑定/SDP/无线交换及示例入口仍待完成。

- 主机 BLE 失败只读诊断将已有崩溃栈映射到 BlueZ `device_found_callback` → `btd_adapter_device_found` 的发现事件路径，未确定底层原因，也未读取受限 core 或重试扫描。无线测试工具新增 D-Bus daemon owner 变更/消失即取消的保护，7 项无无线单测通过；这不代表修复主机崩溃或完成无线验收。

- BLE 等待端不再依赖读取/手填 central 隐藏地址：新增首条无线候选路由，随后仍验证固定的已批准应用 peer；保留严格地址 API，后续设备不能替换候选，失败不自动重开。示例文案明确区分等待与扫描连接。OWW242 路由/协议 23 项、SDK/LAN 21 项（含错密钥拒绝）、UI 14 项通过；新模式真实 GATT/认证无线运行仍未完成，未重试主机扫描。

- Pixel 11 API 36 真实 Nearby 权限弹窗通过：先拒绝、后允许，三项权限状态与 UI 一致，允许后不自动扫描/连接（1 项，2.083 秒）；其余 UI/文档/LAN/真实选择器 15 项通过（29.63 秒）。测试后将示例三项 Nearby 授权恢复为初始拒绝状态，未重试主机蓝牙。物理设备无线互通与 TalkBack/新版视觉验收仍未完成。

- Android companion 新增 BLE 权限、5 秒发现/地址选择、连接认证与指定 central 等待入口，复用 30 秒 attempt 和 120 秒前台驱动；后台取消扫描并丢弃迟到结果，权限回调不会自动操作。OWW242 BLE UI/文档/LAN/核心 UI 14 项通过（43.711 秒），BLE 测试注入权限与扫描结果；真实权限弹窗、新版手机视觉/TalkBack 与无线双端仍待验收。等待模式仍需已知 central 蓝牙地址，不绕过地址隐私或配对。

- 已增加 Linux central↔Android peripheral 的双无线测试工具，使用实际 MTU、4,099 字节确定性往返且不配对/发送应用密钥。首次实跑未通过：主机 BlueZ 5.87 在扫描期间崩溃并自动重启，手表 30.227 秒接受超时，未取得无线 MTU/CCC/数据验收；已停止该主机无线重试，未修改系统服务或重置适配器。测试解码器 4 项通过，主机扫描失败原因仍需独立调查。

- Android 新增 `PodBleDiscovery`：按 PodJS 服务 UUID 过滤的显式扫描，100–10,000 ms、最多 32 个地址去重结果，返回只读快照；取消/平台失败与正常空结果区分，不自动配对/连接。OWW242 发现层/两端控制器/字节流 22 项通过（2.818 秒）；扫描测试使用 fake backend，真实远端发现及权限/选择 UI 尚待完成。

- OWW242 原生 BLE server/广播探针与回归 19 项通过（6.592 秒）：真实广告成功回调、系统服务注册、4 秒接受超时及原生 server 注销，蓝牙开关保持开启。关闭路径补 `clearServices()`；系统保留复用的历史句柄/旧 started 标记，验证采用 Registered 状态，不声称诊断表清空或空中广播消失。测试权限仅在测试 APK，真实远端发现/MTU/CCC/认证传输仍待验收。

- Android SDK 新增 `PodBleAttempt`：选择 central/peripheral 角色后，以一个截止时间覆盖 GATT 建连和认证，超时/取消关闭未移交链路，成功后 Session 独立持有连接。OWW242 BLE attempt/SDK/LAN 20 项通过（23.783 秒），含 MTU 23 认证后状态同步、静默握手超时、取消后迟到链路清理及未配对 peer 拒绝。使用内存分片链路，发现/权限 UI 与真实双设备无线验收仍未完成。

- Android 新增单次 `PodBleGattServer` peripheral：公开 GATT server API、UUID 广播、指定 central 过滤、CCC/write 请求校验、实际 MTU 与逐包 indication 确认；关闭停止本服务广播并释放等待。OWW242 两端控制器/分片/字节流 18 项通过（2.233 秒），含内存接线的 MTU 23 双向 65,539 字节；仍非真实服务注册/广播/无线验收，SDK 认证生命周期与发现/权限 UI 待接。

- Android 新增单次 `PodBleGattClient` central：真实 Android GATT API 接线、服务/特征校验、实际 MTU 回调、CCC indication 订阅及逐包 write-with-response；阶段失败、超时、取消和断连关闭链路。OWW242 central/分片/字节流 12 项测试通过（1.294 秒），central 使用可控 backend 回调，不算真实无线验证。仍缺 peripheral/advertiser、发现/权限 UI、认证流程整合与双设备验收；截止时间不强制中断 Android Binder 调用。

- Android 新增 `PodBleStream`：按实际协商 MTU 将现有认证帧分片，特征值不超过 `min(MTU-3,512)`，有界环形缓冲、序号校验、坏包/溢出关闭及阻塞 IO 取消。OWW242 分片/原字节流 7 项通过；SDK/LAN 16 项通过（22.341 秒），包含内存连接的 MTU 23/247 认证、状态、显式消息 ACK 和 65,539 字节文件逐字节验证。尚未接真实 GATT central/peripheral、权限/发现/回调，不代表 BLE 无线连接或断点续传验收完成。

- Android companion 系统选择器/URI 授权链路已在 Pixel 11 API 36 验证：另一 UID 创建 Downloads 测试文件，示例先直接读取被拒，经真实 DocumentsUI 选择返回后，认证传输 131,079 字节并逐字节一致；清理仅限自建测试文件/副本。手机四类测试共 12 项通过（26.22 秒），不等于物理触控、TalkBack 或新版完整视觉验收。OWW242 当次仅有回环 IPv4，未把此测试扩大为无线双设备验收。

- Android companion 示例已加入系统文件选择入口、16 MiB 常规文档导入、发送/接收分页状态、本机批准、取消/删除确认、完整文件复验及快照清理。OWW242 示例 11 项与 SDK/LAN 15 项测试通过，含 65,539 字节接收、131,079 字节发送逐字节核对、离线批准后重连、取消/超时信号、暂存残留恢复与未知文件/符号链接保留。修复未引用快照无法释放及页面重建丢目标选择；新连接仅查询一次旧批准，不做定时轮询。仍未覆盖系统选择器真实触控/URI 授权、导出、任意云 Provider、手机新版布局与无线双设备；Provider 取消需对方配合，非强制中止第三方代码。

- Android companion 示例已接局域网 connect/listen、30 秒认证截止及 120 秒前台驱动；后台/销毁使旧握手和回调失效并异步断连，回前台不自动重连。状态同步保留编辑草稿，收件列表提供显式 ACK。OWW242 UI 5 项和 SDK/LAN 14 项测试通过，含双向笔记、确认后队列移除、未确认消息跨前后台重连和监听端口释放。测试发现并修复 SDK enqueue/本机确认/远端 ACK 未通知队列观察者的问题。仍属设备内 loopback；文件视图、无线双设备验收、加密/发现和其余平台仍未完成。

- Android companion 示例新增显式带外配对批准与撤销：32 字节随机密钥，双方设备 ID 核对、各自批准，Keystore 封装存储，不隐式替换已有凭据；密钥字段禁止恢复/自动填充，离开前台清除，窗口禁止截图。OWW242 原生 UI 3 项测试通过，覆盖离线功能、取消、批准后重建仍拒绝重复导入、撤销后重新批准、前后台/重建清除秘密。该手动可信渠道流程不是发现、扫码或无线双设备验收，连接/ACK/文件视图仍待接入。

- Android `PodLanAttempt` 新增已配对 peer 的单次连接/监听，绝对截止覆盖 connect/accept/握手，取消解除阻塞，成功后 Session 所有权交给宿主。OWW242 SDK 与 LAN 共 13 项测试通过，含认证后状态传递、关闭 attempt 不影响已交接 Session、取消释放端口和握手超时。仅 loopback，不含 TLS 加密、发现/首次密钥交换、自动重连或无线双设备验收。

- Android 新增 `:companionExample` 原生示例入口：离线笔记、消息入队/目标队列查询、本机身份持久化、Activity 重建恢复，IO 工作不在主线程，页面明确尚未连接。OWW242 与 Pixel 11 API 36 模拟器原生控件测试各通过，手机尺寸截图检查后修正主/次操作颜色。尚非计划完整示例：配对/连接、接收 ACK、文件审批视图及驱动器生命周期接线仍待做，见 [android-companion-example.md](android-companion-example.md)。

- Android SDK 新增 `PodForegroundSync` 显式有界驱动器：100..120,000 ms 截止、独立收发、发送合并/轮转、各通道确认后自动推进、消息始终显式 ACK；停止不删除持久队列，释放快照读取租约。OWW242 SDK 9 项测试通过，含 100 次合并请求、自动三通道/64 KiB 文件、200 ms 截止后工作线程退出且 pending 保留。宿主仍须离开前台时关闭；不属于系统后台授权、不自动续命/重连，UI/发现/配对/无线仍待补。

- Android SDK 三类订阅已统一：状态本地/远端变化、文件意图/进度与未确认消息提供有界合并刷新提示；接收失败也刷新，避免漏掉回复失败前已落盘的数据。`incomingFiles` 可枚举已接受/终态记录，重开 UI 不依赖旧 ID。OWW242 SDK 7 项测试通过，含通道通知隔离、无效写不发成功变更、接受后重开列表与业务回调失败仍发现 pending。提示不是逐条可靠事件或跨进程监听，持久数据仍由 UI 重读；最小 companion UI 与无线接入尚缺。

- Android SDK 已补消息显式交付：`receiveDeferred` 先存 inbox、只推进传输序号不发业务 ACK；`receivedMessages/subscribeMessages/ackMessage` 支持重开后发现、合并通知/取消订阅、在线或离线确认，ACK 校验原始内容和 peer。OWW242 SDK 5 项与原收件箱/消息/共享通道 13 项回归通过，含未确认消息期间状态同步、错误 peer/变更 UI 快照拒绝及离线确认不伪装远端已收 ACK。状态/文件订阅与手机 UI/无线仍待补。

- Android 新增 `:companion` 库与 `PodCompanion` 无 UI SDK 核心：统一三通道存储、显式配对导入、会话/撤销/关闭和文件生命周期，关闭先打断 socket 再释放持久层。AAR 与测试 APK 构建通过；OWW242 3 项 SDK 测试通过，含双向状态、消息 ACK、64 KiB+尾块文件、阻塞读取关闭与离线重开。仍依赖完整 runtime，非独立发行包；订阅/手动交付接口、手机示例 UI、初始配对/无线及其他 SDK 尚缺，见 [android-companion-sdk.md](android-companion-sdk.md)。

- Android 文件真实 SIGKILL 检查点已通过：首块落盘、发送 RPC 仍 pending 时测试进程自杀，不执行 finally/关闭；确认旧 PID 消失后新进程读取原请求/hash/缺块，三次不同 session 最终完成 131,079 字节。OWW242 手动与 `scripts/test-android-file-crash.sh` 各跑一轮通过。仅证明该持久写入后的强杀点，不扩大为任意写中断电或无线双设备验收；普通测试默认跳过自杀探针。

- Android 文件跨会话恢复新增 OWW242 2 项测试通过：131,079 字节经三次不同认证 session，在首块落盘后丢回复、完成后丢回复，关闭全部队列/接收日志/协调器再重开，原请求 ID/字节不变，新会话序号从 1 开始，最终逐字节一致；另覆盖发送侧回复 SQLite 写失败断连且保留 pending。此证据为正常关闭重开，不替代进程强杀或无线双设备验收。

- Android `PodSyncOutgoingFiles` 已连接快照与持久 RPC：等待本机批准→缺块→分块→完成/取消，回复消费/进度/下一请求同一 SQLite 事务；逐块持有快照 Reader 避免重复全盘恢复，源仅在所有关联 peer 终态后显式释放。OWW242 16 项相关测试通过，含 64 KiB+尾块认证 loopback 完整发送、接收方中途取消、完成后取消、重开与下一请求写失败整体回滚。仍缺无线双设备/强杀验收、系统连接调度、授权 UI/guest 路由、profile 配额收紧及其他平台；见 [sync-file-sender.md](sync-file-sender.md)。

- Android `PodSyncFileSnapshots` 已冻结发送源：同一文件描述符流式计算清单、原生分块/整体验证后发布，源修改/删除不影响已完成快照；发送快照与跨 peer 接收共用同一应用 32 MiB/128 项配额。OWW242 快照/授权/接收器 14 项测试通过，含双向配额互斥、重开续读、损坏/符号链接/超限拒绝。仍须发送状态机及持有读取租约的流式读取优化，不代表端到端发送已完成。

- Android 文件 RPC 发送队列 `PodSyncFileRequests` 已落 SQLite：每 peer 单待确认请求，固定 UUID/字节重发，回复绑定设备/ID/hash，先持久化再提交帧；已完成观察可在重开后枚举，消费前保留，重复回复不覆盖首次结果。共享分发器支持请求和回复。OWW242 15 项相关测试通过，含写失败保留 pending、重开恢复、错误摘要/peer 隔离、同一请求在本机批准前后收到不同状态仍保留首次结果。仍缺文件源快照和持续传输状态机，不能视为完整发送端。

- Android `PodSyncFilePump` 已将文件请求接入共享认证连接：offer/status/missing/chunk/finish/cancel 严格字段与 UTF-8 校验，peer 来自连接，不允许远端批准或传入宿主路径；持久操作后回复绑定请求 ID/摘要。OWW242 共享通道与授权日志共 12 项测试通过，含 64 KiB+尾块实际写入、重复操作、状态交错与权限注入拒绝。测试双端改为并发收发以覆盖 socket 背压。仅接收入站请求，持久发送端/回复消费/无线双设备/授权 UI 仍待完成；详见 [sync-file-wire-v1.md](sync-file-wire-v1.md)。

- Android `PodSyncIncomingFiles` 已增加文件 offer/本地接受/取消持久日志：offer 不打开原生接收器，接受意图先落盘再预留空间，取消先记意图再删除；独立应用锁串行协调，恢复先释放取消配额再重试接受。逻辑 app/peer 映射稳定私有存储 ID，跨 peer 不共享接受权限。OWW242 新增 5 项意图/恢复测试，连同文件接收器共 10 项通过，含接受/取消写失败与完整分块重开。尚缺 wire 收发、发送端、接受 UI、事件投递与终态日志回收，不代表文件通道已完整接通。

- Android 文件接收配额已修正为跨 peer 的应用级限制：原生 per-peer 检查外增加应用文件锁，open 恢复和命令共享锁；按全部 peer 统计 32 MiB/128 transfers，未完成保留两份空间，complete 必须校验 hash 才按一份计。OWW242 文件/认证会话 6 项测试通过，含多 peer 绕限拒绝、取消释放、伪 complete 不降低预留与锁竞争失败清理。文件 wire 调度/发送端/接受流程仍待接，不表示无线文件传输已完成；见 [sync-file-storage.md](sync-file-storage.md)。

- Android `PodSyncChannelPump` 已在同一认证连接分发 state/message 和各自 ACK；未知类型关闭且不提交。连接级 receive/apply 临界区覆盖持久写入至序号提交，并拒绝重入；并发调用不能跳过未完成的前帧。OWW242 新增 4 项共享通道测试，连同状态/消息重连和撤销共 10 项通过，含相同 ID ACK 隔离、前帧失败阻止后帧。尚缺文件通道接入、初始配对/无线链路、guest 与手机 SDK，不代表完整三通道或双设备验收。

- Android 认证状态通道已接入 `PodSyncStatePump`：接收状态、游标和批次 ID/摘要同事务，重复内容必须逐字节匹配；写失败断开会话不 ACK，状态 ACK 使用独立 kind 3 并绑定批次。OWW242 状态共 13 项测试通过，包括真实 HMAC/TCP loopback 600 条双向传输、丢 ACK 后重开存储/会话、receipt 插入失败回滚和非法 UTF-8。当前连接只处理 state/ACK，尚缺共享多通道路由、无线配对、guest 服务及其他手机 SDK，不等同双设备验收。线格式见 [sync-state-wire-v1.md](sync-state-wire-v1.md)。

- Android 状态发送批次已持久化：每 peer 固定完整快照周期，最多 512 条/256 KiB 每批，丢 ACK 或重开返回同一批 ID/字节；仅匹配 peer/ID/游标/SHA-256 的 ACK 才推进，周期中的新变更留到下一周期。OWW242 新增发送侧测试覆盖 600 条分批、重开重放、Unicode 字节上限和批次/ACK 写失败；状态发送与存储共 9 项通过。仍缺认证 state/ACK 收发路由、初始配对及双设备无线验收，批次持久化本身不提供认证。

- Android 原生状态存储 `PodSyncStateStore` 已实现：SQLite 事务绑定状态/逻辑时钟/接收游标，确定性冲突合并、删除墓碑、同 revision 不同值拒绝、app/device 隔离、值和总量限额。OWW242 实际 SQLite 5 项测试通过，含注入写失败不推进游标、关闭重开、两个独立句柄并发写入；TypeScript 状态模型 6 项回归通过。尚未接认证状态批次收发、guest 路由与手机 SDK，不代表双设备无线同步完成。边界见 [sync-state-storage.md](sync-state-storage.md)。

- Harmony 后台历史回收已接入协调锁内 cleanup：系统 stop 后再次枚举确认该 workId 已消失，才允许按 workId/runId 删除终态旧记录；保留最新逻辑任务状态、active claim 和未完成 retry，持久 ID 游标不回退。测试覆盖 140 次注册/取消、不匹配身份、活动记录与延迟 OS 删除；88 项 Harmony TS、真实原生执行回归及 DevEco 原生/ArkTS/未签名 HAP 构建通过。最新状态仍占配额（128 条记录上限），不会静默丢弃不同逻辑任务的最后结果；真机调度与冷启动验收仍未完成。

- Harmony 调度协调器已接独立跨进程 `podjs-scheduler` 原生锁，覆盖持久意图与 OS start/stop，异常通过 finally 释放；与 `podjs-execution` 分离，调度句柄没有 guest run 且不能 configure/execute。真实原生测试验证同进程/子进程竞争、独立执行和关闭后重获锁；84 项 Harmony TS 测试及完整原生执行回归通过，DevEco 原生/ArkTS/未签名 HAP 构建通过。历史回收、完整设备调度竞态与冷启动验收仍待完成。

- Harmony 前台维护现在先恢复遗留 active claim：仅取得独立原生执行锁后重新读日志并落盘 `execution_abandoned`，不会准备或执行 guest；有存活锁持有者则保留 claim，继续允许 OS 取消协调。82 项 Harmony TS 通过，真实 NAPI 测试验证持锁时不恢复、释放后恢复且结果写入期间锁仍占用；完整 leased QuickJS 回归及 DevEco ArkTS/原生/未签名 HAP 构建通过。跨进程 OS 调度副作用互斥、历史回收及真机验收仍待完成。

- Harmony 前台后台任务维护已接 EntryAbility：前台立即 reconcile 并每 5 秒重试，后台/销毁停表，初始化中或协调器排队中的旧 foreground 请求失效；不直接执行 guest handler。进程内 native 调度工厂共享同一个协调器，拒绝 filesDir 不一致，纯读取初始化失败可重试。新增 3 项恢复测试，全体 Harmony TS 80 项通过；DevEco ArkTS/原生/未签名 HAP 构建通过。跨进程 OS 副作用互斥、历史回收、遗留 active claim 的前台恢复和真机验收仍未完成。

- Harmony guest 后台服务已接到页面授权门后的 `BackgroundServices`：register/cancel/status 使用安装包批准和持久协调器，补齐可选 network/payload 默认值，仅返回公共状态，不接收 guest source/hash/grants；周期任务明确 unsupported。异步批准期间取消请求可阻止新注册落盘。新增 3 项路由测试，77 项 Harmony TS 全通过，真实 DevEco ArkTS/原生/未签名 HAP 构建通过。前台恢复驱动、协调器跨实例并发/历史回收及设备冷唤醒仍待完善，`background.scheduled` 尚未开放。

- Harmony 已注册非导出的 `PodBackgroundAbility`（workScheduler 扩展），按本机 SDK 的 `onWorkStart/onWorkStop` 签名接入生命周期门控、独立 native 执行和结果后持久协调；拒绝无效 workId/runId/version/扩展名，失败不直接移除系统任务。真实 DevEco ArkTS/原生/HAP 构建通过，74 项 Harmony TS 回归通过。仍缺 guest 调度服务接入、历史回收及真机冷唤醒验证，能力不提前开放。

- Harmony 后台生命周期门控已增加源码与 4 项测试：初始化等待期间的 stop 阻止迟到执行，同 generation 回调合并，冻结回调身份避免调用方修改，销毁取消活动任务并阻止迟到启动，工厂失败可重试。该门控尚未接到实际 WorkSchedulerExtensionAbility，不表示系统冷唤醒链路已完成。

- Harmony 持久重试与 OS 协调器源码已连接：retry 用新 workId/runId，指数增加最早运行延迟（30 秒起、一天封顶，仍受系统调度限制）；同父任务只建立一个重试，取消或新注册可抑制旧重试。协调器先处理系统撤销，再持久创建重试和提交 pending 任务，响应丢失后可重新核对。新增 6 项测试，全体 Harmony TS 70 项、真实 CAS/leased QuickJS 回归通过；显式导入探针 DevEco 编译与 HAP 通过，探针已移除。后台扩展入口、guest 服务路由、历史回收及真机验收仍待完成，不声明系统冷唤醒已通过。

- Harmony 后台执行驱动已连通持久认领与 leased QuickJS：取得锁后恢复遗留 active claim，重新校验当前安装包 hash/grants，在同一锁下配置获准代码，结果提交后才关闭。原生 `backgroundConfigure` 仅允许未执行、未取消的 leased 句柄，不能重置已取消任务。5 项驱动测试、全体 Harmony TS 64 项通过；真实 Node/NAPI/Rust 往返验证获准代码写入 Unicode KV、持久 success、结果提交期间锁仍占用和撤权后不执行。显式导入探针 DevEco 编译/HAP 通过，探针已移除。尚缺后台扩展入口、OS 调度协调、重试/回收、guest 路由与真机冷启动验收。

- Harmony 原生后台执行新增 `backgroundOpenLeased(config, filesDir)`：异步获取独立跨进程锁后才返回句柄，可在认领前确认旧执行已退出；句柄关闭先取消，worker 持有对象直到真实执行返回，锁还可覆盖结果落盘。实际 Node/Rust 测试覆盖同进程/跨进程互斥、运行中关闭仍持锁、环境清理和子进程被终止后内核释放锁；旧存储/CAS/状态机回归与 ASan/UBSan 契约测试通过，DevEco 原生链接和 HAP 构建通过。尚未把 leased 路径与任务认领、后台扩展和系统调度连成完整执行链。

- Harmony 后台持久状态机已接原生 CAS 接口：版本/revision/app 绑定、持久递增 workId、最多 128 条记录、64 KiB UTF-8 payload、取消/替换隔离、单任务认领及 claimId 结果匹配。执行中记录不按时钟自动释放；取消必须等实际执行返回或宿主证明已退出。6 项状态机测试、全体 Harmony TS 59 项通过；额外用真实 NAPI 磁盘后端运行 5 进程，40 个任务 ID 唯一且持久保留，竞争认领仅一个成功。显式导入探针 DevEco 编译与 HAP 构建通过，探针已移除。原生执行租约、后台扩展、重试/回收及 guest→OS 调度协调尚未接通。

- Harmony 后台存储增加独立 namespace 的跨进程 CAS NAPI：每次异步操作仅在读→条件写期间持有文件锁，旧快照返回 false；临时文件 fsync→rename→目录 fsync，提交后失败仍须重读。最多 16 个排队操作，锁竞争返回 `busy`，其他加锁错误不伪装成竞争。Linux 5 进程并发 100 次更新全部保留，Unicode/旧写拒绝/危险文件拒绝及原通知存储回归通过，DevEco 原生链接与 HAP 构建通过。尚须任务状态模型、持久 ID 分配、执行认领和系统调度协调；CAS 本身不表示任务已运行。

- Harmony 系统 Work Scheduler 适配器源码已完成：使用 API 22 的相对 `earliestStartTime`、持久一次性任务、可选任意网络约束；缺少该 API 时明确拒绝。按 bundle/扩展/workId/随机 runId 校验归属，重复申请可认领同一已接受任务，拒绝修改同 generation 的参数及取消其他任务。5 项契约测试（含接受后响应丢失）、全体 Harmony TS 53 项与显式导入探针 DevEco 构建通过；探针已移除。尚未注册后台扩展或连接持久任务协调器、guest 路由，不代表已向设备提交系统任务。

- Harmony 非空后台 HAP 已实测：构建一项实际 handler，解包逐字节核对源文件及清单中的路径/长度/hash，非法路径、错误 hash 和长度均在复制前拒绝。测试发现并修复 PowerShell 混合布尔运算优先级导致路径检查失效；探针结束恢复原清单、移除自身临时文件并重建原 HAP，两次构建通过。可复跑脚本 `scripts/test-harmony-background-package.ps1`；不代表设备执行或系统调度已通过。

- Harmony 已增加 OS 安装包后台 handler 校验：从当前 bundle 身份与 rawfile 读取，限制 manifest/handler 数量、路径、大小和方法子集，核对 SHA-256 与严格 UTF-8；重新加载当前包可拒绝旧 hash/已撤销 grants。5 项新测试，全体 Harmony TS 48 项通过；显式导入探针发现并修复 ArkTS 构造参数属性限制后，DevEco 编译与 HAP 打包通过，探针随后移除。构建脚本补充 manifest 声明后台文件的校验和复制；当前仅空后台清单完成打包验证，非空清单产物检查仍待完成。系统调度、持久记录、可信执行路由与真机冷启动仍未接通。

- Harmony 已接入 host-only 独立后台 NAPI：异步 worker 执行真实 QuickJS，`open/execute/cancel/close` 与页面及帧锁独立；关闭先取消、执行返回后释放，句柄按环境隔离，进程最多 16 个存活 run（包括关闭后仍执行的任务），环境销毁清理未关闭句柄。Linux Node NAPI 实际链接 Rust 测试覆盖 Unicode、一次性执行、超时、运行中关闭、配额及环境隔离/清理；DevEco 原生链接、ArkTS 与未签名 HAP 构建通过。系统 Work Scheduler、可信包批准、持久任务记录及真机冷唤醒尚未接入，不启用 Harmony `background.scheduled`。

- Harmony 通知 ACK 在异步获取 journal 后再次检查授权，等待期间撤权不再确认事件；存储获取失败后可重试，并合并同 ID 在途 ACK。新增 2 项回归测试，全体 Harmony TS 43 项及 DevEco ArkTS/未签名 HAP 构建通过。设备复查仍无 Harmony target，Apple 构建机仍不可达；不替代真机验收。

- Harmony journal v2 增加持久 ID 游标与安全回收：仅清除已结束、打开事件已 ACK 且系统中不再存在的记录；未确认或在途记录保留。旧 v1 按保留记录迁移，新版缺少游标拒绝加载；分配结果写入不确定时宁可留空号，不复用旧 ID。4 项新回归测试、全体 Harmony TS 41 项及 DevEco 构建通过。未点击/未确认历史仍受容量上限保护；自定义操作、远程推送及真机通知往返尚未完成。

- Harmony 通知前台投递及 ACK effect 路由已接入源码：Ability 与页面可见性同时成立且能力获授权才启用一秒重试，后台/隐藏/销毁清除计时器；原生 `steady_clock` 提供单调毫秒。`ServicePump` 将 `notification.ack` 送入持久 ACK 控制器，未知或未交付的 ID 不提前确认。37 项 Harmony TS 测试与实际 DevEco 构建通过；当前能力仍关闭，自定义操作、记录回收、远程推送及真机完整往返尚未完成。

- Harmony 通知投递控制器已实现：前台且授权时才读事件，入原生队列不等同 ACK，失败保留、每秒限流和每批 16 条；未确认的前批不会饿死后续事件。页面隐藏使在途读结果失效，只接受本次控制器已交付事件的 ACK。5 项测试与类型检查通过；页面生命周期驱动、单调时钟和 ACK effect 分发尚未接入，当前仍非系统点击→guest 完整闭环。

- Harmony `EntryAbility.onCreate/onNewWant` 已接入通知打开捕获，格式无效的 Want 不打开存储，合法 token 仍须匹配 journal 原记录。并发捕获去重，首次持久写失败保留最多 64 个内存候选并在前台重试；首次落盘前进程死亡仍可能丢失该次点击。2 项接收器测试、全体 Harmony TS 测试 31 项及 DevEco 构建通过；前台事件投递与 ACK effect 路由仍待接入。

- Harmony journal 已增加通知打开事件的持久状态与 ACK：按私有 token＋逻辑 ID 查找原记录，事件 payload 来自对应 generation；旧提醒不会读取新 payload，ACK 前可重读，ACK 后重复捕获不再投递。新增重启/旧 generation/写失败测试，journal 共 7 项通过。系统 Want 捕获、前台事件投递和 ACK effect 路由仍待接入，尚非点击冷启动闭环。

- Harmony 通知 `schedule/cancel/listPending` 服务路由已接通共享 journal 协调器和系统 reminder 适配器；host 分配系统 ID 与加密随机 token，同 ID 的重复提交去重，更新取消旧 generation 后发布新记录并保留旧 payload。5 项路由/替换测试与类型检查、DevEco 构建通过。更新途中失败不保证保留旧提醒，错误显式返回；点击事件箱、自定义操作、记录回收及真机通知往返仍待完成，不启用 `notification.local`。

- Harmony journal 已有真实私有磁盘后端及异步 NAPI：进程独占锁、`O_NOFOLLOW`/文件归属检查、UTF-8 与 16 MiB 限制、临时文件 fsync→rename→目录 fsync；写入拒绝可能发生于 rename 后，协调器仍须重新读取。ArkTS 提供页面重建间共享的惰性单实例协调器。Linux 真实文件测试（含 ASan/UBSan）与 Node NAPI 工作线程/并发写入/跨进程锁测试通过，DevEco 编译打包通过；Harmony 设备断电/强杀恢复与 guest 调度接入尚未验证。

- Harmony reminder 新增写前 journal 协调器：持久意图成功后才发布/取消，返回 ID 丢失时按 token＋notificationId 认领系统记录；可能已经触发的未知结果保留 `uncertain`，不自动重发。取消恢复幂等，结束记录保留 payload，损坏日志 fail closed；5 项故障注入测试通过。原子磁盘写入、单实例所有权及 guest 路由进度见上；记录回收和真机持久通知闭环仍未完成。

- Harmony 已增加系统 reminder 调度适配器源码：使用 `publishReminder/getAllValidReminders/cancelReminder`，按原生 ID＋notificationId＋私有 token 校验记录归属；系统倒计时向上取整，不用错误的 `deliveryTime` 或常驻 JS timer。应用自定义操作按钮暂显式 `unsupported`，不会映射成关闭/稍后提醒。3 项参数/时间契约测试及 DevEco 编译打包通过；仍须接入持久 journal、重启恢复、guest 调度分发和点击事件箱，此适配器尚未作为可用通知能力开放。

- Harmony 通知权限 handler 已接入原生授权门之后，调用实际 `notificationManager.isNotificationEnabled/requestEnableNotification(UIAbilityContext)`，先检查系统能力；并发申请共用对话框，取消阻止迟到结果及尚未发起的弹窗。OS 布尔禁用状态映射 `denied`，不推测 `notDetermined`；明确拒绝、系统忙和内部错误分别处理。新增 5 项权限测试，连同分发/授权共 14 项通过；DevEco ArkTS 与 HAP 打包通过。通知调度/事件箱/推送及真机授权交互未完成，`notification.local` 仍不启用。

- Harmony 服务新增原生授权门：`hasCapability` 与 runtime 配置复用同一列表，成功校验 manifest 并启动前一律拒绝；ArkTS `AuthorizedServices` 对计划中的每个方法核对其精确 capability 后才进入 handler，忽略请求 args 中伪造的授权。未知方法、取消及复用 ID 的迟到结果均 fail closed。新增授权测试与分发测试共 9 项通过，原生契约 ASan/UBSan 与实际 DevEco 原生链接、ArkTS 编译、HAP 打包通过；仍未开启未实现的服务能力。

- Harmony 前台 effect 分发已接入页面：原生帧以单个合并 TSFN 唤醒 ArkTS，有 effect 或回传队列释放容量时才通知，不增加后台 timer；隐藏/注销/环境销毁不调用页面，锁外进入 ArkTS。`ServicePump` 每批最多 64 条、支持取消、积压重试和迟到结果隔离；未接入方法返回 `unsupported`。6 项分发测试及原生契约 ASan/UBSan 通过，真实 DevEco 原生链接、ArkTS 编译和打包通过。当前没有开启新的服务能力；具体同步/通知/后台 handler 与设备端 QuickJS 往返仍待验证。

- Harmony 已增加 host-only `pollEffect/postEvent` NAPI 桥：锁内复制原生 effect，UTF-8 JSON 对象回传只入队、不驱动帧；单条 1 MiB、成功帧之间最多 256 条/4 MiB，拒绝 NUL 和积压超限，失败不得视为交付。C++ 契约测试及 ASan/UBSan 通过，NAPI 的实际 DevEco 编译链接和打包通过。ArkTS 请求分发进度见上；具体同步/通知/后台服务和设备端真实请求往返仍待完成，不因此声明对应能力。

- Harmony 启动预检已修复 ABI 升级遗漏：页面请求 ABI 2，原生接受受支持的 ABI 1..2，并拒绝小数、非有限数、溢出绕回和链接库版本不一致。此前页面固定 ABI 1 加原生严格等于 ABI 2 会在启动前拒绝。新增 C++ 契约测试及 ASan/UBSan 通过，修复后 DevEco 原生链接、ArkTS 编译和 HAP 打包再次通过；这不替代设备端启动验收。

- Windows DevEco 实际 SDK 的完整构建已通过：在独立目录重建最新 Rust ARM64 archive，provider、完整 NAPI 桥、GLES 原生链接、ArkTS 编译及未签名 HAP 打包成功，未覆盖旧项目。存在 deprecated-context/potential-exception 编译警告；`hdc list targets` 当前返回 `[Empty]`，读屏真机验收仍待完成。Apple 构建机旧地址当前不可达。

- Harmony XComponent provider 已有节点/子树/文本查询、文档序焦点、点击/调节回传及显隐失效处理源码；缓存与 runtime 锁分离，解锁后发送事件。使用上游原生头文件的 C++ 回调契约测试及 ASan/UBSan 通过，实际 Harmony SDK 原生链接与打包亦通过；读屏真机验收、角色/状态朗读映射验证仍待完成。

- Harmony 无障碍 NAPI 数据接口（启用、已提交快照、动作回传）与 XComponent 帧回调共用互斥锁，64 位 hash 使用精确十六进制字符串；已接入原生 accessibility provider 并通过 Harmony SDK 编译链接。系统读屏交互尚未验证，不声明可用能力。

- watchOS 新增语义解码/Combine 发布及 SwiftUI 无障碍投影源码：独立于 paint hash 更新，按稳定 ID 和文档序创建元素，使用 aspect-fit 边界，点击/调节回传 C ABI。新增解码、坐标和禁用动作 XCTest 源码；本机无 Swift/Xcode，尚未编译/运行，需重建 Apple runtime artifact 后验证，不能视为 watchOS 无障碍已可用。

- OWW242 新增真实 provider → JNI → C ABI → QuickJS 动作往返测试：入队不提前改变快照，下一帧更新 Unicode 标签并禁用控件，后续点击被拒绝；与合成语义树测试共 2 项通过。测试包校验真实 revision/capabilities/hash，没有绕过启动校验；使用小型 guest 且脱离显示呈现，仍不替代 SDK 页面上的 TalkBack 交互验收。

- 无障碍属性、Rust 主画面语义快照及动作回传已接通：draw 提交后按文档树序生成语义节点，复用绘制裁剪/缩放/透视边界；C ABI 按语义 hash 导出 JSON，动作校验已提交快照和当前节点后入队，SDK 精确派发控件回调并再次检查禁用/隐藏/模态范围。Android 已接入 AccessibilityNodeProvider、虚拟节点焦点/触摸探索/点击/增减与 JNI，OWW242 合成语义树 instrumentation 测试通过（Unicode、缩放/嵌套坐标、状态、hash、焦点保留/删除）；两种 ARM JNI 链接通过。7 项核心无障碍测试、2 项 runtime 边界测试、52 项 renderer 测试通过。尚缺真实读屏完整交互及 watchOS/Harmony 桥，不启用 accessibility.basic。详见 accessibility.md。

- Android/Wear 已启用 `notification.local`，原生启动能力列表与 target profile 一致性测试通过。OWW242 六项通知测试通过，包括实际 PendingIntent → Activity → View → SDK onOpen/onAction → 异步 permission 服务 → KV 保存 payload/actionId → 收件箱 ACK；不是物理点击或进程冷启动验收。
- 本地通知具备 permission/status、权限请求协调、即时/WorkManager 尽力定时发送、listPending、取消、最多 3 个操作按钮、随机 token 与持久事件去重。新调度先持久化系统任务、再事务提交记录、最后撤销旧任务，关闭存储→enqueue 丢失窗口；七项通知测试含中断点验证旧任务保留/孤儿不发布。OWW242 已验证进程不存在后 SystemJobService 启动新进程并发布通知，独立测试确认正文和 pending 清理。API 36 模拟器三组真实权限弹窗测试通过（允许/拒绝/并发与取消/关闭不决策/固定拒绝），修复关闭误报 denied；其他 API 33+ 版本、OEM 权限 UI、设备重启与物理点击仍未验收；不修复旧版本遗留孤儿记录，不保证 exactly-once 展示。远程通知与非 Android 适配仍不宣称可用。详见 notifications.md。
- OWW242 已通过真实 APK 升级撤权测试：v1 带 KV grants 调度，升级到同源码哈希但空 grants 的 v2，系统自然执行旧 run 被 background_permission_denied 拒绝；只读探针确认 SQLite failed 与 WorkManager FAILED。此前“应用升级撤权待测”由本项更新；不涵盖运行中升级中断。
- WorkManager KV 服务已贯通：项目 backgroundServices 显式声明四种 KV 方法子集，manifest 授权快照写入任务 SQLite；每次执行校验当前 APK grants/源码哈希及应用包名，固定 filesDir/podjs，不接受 guest root。OWW242 实际 UI guest 前台写→后台读写→持久 Unicode 结果通过；42 项 TS、38 项 Rust 与类型检查通过。应用升级撤权专测尚未完成。
- 后台原生 KV 异步适配已实现：host-only 绝对 root、方法子集授权、共享前台存储、可取消/有截止时间的 Unix 锁等待，提交前再次检查取消。38 项 Rust 与 OWW242 13 项后台测试通过，包含前后台互通、锁超时不写、独立 native run 持久互读。WorkManager 的持久 grants/root 自动装配仍待接通；不声称能抢占磁盘系统调用。
- 为后台共享数据准备，前台 KV 已改为独立公共存储模块：同一 JSON 格式、操作前重读、文件锁与 fsync+原子替换，避免不同句柄覆盖缓存；损坏文件写入失败且保留原内容。36 项 Rust 测试通过，OWW242 原生诊断验证 4 线程/40 次写入及跨句柄读改删。Android 标准库 lock 不支持已由实测发现并改为 Unix flock。后台服务授权/调用仍待接入。
- 后台请求 JNI/Java 桥已接通：host-only 方法列表、UTF-8 poll/reply、同步句柄锁、关闭后拒绝回包。ARMv7/ARM64 重建及 OWW242 12 项后台测试通过，包含汉字/补充 Unicode 往返和待处理请求关闭。WorkManager 仍使用空方法列表；持久授权与实际 IO 分发待接。
- 后台异步请求已接 C ABI：allowed_methods 显式 opt-in、poll/reply 与 execute 并发、回包一次性、取消/结束清队列。35 项 Rust 测试通过，包括实际跨线程请求/回包、非法与重复回包拒绝。JNI 与平台 IO 消费尚未接通。
- Rust 后台执行增加 opt-in 异步宿主请求：host 方法白名单、固定 app/task、deadline/取消信号、16 并发/32 总请求及请求/响应各 64 KiB 限额；所有退出取消未完成 IO。34 项 Rust 测试通过，覆盖异步延迟、越权、重入、响应限制、超时及未 await 清理。C/JNI 与具体数据/网络适配尚未连接，不宣称 Android handler 已可用这些服务。
- Android/Wear OS 共用宿主已启用单次 `background.scheduled`，profile 与 JNI capability 顺序测试通过。OWW242 实际 View→QuickJS guest 框架 register→宿主→WorkManager→独立 handler→持久 success 验收通过，并检查新 run 与 guest payload，未使用测试代码代调度。此前“未启用 capability/UI guest 未验收”由本项更新；Wear OS 专机、其他平台、后台异步授权服务仍未验收/接通。
- OWW242 单次系统冷唤醒已验收：调度进程退出且 pidof 为空，JobScheduler 保留延迟任务，系统自然启动新 PID 完成同一 run，随后只读测试确认 SQLite completed/success 与 WorkManager SUCCEEDED。未强制执行 job；不涵盖重启/force-stop/Doze，也不替代 UI guest 接入验收。可复跑探针见 background-bundles.md。
- 实际独立 APK `dev.podjs.backgroundtest` 验收通过（OWW242）：CLI 构建内置 handler → APK 资产批准 → 异步 register 服务 → WorkManager 无 Activity 执行 → 持久 success；另验证 target 不匹配拒绝。fixture 和运行命令见 background-bundles.md；不包含 UI guest 或进程死亡后的系统唤醒。
- APK 内置后台包已挂接 `PodRuntimeView` 原生校验成功后的 IO 队列：读取有界 manifest、匹配 target、以 Android 包名绑定身份、失败关闭路由；信任限于 OS 安装的 APK，不接受下载包。尚未验收生产 guest→系统冷唤醒全链，也尚未启用 background.scheduled capability。此前进度中“生产安装未接通”已由本项部分推进，非 APK 安装路径仍未实现。
- Android 后台服务路由已增加宿主绑定应用的 register/cancel/status，任务 ID 与 handler ID 分离，状态按 app/task 持久查询，retry 映射 scheduled；拒绝未支持的周期请求。OWW242 回归验证服务重开、跨应用读取/取消隔离。`PodServices` 默认拒绝未注入授权路由的后台请求；生产 View 的可信安装与能力启用尚未接通。
- Android `PodBackgroundPackage` 已增加宿主批准 manifest 的不可变快照与 handler 解析：固定应用身份、仅允许构建器路径、校验精确字节数/UTF-8/SHA-256 后调度。包认证、guest 服务授权与生产安装挂接仍未接通，此 helper 不构成可信安装器。
- 后台任务 payload 已贯通 Rust/C/JNI 与 Android WorkManager：调度前序列化独立 JSON 快照、SQLite 持久保存、worker 重开后传入 `context.payload`；默认 null，最多 64 KiB UTF-8，计入应用存储配额。OWW242 七项后台测试通过（含 payload 修改隔离、Unicode、重开与超限拒绝），Rust 全部 30 项测试通过；不代表系统冷启动验收。
- 已实现七项 capability 标识；尚未把未接通的能力加入任何 target 的支持列表。
- 已实现 `@podjs/framework/platform-services`：状态/消息/文件同步、通知与后台调度的类型化客户端，复用现有服务请求、错误与取消机制。
- 已实现 `@podjs/framework/sync-state`：传输无关的双端状态合并、逻辑时钟、删除墓碑、原子持久化接口和按 peer 接收游标；仅在存储提交成功后确认接收。
- 已加入并运行状态冲突、失败后重放、重启、序号缺口、删除保护及未实现能力拒绝测试；默认测试命令已包含新增测试。
- 已增加项目 `background` handler→入口映射、纯类型/handler helper 与独立 IIFE 构建；manifest 记录每项文件、SHA-256 和字节数，不复用 UI bootstrap。构建和项目配置九项测试通过，默认测试现在覆盖这些测试。实际生成的 617-byte bundle 在本机和 OWW242 QuickJS diagnostic runner 校验哈希后执行成功。用法见 [background-bundles.md](background-bundles.md)；guest-visible 安装/授权/调度路由仍待接通，构建产物不自动宣称调度 capability 可用。
- 已实现独立 Rust `background` QuickJS 执行边界：不构造 UI surface/renderer/frame loop，单应用单实例，1..30,000 ms 截止预算、4..32 MiB heap 限额和跨线程取消；支持 handler 同步/Promise 返回 success/retry/failure，未决且无可运行工作的 Promise 明确失败。禁用 eval、动态函数构造、Atomics/SharedArrayBuffer，不挂载任意文件/网络/UI 服务。核心六项新增测试及当时全部 Rust 28 项测试在本机和 OWW242 ARMv7 实际执行通过。
- 后台执行已增加 one-shot C API `pod_background_open/execute/cancel/close` 与 Android `PodBackgroundRun` JNI；关闭时先取消，执行返回后才释放句柄。Android host-only `PodBackgroundScheduler/Worker` 接入 [WorkManager 2.11.2](https://developer.android.com/jetpack/androidx/releases/work)，使用唯一一次性任务、最早时间、网络约束、指数退避和 SQLite 持久结果；独立校验 source SHA-256，保存最多 128 条/16 MiB 每应用任务历史，替换/取消状态不能被旧 worker 覆盖。OWW242 六项后台测试通过：无 UI 执行、截止时间、并发关闭、实际 WorkManager 执行及结果重开、替换/取消、retry 状态。尚未验收系统冷唤醒或实际退避后重跑；周期任务、产物安装路由、manifest 授权服务与其他平台调度仍待实现，不对应用宣称 background.scheduled 可用。
- 加载预检已修正非字符串 capability 被忽略的问题；每次重新校验先撤销旧的通过状态，空/损坏/不匹配 manifest 不能沿用上次授权启动。新增回归覆盖这些拒绝路径及有效 manifest 重新恢复启动；Rust 22 项测试通过。
- 仍待实现：完整无线双设备传输、多通道集成、手机 SDK、原生通知/后台 handler、语义树及原生读屏桥接、四端设备验收。当前客户端 API 不代表这些原生能力已经可用。
- runtime/header/framework 默认 ABI 已升级至 2，保留 ABI 1 struct 布局和应用启动兼容。runtime 接受 host config 1..2，应用 ABI 只能在 1..hostAbi 范围；guest 暴露应用协商 ABI，receipt 分别记录 hostAbi/packageAbi/runtimeAbi。ABI 2 host 可启动 ABI 1 应用，但 ABI 1 host 拒绝 ABI 2 应用，兼容模式不跳过能力/哈希/target 预检。本机 32 项 TS 与 21 项 Rust 测试通过，OWW242 ARMv7 直接执行全部 21 项 Rust 测试通过（含四 target 标识的 ABI 矩阵）；这不等同 watchOS/HarmonyOS 宿主设备验收。Android ARMv7/ARM64 原生库与 AAR 已重建。
- 已实现独立链路共用的长度前缀分帧、BLE MTU 分片及流式重组；分帧本身不提供身份认证。
- 已实现传输无关的消息 outbox/inbox：原子存储接口、接收方 ACK 删除、重启重发、TTL、优先级、UTF-8 容量限制及已处理回执去重。接收端只有持久化应用确认后才允许发 applied ACK；业务副作用仍须用 messageId 幂等。认证链路与各端原生持久化适配仍待接通。
- 已实现 Rust 原生文件接收器：64 KiB 分块、SHA-256 校验、真实磁盘重启恢复、缺块请求、最终校验与原子发布、取消释放。接收时预留双份数据空间覆盖组装，完成后清理分块；启动清理已知临时文件并重新验证完成文件。
- 文件接收器提供 C API `pod_sync_files_open/command/close`，独立 handle 支持 host IO worker，无需 UI runtime。Android JNI 已接通，其余平台及多通道生产路由仍待实现；整体同步能力仍不对应用宣称可用。
- 已实现 Rust HMAC 会话原语：应用/双方身份/双 nonce 绑定、双向握手证明、帧认证、通道授权、序号检查和应用提交后 ACK。测试覆盖篡改、反射、错误密钥、旧挑战与序号缺口。Android host 密钥存储及随机 nonce 已实现；初始配对、加密传输、持久会话恢复协商及其余 host 集成尚未完成；HMAC 本身不加密内容。
- Android 已接入 host-only `PodSyncFileReceiver` JNI：应用/peer 私有目录、文件锁防重复打开、命令与关闭互斥、大小限制和 UTF-8 响应。ARMv7/ARM64 原生构建通过；OWW242 Android 11 真机两项测试通过，覆盖非空分块校验、关闭重开后缺块恢复、最终字节一致、空文件、重复打开拒绝与关闭后调用拒绝。尚未连接认证传输或应用服务路由，不代表双端同步验收完成。
- 认证帧现显式输出 `protocolVersion/sessionId/channel/messageId/sequence/payload`；sessionId 由双方身份和随机 challenge 的绑定摘要确定，版本与 sessionId 同时纳入帧认证。新增测试验证线格式、未知版本拒绝和旧会话帧不能进入重连会话；会话原语四项测试通过。链路适配与持久恢复协商仍待实现。
- 已增加 host-only `pod_sync_session_open/command/close` 和 Android `PodSyncSession` JNI：固定通道授权、握手前收发拒绝、内部发送序号、认证失败终止会话、持久提交后 ACK；Android 提供 SecureRandom challenge。ARMv7/ARM64 构建通过，OWW242 文件/会话测试验证握手→认证文件 offer→真实磁盘写入→ACK→重复/篡改拒绝。该测试不经过无线链路，不代表双设备验收；真实配对、无线传输适配与生产服务路由仍待接通。

消息持久化进度：Android `PodSyncOutbox/PodSyncInbox` 使用 app 私有 SQLite 事务保存已编码 payload 和 applied 回执，支持有界批次、优先级、TTL、相同 ID 内容一致性检查、接收方作用域和内容摘要绑定 ACK。256 KiB 单条、1,000 条及 8 MiB（含保守信封开销）限制生效，不驱逐未确认消息或未到期回执。`PodSyncMessagePump` 接通认证连接、持久队列、幂等业务回调、提交和签名 ACK，支持新会话重发相同消息。OWW242 八项队列/消息测试通过，包括完整 256 KiB 数据、丢弃 ACK 后重开存储/重连不重复业务、业务写失败保留 pending、旧 ACK 不能误删复用 ID 新内容。未做进程强杀或双设备无线验收。线格式见 [sync-message-wire-v1.md](sync-message-wire-v1.md)。

连接撤销进度：Android `PodSyncConnections` 统一管理待握手/已认证连接，从 Keystore 配对存储建立会话，提供认证收发及显式提交接口。撤销先关闭连接再删除凭据，握手中途被撤销不能重新挂入会话。OWW242 两项测试通过：已存凭据建立 TCP 会话、双向认证消息及提交后撤销；对方尚未返回 hello 时撤销解除握手阻塞。已关闭连接不占名额；持久 session 游标恢复及多通道路由仍待实现。

配对凭据进度：Android `PodSyncPairingStore` 使用独立 Keystore AES-GCM wrapping key、no-backup 私有目录及 app/双方身份 AAD；提供已授权密钥导入、读取和撤销，禁止隐式替换，文件锁避免并发写入。OWW242 密文篡改、重开读取、拒绝重复导入、撤销后重建测试通过。`establishPaired` 可从存储载入密钥发起握手，临时字节数组随后清零；初始配对 UI 及跨设备流程仍待实现，不声称进程内所有密钥副本均已清除。

握手集成进度：Android `PodSyncHandshake` 已在已连接流上交换新 challenge、核对已配对 app/设备身份及角色、按顺序完成双向 HMAC 证明；错误身份或密钥关闭连接，不采用首次连接自动信任。调用方仍须提供已认证配对密钥、授权通道和连接超时/取消。这不包含初始配对 UI 或密钥存储。

补充传输进度：Android `PodSyncStream` 已支持已连接 TCP/RFCOMM socket 的统一大端长度前缀，限制 2 MiB、分段读取、整帧写互斥、半帧/非法长度断连及关闭解除阻塞。OWW242 上流/会话/文件五项测试通过；会话测试随后升级为真实 loopback TCP 交换握手证明和认证文件 offer，并单独重跑通过。RFCOMM 构造适配已编译但未做蓝牙连接测试，loopback 不等同局域网双设备验收；该层不加密、不负责发现或配对。

## Summary

在 `podjs-watch` 中新增三组跨平台能力：

1. 手机—手表完整双向同步：独立的状态、消息、文件通道。
2. 本地/远程通知与系统调度后台任务。
3. 基础可朗读无障碍：标签、角色、值、状态与操作。

覆盖 Android Watch、Wear OS、watchOS、HarmonyOS；同时交付 Android、iOS、HarmonyOS 手机端 SDK 和最小 companion 示例。

## Key Changes

### 1. 公共契约与能力声明

- 扩展 target capability：
  - `companion.sync.state`
  - `companion.sync.message`
  - `companion.sync.file`
  - `notification.local`
  - `notification.remote`
  - `background.scheduled`
  - `accessibility.basic`
- 所有能力继续走现有 manifest 预检、host ABI 和 `service.request/result` 通道；缺失能力必须在调用前明确返回 `unsupported`。
- 升级 host ABI，并保留旧 ABI 应用启动兼容；新应用声明能力后不得在不支持的平台静默降级。
- 为后台 handler 增加独立 bundle 入口。它不创建 UI/runtime renderer，只能使用 manifest 授权的同步、网络、存储、密码学和通知能力。

### 2. 手机—手表同步

建立通用协议，不直接绑定 WatchRSS v14，但复用其已验证的游标、清单、分块、ACK、断线续传和链路升级模型。

- 会话层：
  - 版本协商、设备/app 身份、随机 challenge、HMAC 握手。
  - 单调发送序号、累计 ACK、会话恢复游标。
  - 帧必须有 `protocolVersion/sessionId/channel/messageId/sequence/payload`。
  - 重复帧幂等；未知版本、越权 channel、校验失败均 fail closed。
- 状态通道：
  - API：`syncState.get/set/delete/subscribe/synchronize`。
  - 每项包含 key、value、逻辑版本、deviceId 和 tombstone。
  - 使用 `(logicalCounter, deviceId)` 确定性合并；同一 key 后写胜出，删除墓碑参与同样排序。
  - 同步游标只在双方确认应用成功后推进；失败重试不能丢变更。
- 消息通道：
  - API：`syncMessages.send/subscribe/ack`。
  - 至少一次投递，以 messageId 去重；支持 TTL、优先级和离线队列。
  - 默认单条上限 256 KiB；队列上限 1,000 条或 8 MiB，超限拒绝新普通优先级消息，不删除未确认消息。
- 文件通道：
  - API：`syncFiles.offer/accept/cancel/status/subscribe`，以及显式交付完整接收文件到应用目录的 `syncFiles.save(transferId, path)`。
  - manifest 先交换文件大小、SHA-256、MIME 和分块哈希；默认 64 KiB 分块。
  - 支持缺块请求、逐块校验、断点续传、最终整文件校验和原子改名。
  - 默认单文件 16 MiB、每应用 32 MiB；target profile 可以收紧但不能扩大应用声明的配额。
- 传输适配：
  - 独立协议是必需能力：无需厂商运动健康 App、厂商账号或厂商云服务。RFCOMM、BLE 和局域网共享认证、消息/状态/文件通道、ACK 和恢复游标。
  - BLE 必须能独立完成数据传输，不限于发现；按协商 MTU 分片。RFCOMM 和局域网使用同一长度前缀帧。认证后可切换链路，切换不得丢失已确认进度。
  - 系统原生同步通道只作为可选适配器；逐设备报告可用独立链路，禁止声称系统未开放的 RFCOMM/BLE 接口可用。
  - Android Watch：BLE/RFCOMM 发现与控制通道，认证后优先升级本地 IP。
  - Wear OS：Google Data Layer 优先，无法使用时回落到同一 BLE/IP 协议。
  - watchOS：WatchConnectivity，映射即时消息、后台用户信息和文件传输。
  - HarmonyOS：系统分布式连接/数据能力；若目标 SDK 不提供等价传输，则使用 BLE 发现加认证 IP。
- 手机 SDK：
  - 三个平台暴露相同状态、消息、文件和连接状态接口。
  - 示例 companion 展示配对、双向状态编辑、离线消息、文件续传及错误恢复。
  - 传输层不得理解业务数据；WatchRSS 后续通过应用层 schema 使用该 SDK。

### 3. 通知与后台任务

- 通知 API：
  - `notifications.permission/status/requestPermission`
  - `notifications.schedule/cancel/listPending`
  - `notifications.registerRemote/unregisterRemote`
  - `notifications.onOpen/onAction`
- 通知描述统一包含稳定 ID、标题、正文、计划时间、分类、操作按钮和应用 payload。
- 远程推送由各平台原生 host 注册；框架只返回不透明 token 和平台标识，不保存服务端凭据。
- 点击、操作和冷启动事件先写入持久事件箱，再在下一个可用 frame 交付，避免启动期间丢事件。
- 后台任务 API：
  - `background.register/cancel/status`
  - handler 返回 `success | retry | failure`。
  - 调度时间是“最早运行时间”，不得承诺准点或统一最小周期。
- 系统唤醒时冷启动受限、无 UI 的 QuickJS handler：
  - 每次只有一个任务实例。
  - host 提供 deadline 与取消信号；超时立即终止并记录结构化结果。
  - 禁止绘制、动态加载代码和常驻进程。
  - 所有写入必须幂等；`retry` 使用平台退避策略。
- 平台映射：
  - Android/Wear OS：WorkManager；确有用户可见长期工作的场景留待后续 foreground service 能力。
  - watchOS：BackgroundTasks/WatchConnectivity 后台传输；接受系统严格调度限制。
  - HarmonyOS：系统 Work Scheduler/后台任务机制。
- v1 不包含运动持续会话、后台音频扩展、常驻 socket 或任意周期轮询。

### 4. 基础无障碍

- 为公共组件增加：
  - `accessibilityLabel`
  - `accessibilityRole`
  - `accessibilityValue`
  - `accessibilityHint`
  - `accessibilityHidden`
  - `accessibilityState`
  - `accessibilityActions`
- 角色首版固定为 `text/button/image/header/link/checkbox/switch/adjustable/list/listitem`。
- Rust core 从已提交 native tree 生成独立语义树，包含稳定 node id、边界、顺序、状态和可执行动作；仅在语义 hash 变化时提交 host。
- 默认规则：
  - `Text` 自动生成文本标签。
  - 可按压元素默认是 button。
  - 装饰图片默认隐藏；有 label 的图片才可朗读。
  - 焦点顺序按可见树序排列，不使用屏幕坐标猜测。
- Host 桥接：
  - Android/Wear OS 使用 `AccessibilityNodeProvider` 和虚拟节点。
  - watchOS 为渲染面建立对应的可访问元素，并把 activate/adjust 动作回送 runtime。
  - HarmonyOS 使用 ArkUI/XComponent 可访问性桥接能力。
- 首版支持朗读、焦点移动、点击、增减调节和选中/禁用状态；动态公告、动态字号、高对比度和减少动画进入后续版本。

## Test Plan

- 协议测试：
  - 版本协商、HMAC 失败、乱序、重复、丢帧、断线重连和游标不提前推进。
  - 双端同时修改同一状态、删除与更新竞争，结果必须确定一致。
  - 文件缺块、错误哈希、空间不足、传输取消、进程被杀后续传。
  - 复现 WatchRSS 的大正文场景：多批传输中断后不能错误推进 ACK，重试后完整校验通过。
- 后台/通知测试：
  - 权限拒绝、重复调度、取消、远程 token 更新、点击冷启动、事件重复去重。
  - handler 成功、重试、异常、超时、系统取消和无能力访问。
  - 后台运行期间不得初始化渲染器或持续占用帧循环。
- 无障碍测试：
  - native tree 到语义树 golden。
  - 隐藏节点、合并标签、稳定焦点顺序和动作回传。
  - TalkBack、VoiceOver 及 Harmony 读屏真机确认基础页面可以完整读出并执行主要操作。
- 四端验收：
  - 每个平台完成手机与手表在线同步、双方离线修改、重连合并、消息投递及文件断点续传。
  - 本地通知和一条真实远程推送可到达，点击后 payload 正确。
  - 后台任务在系统允许的窗口内实际执行，并提供系统日志与持久结果。
  - 进行 10 分钟稳定性和功耗观察；源码编译、安装成功或静态截图均不能代替真机交互验收。

## Delivery Order and Assumptions

1. 先完成 ABI、manifest、通用协议和模拟双端测试。
2. 完成三个手机 SDK 及 Android/Wear 传输闭环。
3. 接入 watchOS 与 HarmonyOS 同步适配器。
4. 增加通知和受限后台 runtime。
5. 增加语义树与四个平台无障碍桥接。
6. 最后跑四端真实设备矩阵并更新 implementation status。

默认不修改现有 WatchRSS v14 协议，也不在 PodJS 框架中引入 RSS 业务对象；WatchRSS 只能作为可靠性语义和回归场景来源。现有未提交的 PodJS Android 服务代码必须保留并在实施前重新审计，禁止重置或覆盖。
