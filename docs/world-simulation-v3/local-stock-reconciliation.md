# 显式储备请求与当前库存对账

2026-10-08。阶段 0–10 已结项；本项不新增阶段编号。

## 现有能力与本次范围

阶段 6 已实现局部知识、产权约束、缺料依赖、生产、真实运输和商人交易。此前桌面旧基线上的自主补给草案与这些能力重叠，不作为新系统实施依据。

本次只修复显式储备策略请求在外部补货后仍保留旧缺口的问题。原聚合公式用旧 requested 作为下界，并在 missing 为零时跳过，导致尚未承诺的需求无法缩减。

## 数量契约

实物缺口 D = max(0, 储备目标 - 当前精确规格库存)。已承诺 C = reserved + inTransit。
新请求总量 = delivered + max(D, C)。历史交付不是当前库存，不能再次从 D 扣减。
若 D 与 C 同为零，取消剩余请求，保留原 ID、原始需求量、历史交付和所有回执；不以手动补货伪造 DELIVERED。

已预留、已领取及不确定转移都保留原义务。本次不会自动撤销运输，因而既有承诺可能让库存超过储备目标；保证的是不再额外承诺已经不存在的缺口。生产关联需求保持不变，因为原生产单输入和输出数量固定，需独立设计取消/变更契约。家庭自动需求不在 2026-10-08 的初始范围内；2026-10-19 的扩展与证据见文末。

## 接入与恢复

新增 NeedRequestData.reconcileStockDeficit，由 SupplyFlowService 显式策略聚合调用。不新增 SavedData、字段、状态、网络载荷或物品快照，沿用 schema 1。通过已有 resize/transition 写入并标脏，重载保持同一请求与回执。重复相同测量不刷新等待时间，避免破坏已有退避。

## 验证

新增单元测试覆盖外部部分/全量补货、已预留与在途保留、部分交付历史、保存重载、增长与重复测量、已交付不复活、时钟/负数/溢出拒绝。真实容器与聚合接入另以新增隔离 GameTest 验证。实际结果见后文日期记录，不重复既有阶段验收。

## 还原限制

本次还原后源码与验收记录存在，Git 初始无未提交差异；run 与 build 不存在。原测试世界、JFR、截图和原始日志未在此次还原中找到，不能宣称已恢复或用新生成证据替代。

## 参考

- 项目阶段 6 实施说明、阶段 10 验收记录及总计划第 6 节。
- https://minecolonies.com/wiki/systems/request/ ：逐层解析既有物资与生产请求的参考，不引入集中式产权。
- https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/ ：SavedData 标脏和存储范围；不构成跨文件原子事务保证。

## 2026-10-08 实际结果

- 定向单元测试 22 项通过：新增 StockDeficitReconciliationTest 7 项、原 NeedRequestDataTest 8 项、SupplyFlowDataTest 7 项；失败/错误均 0。
- 两项新增真实容器 GameTest 通过；仅启用 sagadyssey_stock 命名空间，没有运行旧阶段测试。成功世界及日志：run/phase9-gametest/stock-reconciliation-20261008-verified/。
- 先前三次测试配置排错目录均保留：stock-reconciliation-20261008（模板命名空间错误）、-fixed（按模板命名空间过滤后无测试，虽然 Gradle 返回成功但不计通过）、-final（缺少内存模板）。不删除失败证据。
- 构建与 verifySocialSystem 检查通过；build 使用 -x test，单元测试由前述定向命令单独执行，不声称全量单元测试已重跑。
- 重现命令：gradlew.bat test --tests '*StockDeficitReconciliationTest' --tests '*NeedRequestDataTest' --tests '*SupplyFlowDataTest'；gradlew.bat runGameTestServer -PstockReconciliationOnly=true -Pphase9GameTestRun=<新的唯一目录名>；gradlew.bat build -x test。
- 没有真人客户端新增验收，没有修改原存档，没有执行 reset/checkout/clean，没有提交代码。
## 2026-10-19 后续设计：家庭自动补给对账

只读检查确认原改动、隔离测试世界和本项证据目录仍在；尚未找到阶段 10 原始世界和证据。本轮不重跑阶段 0–10 验收。

已确认缺口：householdDemand 在 deficit < 1 时直接退出，活动请求既不随手工补货缩减，也不随后续消耗增长。本轮复用 reconcileStockDeficit；家庭库存按既有 HouseholdProvisionPlan 分类统计，苹果等食品可减少面包缺口，木炭等可减少煤需求，不改变既有单位/配方。未新增系统或阶段编号。

约束：没有原请求且缺口为零时不建单；原请求部分/全部履约历史保留，DELIVERED 只在有新缺口时关闭并另开时间窗；活动的生产关联请求仍按固定生产契约处理；已预留、在途和不确定转移不取消、不清零。没有承诺且实物已满足时取消剩余请求。连续增加的缺口更新原请求 ID，不再生成同用途副本。沿用 schema 1，不改玩家载荷。

验证计划：新增真实家庭、真实粮仓场景，先在修复前重现失败，再验证食品部分补给、木炭补满及再次消耗、真实预留保留、原家庭成员实际取货后外部补满并完成原交付。保存重载核对原请求历史与供给关联。不要求真人重复旧阶段验收，不更改原存档。
### 2026-10-19 实现与实测结果

- 仅在 SupplyFlowService.householdDemand 接入已有 reconcileStockDeficit：零缺口也核对原请求；持续缺口更新原 ID；没有缺口时不新建；固定生产关联保持不变。没有新增 schema、网络字段、执行器或第二套需求账本。
- 修复前，在新隔离目录 household-stock-20261019-before 运行 6 项当前功能场景：上次 2 项通过，本轮最初 4 项全部因目标断言失败；实际领取面包的场景也在携货后复现缺口不收敛。
- 修复后，同样 6 项在 household-stock-20261019-after 全部通过。随后补充真实铁镐生产单的输入/输出和原请求不被手工补货改写的保护场景，最终 household-stock-20261019-final 中 7 项全通过。
- 新增 5 项 GameTest：苹果减少面包需求及随后消耗扩大缺口；木炭补满后取消煤需求及新时间窗；实物预留保留；实际生产关联保持；原家庭成员实际携带 3 块面包，外部补满后仍以同一请求完成真实交付。最后一项核对源桶 0 / 原货舱 0 / 粮仓 3 块面包，同时保留外加 6 个苹果，不用数量回执模拟搬运。
- 27 项相关单元测试通过：StockDeficitReconciliationTest 7、NeedRequestDataTest 8、SupplyFlowDataTest 7、HouseholdLifecycleTest 4、HouseholdProvisionPlanTest 1；无失败/错误。完整构建与 verifySocialSystem 通过，测试任务只运行这 5 个类，不声称重跑全部单元测试或阶段验收。
- 存档覆盖是对 NeedRequestData / SupplyFlowData 的实际 save/load NBT 往返，保留旧取消历史、新请求及家庭主体关联；本轮没有独立 JVM 续运或真人保存重进验收，不把序列化往返称作这些验收。
- 本轮证据：build/reports/household-stock-20261019/，包含失败/修复后/最终服务端日志、最终完整控制台、27 项测试 XML、构建控制台和产物 SHA256。三个新隔离世界全部保留，上次 2026-10-08 的所有世界/日志未清理。
- 重现新增场景：gradlew.bat runGameTestServer -PstockReconciliationOnly=true -Pphase9GameTestRun=<全新唯一目录名>。相关单测及构建：gradlew.bat test --tests '*StockDeficitReconciliationTest' --tests '*NeedRequestDataTest' --tests '*SupplyFlowDataTest' --tests '*HouseholdProvisionPlanTest' --tests '*HouseholdLifecycleTest' build。
- 剩余边界：已承诺的运输可能造成超储备；生产单的取消/改量须另立契约；本轮未处理迁移粮仓、家庭解散或更换户主时的在途义务。未恢复缺失的阶段 10 原始证据，不新增阶段编号。没有子代理、提交代码或原存档操作。
## 2026-10-19 后续设计：家庭粮仓迁移与解散

已只读确认上一轮所有改动及证据仍在，未发现额外修改。新缺口：家庭自动请求按家庭/用途查找，未验证当前粮仓；迁移后可能把新缺口写入旧地址请求，家庭解散后旧请求也仍可匹配。

本轮契约：仅处理 HOUSEHOLD:household:food/fuel/tools 三类自动请求。原请求、资产和运输目标不可改写。当前粮仓由 HouseholdData 的登记及稳定物理容器 ID 判断；区块卸载或成员不在场不算迁移。换户主本身不更换家庭身份或库存权属。

- 尚未取货且没有待提交意图的 HELD 路线，先调用原释放服务撤销真实预留和物流单，再结束旧请求，不直接清空计数。
- 已携货、存在待核对意图或 REVIEW 路线时保留原身份/货物/回执。必要时记录 UNREACHABLE，原持货者按原执行器和产权验证完成原目标交付；没有权限或目标失效则继续保管待核对，不自动改派。
- 存在固定生产关联时不修改原生产输入/输出或悄悄取消子请求；等待原义务完成或管理员明确处理。
- 尚有旧义务时，同一家庭/用途的新粮仓请求暂缓。旧义务结清后，完整交付关闭为历史，部分交付保留实际 delivered 后取消剩余缺口；随后根据新粮仓实物创建新请求。
- 无论调用聚合还是单独调用匹配，都不为过时家庭目标新增预留。家庭解散或解除粮仓登记按同一规则处理，不新增世界扫描、资产副本或存档 schema。

验证计划：先用新增隔离 GameTest 复现未承诺迁移、已预留迁移、待核对取货意图、实际携货迁移、解散前未取货及实际携货解散。检查旧租约释放、新旧请求目的地隔离、原人物实际交付及面包数量守恒；序列化往返保留原路线与在途引用。所有新世界和失败日志保留，不复测阶段 0–10 验收。
### 家庭粮仓迁移与解散：本轮实现和验证结果

- 新增 currentHouseholdDestination 校验及 reconcileHouseholdDestinations 清理；聚合先核对旧目标，匹配入口独立阻止向过时粮仓新增承诺。仅以家庭登记和稳定容器 ID 判断，不把区块卸载当作迁移。
- 通过原 release 服务释放没有待提交意图的未领取路线，实际撤销预留与管理物流单；保留携货、pending 和 REVIEW 路线。原目的地、人物和回执均不改写。
- 已全部履约的旧请求转 CLOSED；部分实际交付完毕后转 CANCELLED，保留 delivered 历史，仅取消剩余旧缺口。有固定生产单时原契约保持不变，同用途新请求暂缓，不虚构生产撤销或重建物资。
- 仅在存在过时请求时构建一次按 need 分组的路线索引，避免逐个旧请求反复扫描全部历史路线。未做新的百人性能验收，不宣称本轮覆盖了该性能边界。
- 修复前首次测试编译缺少 ContainerIds 导入，控制台保留；修正测试导入后，household-destination-20261019-red 的 13 项中，旧 7 项与待核对意图保护场景通过，新增的 5 个问题场景失败。失败世界及日志保留。
- 实现修复后，household-destination-20261019-after 的 13 项全部通过；补充生产关联迁移及完整六块面包交付后迁移的两个边界，最终 household-destination-20261019-final 中 15 项全部通过（上轮 7 项、本轮 8 项）。未执行阶段 0–10 GameTest 验收。
- 新增 8 项场景：无预留迁移且直接匹配旧目标被阻止；已预留迁移先释放后新匹配；迁移保留无回执的取货意图；实际携货迁移先结清原目标；解散后释放未取货；解散后原人物继续结清携货；迁移不改固定生产单；完整实际交付后关闭原请求历史。已携货的两项分别核对原人物、源桶 0、原货舱 0、旧粮仓 3 块面包；完整交付场景核对旧粮仓 6 块面包。新粮仓没有远程收到原携货物资。
- 33 项相关领域单元测试通过，失败/错误/跳过均 0；构建及 verifySocialSystem 通过。命令：gradlew.bat test --tests 'com.jgeted.sagadyssey.social.needs.*' --tests 'com.jgeted.sagadyssey.social.household.*' build；没有声称全量单元测试已重跑。
- GameTest 命令：gradlew.bat runGameTestServer -PstockReconciliationOnly=true -Pphase9GameTestRun=<全新唯一目录名>。证据目录 build/reports/household-destination-20261019/ 保存修复前后及最终日志、最终控制台、初次编译失败控制台、33 项 XML、构建控制台与产物 SHA256。
- NBT save/load 验证保留原目标、pending 意图和生产关联；未进行新的独立 JVM 或真人重进验收。所有先前证据和本轮三个新测试世界保留；未修改原存档、提交代码或使用子代理。
- 剩余边界：本轮没有新增自动退货/改派、生产单改量、离户成员未取货任务重新分配或权限争议处理。原目标失效或产权核验不通过时，原执行器继续保管/阻塞，不能称作已经交付；REVIEW 和固定生产契约需要原流程完成或明确核对后才能解除新请求等待。
## 2026-10-19 家庭食物替代选品接入：固定范围交付

本轮在 HEAD `647a193d12122a99d6940a7faed16a605afaf2d9` 的现有未提交工作上增量实施。开始时为分离 HEAD，已有 17 个已跟踪修改、9 个未跟踪文件；保存了这批文件及拟涉及文件的 31 份原始副本/哈希。本轮仅修改五个运行时源码文件，新增 HouseholdFoodSupplyGameTests、NpcPlannedFoodTest，并追加本记录。没有提交、重置、清理、修改原玩家存档或使用子代理。

### 已落实契约

- 在 NpcFoodItems 集中计划模式的实际可消费判定，LifePlanProvider 与 LifeTaskExecutor 共用。日常储备计数和选品使用普通饥饿口径：支持表中恢复值至少 250 且本执行通路能消费的物品。蜂蜜瓶需要尚未实现的容器返还事务，仍拒绝；胡萝卜、土豆等保留严重饥饿时实际消费的行为，不计入日常储备。旧版 AI 的 findFoodSlot 及聚落口粮表保持原语义。本条收紧了前文仅依赖 FOOD 组件的家庭储备口径。
- 数量仍按件：苹果和面包各计一件，饥饿恢复分别保持 260、400，不引入营养换算或食品加工。
- 只观察家庭已知、同维度、已加载且沿用现有权限/可用库存检查的来源。保持仍有合法供给的当前精确品种；首次优先合法面包，其余按物品 ID/组件指纹稳定选择，来源按 ID 排序。无合法替代时保留原请求及等待原因，只有来源观察不形成预留或交付。
- 家庭食物用途仅允许一个活动请求。换品前核对预留、在途、活动路线、pending、REVIEW、真实残留租约和固定生产关联；还检查终态历史请求上的未结义务。REVIEW 虽然 closed() 为真，仍阻止换单。过期重开和缺口归零收尾使用同一检查；历史多活动单只停止新建/匹配并记录原因，保留原履约。
- 旧承诺存在时不为剩余缺口另开苹果单。结清后才按当前实物建立新请求，保留旧精确身份、部分交付和回执；同一游戏刻不复用旧终态请求身份。真实预留和取货继续走既有匹配、运输、权限与转移服务。

### 五组验收及证据

| 组别 | 本轮实际验证 |
| --- | --- |
| 1. 苹果真实运输与消费 | 原家庭成员运输 6 个苹果，源/货舱/粮仓核对为 0/0/6；随后真实消费一个，粮仓为 5，饥饿从设定的 500 降至与恢复 260 及有限代谢对应的范围；核对完成的进食回执及原人物身份。 |
| 2. 不合法来源与食物 | 未知、无权、实际未加载来源不被选中或强制加载；蜂蜜瓶、应急胡萝卜及不支持的 FOOD 物品不填满日常储备，不生成虚假物流单。 |
| 3. 旧义务保护 | 真实面包预留、准备中转移、零计数 REVIEW、到期但 pending 未清、终态历史 REVIEW、固定生产关联及历史多活动单均阻止错误换单。真实携带的 3 块面包先入库，旧请求保留 delivered=3，之后仅为剩余缺口新建 3 个苹果需求。协议异常案例明确使用持久账本接缝，不冒充真实搬运。 |
| 4. 稳定调度及重载 | 当前面包有货时保持；失去供给后选苹果，面包再次出现也不切回；所有来源清空后重复调度仍是同一等待请求。实际保存并从磁盘读取 NeedRequestData/SupplyFlowData，再接回当前服务器继续聚合和匹配，产生一次真实预留。此为同一 JVM 的实际账本重载，不是独立 JVM 或真人重进验收。 |
| 5. 选品后的变化 | 选品后移走库存，匹配不产生预留；真实预留后撤销来源转移授权或移走库存，原运输执行器实际取货失败，货舱/粮仓/交付计数均为零，原义务保留。 |

先在原业务实现上加入三个复现：苹果来源无法选中、错误食物满足储备、零计数 REVIEW 被结清。首次编译补正了一处测试导入，日志保留；有效失败复现 `household-food-20261019-red2` 共 21 项，三个新增目标断言失败，原 18 项通过。修复后 `after1` 的 21 项全部通过。补齐五组验收后 `accept1` 共 32 项全部通过，其中本轮新增 14 项，其余 18 项为既有补给、家庭迁移及持久化恢复回归。

74 项相关单元测试通过，失败/错误/跳过均为 0（家庭、需求与生活计划相关包，包括本轮 4 个判定/件数测试）。构建、verifySocialSystem、verifyProductionJar 和 git diff --check 通过。没有宣称全仓全部测试或阶段 0–10 重新验收。

复现与验收命令（每次游戏测试使用新的隔离目录）：

```powershell
.\gradlew.bat --offline runGameTestServer -PstockReconciliationOnly=true -Pphase9GameTestRun=<新的唯一目录名>
.\gradlew.bat --offline test --tests 'com.jgeted.sagadyssey.social.needs.*' --tests 'com.jgeted.sagadyssey.social.household.*' --tests 'com.jgeted.sagadyssey.npc.brain.life.*'
.\gradlew.bat --offline build -x test verifySocialSystem
```

证据保存于 `build/reports/household-food-20261019/`：首次编译、失败复现、修复后和最终控制台/服务器日志，74 项 XML，构建日志，基线哈希、增量差异、当前源码和产物 SHA256。新增隔离世界及此前证据均保留，原始副本另保存在本次工作区的 `household-food-work/baseline/`。

五组验收已经达到本增量终点。完整自然生长、自养平衡、远方模拟和通用食品系统继续分开处理，不在本轮扩展。
