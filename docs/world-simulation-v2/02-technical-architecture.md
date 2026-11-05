# 世界模拟 v2：技术架构

本文把[总体设计](./README.md)转为可实现的服务器架构。命名是建议基线，实际编码时可依据 NeoForge/Minecraft 版本调整，但边界和不变量不得弱化。

## 1. 架构原则

### 1.1 服务端权威

- 世界事实、权利判断、知识过滤、AI 决策和交易提交只在服务端执行。
- 客户端提交意图和稳定 ID，不提交结果、不提交价格判定，也不提交槽位数组索引作为长期身份。
- 服务端先验证玩家控制的人物、交互距离、权限、视线/沟通渠道、对象修订号和速率限制。
- S2C 载荷是面向某个观察者构建的只读投影，不能复用“完整 NPC 数据包”。

### 1.2 领域状态不依附实体生命周期

`NpcBase` 负责身体、导航、动画、战斗和与 Minecraft 实体系统的桥接。人物身份、契约、财产声明、关系、知识和长期计划存于世界 SavedData/持久领域仓库。

实体卸载不会让人物失业、失忆、清空债务或失去财产。

### 1.3 命令与查询分离

- Command 表达“想做什么”，由服务端验证并产生结果或失败原因。
- Query/Projection 表达“这个观察者现在获准知道什么”。
- GUI 不直接修改实体字段；网络处理器不直接拼装经济后果。
- 跨系统变化通过领域服务与事件完成。

## 2. 推荐包结构

```text
com.jgeted.sagadyssey
├─ character/              人物身份、身体绑定、人生历史
├─ social/
│  ├─ relation/            多维私人关系
│  ├─ household/           家庭与成员角色
│  ├─ institution/         制度、成员、职位、规则、合法权限
│  ├─ contract/            服务、借贷、租赁、交付与债务
│  └─ claim/               社会主张、证据、裁决与执行
├─ property/
│  ├─ asset/               资产批次、容器上下文、声明
│  ├─ rights/              权利类型、授予、撤销与判定
│  └─ transfer/            原子转移与审计
├─ knowledge/
│  ├─ fact/                世界事实标识与值类型
│  ├─ belief/              人物信念、来源、置信度、时效
│  ├─ perception/          观察、证词、文书与传播
│  └─ projection/          面向观察者的 UI 读模型
├─ economy/
│  ├─ needs/               储备目标和需求
│  ├─ production/          生产批次与消费
│  ├─ exchange/            提议、谈判、成交、赊欠
│  ├─ market/              本地报价和成交统计
│  ├─ credit/              债权、担保、抵押和清偿
│  └─ money/               支付物、面额、接受规则
├─ simulation/
│  ├─ scheduler/           分片调度、预算和任务队列
│  ├─ lod/                 细节层级快照与对账
│  └─ event/               领域事件与审计日志
└─ npc/
   ├─ entity/              身体适配器
   ├─ action/              可执行世界动作
   ├─ interaction/         情境动作与会话
   └─ client/              客户端投影展示
```

现有 `social/*` 包可以渐进迁入，不要求一次改完目录；但新代码应遵守边界。

## 3. 核心标识

所有跨 tick、跨网络和跨存档引用使用稳定 ID：

| 标识 | 用途 |
|---|---|
| `CharacterId` | 玩家人物或 NPC 人物；UUID |
| `BodyRef` | 当前实体 ID + 维度，仅作短期定位 |
| `HouseholdId` | 家庭 |
| `InstitutionId` | 制度 |
| `OfficeAppointmentId` | 一次职位任命 |
| `ContractId` | 契约 |
| `ContainerId` | 注册容器/虚拟携带容器 |
| `AssetClaimId` | 例外物权与负担声明 |
| `TransferId` | 一次原子转移 |
| `KnowledgeId` | 一个主体-谓词信念 |
| `TradeSessionId` | 一次谈判会话 |
| `ProposalId` | 一个带修订号的提议 |
| `ClaimId` | 一项所有权、身份、品质或管辖主张 |
| `EvidenceId` | 一件证据或见证记录 |
| `DebtId` | 一项可清偿、转让或争议的债权债务 |
| `StandardId` | 度量、币制、品质或资格标准 |
| `DomainEventId` | 审计事件 |

网络包中的 `entityId` 只用来找到交互现场，随后必须解析为 `CharacterId`。不得以实体 ID 作为交易、契约或知识的永久主键。

## 4. 数据模型

以下伪 Java 只表达字段与边界，不要求照抄语法。

### 4.1 人物、成员资格与职位

```java
record CharacterRecord(
    UUID id,
    NameHistory names,
    LifeSpan life,
    OriginIdentity origin,
    PersonalityProfile personality,
    CharacterStatus status,
    long revision
) {}

record MembershipRecord(
    UUID id,
    UUID characterId,
    SubjectRef group,          // household or institution
    String role,
    MembershipStatus status,
    long joinedAt,
    Long endedAt,
    EvidenceRef basis
) {}

record OfficeAppointment(
    UUID id,
    UUID institutionId,
    UUID holderId,
    String officeType,
    Set<AuthorityScope> scopes,
    Jurisdiction jurisdiction,
    UUID appointedBy,
    long startedAt,
    Long endsAt
) {}
```

现有 `CharacterRecord.socialClass/office/factionId` 的迁移目标：

- `socialClass` → `OriginIdentity.birthEstate` + 必要的现行 `StatusRecognition`；
- `office` → `OfficeAppointment`；
- `factionId` → 一个或多个 `MembershipRecord`；
- 旧字段在兼容期只读派生，最终删除。

### 4.2 关系

```java
record DirectedRelationship(
    UUID observerId,
    UUID subjectId,
    int familiarity,
    int trust,
    int affection,
    int respect,
    int fear,
    int obligation,
    int grievance,
    Map<ContextKey, Integer> legitimacy,
    long lastMeaningfulEventAt,
    long revision
) {}
```

数值为服务端内部状态。正常 UI 映射为叙述区间，不显示精确整数。

关系变化只能由带来源的 `RelationshipEffect` 应用，禁止任意 `setTrust(80)`。每个 effect 记录事件、维度、变化量、上限/衰减规则。

### 4.3 权利与资产

```java
enum AssetRight {
    POSSESS, ACCESS, USE, CONSUME,
    MANAGE, EXCLUDE, TRANSFER, INCOME
}

record RightGrant(
    UUID id,
    SubjectRef assetOrContainer,
    SubjectRef holder,
    EnumSet<AssetRight> rights,
    EvidenceRef basis,
    ScopeConstraint scope,
    long startsAt,
    Long endsAt,
    boolean revocable
) {}

record ContainerContext(
    UUID containerId,
    ContainerLocator locator,
    SubjectRef custodian,
    SubjectRef defaultBeneficialOwner,
    AccessPolicy accessPolicy,
    long revision
) {}

record AssetClaim(
    UUID claimId,
    SubjectRef beneficialOwner,
    SubjectRef custodian,
    Set<Encumbrance> encumbrances,
    Set<ReservationRef> reservations,
    EvidenceRef provenance,
    long revision
) {}
```

`RightsService.can(actor, right, subject, context)` 聚合：

1. 直接所有权；
2. 容器默认权利；
3. 明确授权；
4. 职位与制度规则；
5. 契约权限；
6. 临时紧急规则；
7. 撤销、期限、地域和冲突限制。

它返回结构化结果：`ALLOW/DENY/CONTESTED`、依据和可向该角色披露的原因，而不是只有布尔值。

### 4.4 知识与信念

```java
record FactKey(SubjectRef subject, String predicate, String qualifier) {}

record BeliefRecord(
    UUID knowerId,
    FactKey key,
    BeliefValue value,          // exact, range, category, proposition
    double confidence,
    KnowledgeSource source,
    List<UUID> provenanceChain,
    long learnedAt,
    long lastConfirmedAt,
    Long staleAfter,
    DisclosurePolicy disclosure,
    long revision
) {}
```

事实真值不必复制成每个人的信念。只有被观察、告知或推断的内容进入信念库。信念合并规则需要处理：

- 同源重复消息不应无限提高置信度；
- 独立来源可以提高置信度；
- 直接新观察通常覆盖陈旧传闻；
- 冲突证词并存，直到角色选择相信其中一种或保留不确定性；
- 随时间衰减的是确定性或时效，不是自动改写事实值。

### 4.5 服务关系与契约

```java
record ServiceRelationship(
    UUID id,
    UUID principalId,
    UUID agentId,
    ServiceBasis basis,
    Set<AuthorityScope> scopes,
    List<Obligation> principalObligations,
    List<Obligation> agentObligations,
    ServiceLimits limits,
    ContractStatus status,
    long startedAt,
    Long endsAt,
    long revision
) {}
```

`NpcWorkAuthority.PLAYER` 被移除。机构、合同、职位和家庭协作都通过具体关系产生授权。一个人物可以同时有多项关系；冲突由优先级、紧急性、正当性和违约代价进入决策，而不是要求“唯一工作权威”。

### 4.6 需求、意图与生产

```java
record StockTarget(
    SubjectRef actor,
    ItemCategory category,
    int minimum,
    int preferred,
    int maximum,
    Horizon horizon,
    EvidenceRef reason
) {}

record TradeIntent(
    UUID id,
    SubjectRef representedParty,
    IntentType type,            // OFFER, REQUEST, SERVICE
    AssetPredicate asset,
    QuantityRange quantity,
    TermsConstraint terms,
    Visibility visibility,
    long validUntil,
    long revision
) {}

record ProductionBatch(
    UUID id,
    SubjectRef beneficiary,
    UUID workplaceId,
    RecipeRef recipe,
    List<ReservedAsset> inputs,
    List<UUID> workers,
    BatchState state,
    long workRequired,
    long workCompleted,
    long revision
) {}
```

生产开始时预留输入，完成时原子消耗并生成具有明确归属的输出。中止时根据进度和配方返还剩余或产生半成品/损耗。

### 4.7 交易会话

```java
record ExchangeLeg(
    SubjectRef from,
    SubjectRef to,
    AssetSelector asset,
    int quantity,
    TransferIntent intent,
    DeliveryTerm delivery
) {}

record TradeProposal(
    UUID proposalId,
    UUID sessionId,
    int revision,
    UUID proposedBy,
    List<ExchangeLeg> legs,
    List<FutureObligation> obligations,
    long validUntil,
    Set<UUID> acceptedBy
) {}
```

报价引用资产选择器和预留，不复制一个未来可能不存在的 ItemStack 结果。

### 4.8 主张、证据、标准与裁决

```java
record SocialClaim(
    UUID claimId,
    SubjectRef assertedBy,
    ClaimType type,             // ownership, authority, identity, quality, debt
    SubjectRef subject,
    ClaimValue value,
    List<UUID> evidenceIds,
    ClaimStatus status,
    long assertedAt,
    long revision
) {}

record EvidenceRecord(
    UUID evidenceId,
    EvidenceType type,          // possession, witness, document, seal, ledger, custom
    SubjectRef providedBy,
    EvidencePayload payload,
    Provenance provenance,
    TamperState tamperState,
    long createdAt
) {}

record MeasurementStandard(
    UUID standardId,
    UUID issuingInstitutionId,
    StandardType type,          // weight, measure, coin, quality, credential
    String definition,
    Jurisdiction jurisdiction,
    long validFrom,
    Long validUntil,
    long revision
) {}

record JudgmentRecord(
    UUID judgmentId,
    UUID institutionId,
    List<UUID> claimIds,
    List<UUID> acceptedEvidence,
    JudgmentOutcome outcome,
    List<EnforcementOrder> orders,
    long judgedAt
) {}
```

真值层仍知道实际 ItemStack 和领域状态；主张层表达社会主体如何描述和争夺它。`RightsService` 在正常无争议路径中快速判定，在相互冲突或证据异常时返回 `CONTESTED` 并建立案件/争议，而不是随意覆盖现有声明。

### 4.9 信用、债务与担保

```java
record DebtRecord(
    UUID debtId,
    SubjectRef creditor,
    SubjectRef debtor,
    List<SettlementTerm> settlementTerms,
    List<GuaranteeRef> guarantees,
    List<CollateralRef> collateral,
    long createdAt,
    long dueAt,
    DebtStatus status,
    EvidenceRef basis,
    long revision
) {}

record CreditAssessment(
    SubjectRef assessor,
    SubjectRef counterparty,
    ObligationKind kind,
    RiskBand risk,
    List<BeliefRef> reasons,
    long assessedAt,
    long staleAfter
) {}
```

`CreditAssessment` 是按需派生的主观判断，不作为人物全局属性持久化。债务可以部分清偿、延期、争议或由担保人承担；每次变化都走资产转移和领域事件。

### 4.10 世界社会模板与家庭生命周期

```java
record WorldSocialProfile(
    String id,
    String inspirationScope,
    HouseholdFormationRules householdRules,
    InheritanceRules inheritanceRules,
    LabourRules labourRules,
    CommonsRules commonsRules,
    MarketRules marketRules,
    JurisdictionRules jurisdictionRules,
    Map<String, SourceNote> rationale
) {}

record HouseholdTransition(
    UUID transitionId,
    UUID householdId,
    HouseholdTransitionType type,
    List<UUID> affectedCharacters,
    List<AssetAllocation> allocations,
    EvidenceRef cause,
    long occurredAt
) {}
```

模板只提供初始规则与参数边界。游戏中的法律、习俗和制度变化通过版本化规则事件演化，不能运行中偷偷更换模板。

## 5. 领域服务

| 服务 | 责任 | 不负责 |
|---|---|---|
| `CharacterService` | 人物生命周期、身体绑定 | GUI、寻路 |
| `MembershipService` | 家庭/制度成员和职位 | 物品转移 |
| `RightsService` | 权利解析、冲突与依据 | 实际修改库存 |
| `TransferService` | 锁定、拆分、移动、所有权变化、回滚 | 决定主观价格 |
| `KnowledgeService` | 获知、传播、合并、过期 | 客户端绘制 |
| `ProjectionService` | 按观察者生成只读视图 | 修改领域状态 |
| `RelationshipService` | 应用事件效果、衰减和上下文关系 | 直接命令 AI |
| `ContractService` | 签订、履约、欠薪、违约、解除 | 凭空支付 |
| `ClaimService` | 主张、证据、裁决状态与管辖 | 把制度裁决冒充客观真相 |
| `StandardService` | 度量、币制、品质与资格标准 | 自动让所有人物知道标准 |
| `DebtService` | 到期、部分清偿、担保、抵押、违约 | 生成支付物或全局信用分 |
| `ExchangeService` | 谈判、预留、接受、原子提交 | 生成职业商品 |
| `ProductionService` | 输入预留、工作进度、输出归属 | 全局价格 |
| `DecisionService` | 基于信念生成和选择行动 | 读取未获知真相 |
| `SimulationScheduler` | 分片、预算、延迟队列、LOD | 领域规则本身 |
| `DomainEventService` | 审计、分发、历史摘要 | 作为唯一状态数据库 |

## 6. 原子物品转移

所有赠礼、交易、工资、配发、归还和交付走同一 `TransferService`。

### 6.1 请求

```java
record TransferRequest(
    UUID transferId,
    UUID initiatedBy,
    TransferIntent intent,
    List<TransferLeg> legs,
    List<ExpectedRevision> expectedRevisions,
    EvidenceRef authority,
    long requestedAt
) {}
```

### 6.2 提交顺序

1. 解析所有主体、容器和资产，拒绝失效引用；
2. 按稳定 ID 排序并获取短期逻辑锁，避免死锁；
3. 核对来源修订号、实际数量和物品组件；
4. 调用 `RightsService` 验证每条腿所需权利；
5. 验证预留、期限、地点、距离、接收容量和规则；
6. 在内存事务中构造全部来源扣除和目标加入；
7. 若任一目标无法容纳，则不修改任何一方；
8. 应用库存和声明变化；
9. 写入转移审计与领域事件；
10. 释放锁并向相关观察者发送最小增量。

不得使用“先扣除，失败后向世界掉落结果”作为正常交易路径。掉落只属于明确选择的交付方式，且仍有所有权和位置记录。

### 6.3 幂等与并发

- `transferId` 在一定窗口内幂等；重复包返回原结果。
- 每个容器和交易提案有 `revision`。
- 两名玩家同时购买最后一件商品时，只有先成功提交者成交；另一方收到“库存已变化”的可理解失败。
- 断线不会留下半笔交易；未来交付则以契约义务保存，而非保持网络会话。

## 7. NPC 物品栏实现

### 7.1 目标结构

```text
NpcPersonalInventory: 36 slots
Equipment: vanilla armor + hands
Registered cargo containers: 0..n, external custody contexts
```

取消：

- `bowSlot`
- `arrowSlot`
- 9 格 `equipmentInventory` 中混合装备与背包的特殊语义

AI 装备逻辑：

1. 从装备位和 36 格背包查找可用武器；
2. 弓弩查询兼容弹药；
3. 依据权利检查是否允许消耗该弹药；
4. 对订单货物、借用品或他人财产默认不用于战斗，除非紧急规则或明确授权；
5. 消耗后记录必要的库存变化，不为 NPC 无限补弹。

### 7.2 容量与重量

第一阶段只统一槽位语义，不同时引入重量，以免把迁移、AI 和交互风险叠加。以后若引入负重，玩家和 NPC 应共享同一规则，并考虑容积、装备和载具。

## 8. 知识投影与网络协议

### 8.1 取代 `NpcStatsPayload`

现有载荷包含精确 HP、攻击、速度、护甲、经验、士气、招募价、完整交易列表和阵营内部字段。这会造成信息泄露，也迫使客户端理解后台模型。

改为：

```java
record NpcViewSnapshot(
    int bodyEntityId,
    UUID characterId,
    long projectionRevision,
    DisplayIdentity identity,
    List<ObservedCondition> conditions,
    List<KnownRelationship> relationships,
    List<KnownFact> knownFacts,
    List<AvailableInteraction> actions,
    List<VisibleAsset> visibleAssets,
    List<KnownAgreement> agreements,
    long expiresAt
) {}
```

`ProjectionService.forViewer(viewerCharacterId, targetCharacterId, context)` 在服务端构建它。不存在的字段不发送；粗略信息使用类别或范围，不把精确值塞进描述字符串。

### 8.2 推荐协议

```text
C2S OpenContext(bodyEntityId, requestNonce)
S2C NpcViewSnapshot(requestNonce, projection)

C2S PerformContextAction(characterId, actionId, expectedProjectionRevision)
S2C ActionResult(actionId, resultCode, publicEffects)

C2S OfferHeldItem(targetCharacterId, hand, count, intent, expectedStackRevision)
S2C HandoverResult(transferId, accepted, disclosedReason, newProjectionDelta)

C2S OpenTrade(targetCharacterId)
S2C TradeSessionView(sessionId, revision, visibleIntentions, eligiblePlayerAssets)
C2S ProposeTrade(sessionId, expectedRevision, proposal)
S2C TradeSessionDelta(...)
C2S AcceptProposal(sessionId, proposalId, proposalRevision)
S2C ExchangeCommitResult(...)

C2S RequestAppraisal(subjectRef, appraiserRef, expectedRevision)
S2C AppraisalResult(assertedQuality, evidence, disclosedLimitations)
```

`actionId`、`sessionId` 和 `proposalId` 都由服务端签发并有短期有效期，防止客户端构造未显示的动作。

投影中的不确定信息使用结构化字段，而不是预先拼成没有语义的字符串：

```java
record KnownFactView(
    String displayValue,
    PrecisionBand precision,
    SourceKind sourceKind,
    String sourceLabel,
    FreshnessBand freshness,
    ConflictState conflict,
    boolean exactValueAvailable
) {}
```

客户端可据此同时显示文字、来源和新旧程度；颜色只作辅助，不能作为唯一不确定性编码。

### 8.3 披露矩阵

| 信息 | 陌生观察 | 交谈/熟悉 | 合同当事人 | 获授权管理 | 调试权限 |
|---|---:|---:|---:|---:|---:|
| 外观与手持 | 是 | 是 | 是 | 是 | 是 |
| 自报姓名/职业 | 可能 | 是 | 是 | 是 | 是 |
| 精确 HP | 否 | 否 | 否；医疗可例外 | 否 | 是 |
| 伤势区间 | 可观察 | 是 | 是 | 是 | 是 |
| 私人物品全表 | 否 | 否 | 否 | 仅授权范围 | 是 |
| 交易展示库存 | 否 | 按意愿 | 按意愿 | 按职责 | 是 |
| 性格内部值 | 否 | 否 | 否 | 否 | 是 |
| 契约条款 | 否 | 当事人愿意透露 | 是 | 按职位 | 是 |
| AI 决策分解 | 否 | 否 | 否 | 否 | 是 |

## 9. 互动动作系统

### 9.1 动作定义

```java
interface ContextAction {
    ActionAvailability evaluate(ActionContext context);
    ActionStartResult start(ActionCommand command);
    ActionTickResult tick(ActionInstance action);
    void cancel(ActionInstance action, CancelReason reason);
}
```

`ActionAvailability` 返回：可用、不可用但可披露原因、完全隐藏。后两者的选择取决于玩家是否知道缺少的条件。

例如没有搜查权且从未想到搜查时，选项隐藏；卫兵明确告知“无搜查令”后，可以显示为不可用并说明原因。

### 9.2 递交状态机

```text
OFFERED
  ├─ invalid/changed → FAILED
  ├─ recipient refuses → REFUSED
  ├─ needs clarification → CLARIFYING → OFFERED
  └─ accepts → COMMITTING → COMPLETED / FAILED
```

NPC 接受判定考虑：是否识别物品、是否需要、是否认为安全、意图、关系、礼俗、容量、权利和可能的连带义务。拒绝理由只披露 NPC 愿意说且玩家能够理解的部分。

## 10. 决策与调度

### 10.1 候选行动生成

候选来源：

- 紧急身体需要；
- 已承诺任务和合同；
- 日程与制度职责；
- 家庭照料；
- 当前可感知威胁/机会；
- 收到的请求、消息和订单；
- 长期目标触发的计划步骤。

候选只引用 NPC 已知对象。系统可用真值检查行动能否实际执行，但不可用隐藏真值改善 NPC 的主观选择。

### 10.2 评价

内部评价可保留现有带噪声逻辑，但拆成可组合考虑项：

```text
subjective benefit
+ need relief
+ relationship and obligation
+ value/identity consistency
+ expected future effect
- perceived risk
- opportunity cost
- uncertainty penalty
- expected sanctions
```

结果产生：接受、拒绝、协商、等待或寻求更多信息。评价日志仅供调试；玩家获得自然语言行为和可获知原因。

### 10.3 调度预算

- 紧急感知与战斗由实体级事件触发；
- 普通决策按时间轮分片，不让所有 NPC 同 tick 重算；
- 日程与经济批次使用截止时间队列；
- 关系衰减、知识过期采用批处理；
- 每个系统设可配置预算和积压指标；
- 禁止每 tick 扫描全部人物、容器、聚落或知识条目。

## 11. 仿真细节层级

### 11.1 L0：实体级

- 实际方块与实体交互；
- 逐槽位资产；
- 即时观察、战斗、交谈、递交和交易；
- 高频位置和动作。

### 11.2 L1：区域级

- 实体仍存在但降低普通决策频率；
- 生产按工作片段累计；
- 感知范围和社交机会简化；
- 容器仍为物理真值。

### 11.3 L2：聚落事件级

- 只使用卸载前注册并快照的资产；
- 人物位置为“家/作坊/路上”等语义位置；
- 工作按班次、能力、输入和风险结算；
- 消费按人物/家庭进行，不用聚落总人口一次扣除；
- 途中运输保留订单和货物批次。

### 11.4 L3：世界级

- 只推进到期契约、长期旅行、人口生命周期和重大制度事件；
- 聚合普通重复生产，但保存输入来源和输出归属摘要；
- 结果在进入更高细节层级前展开或分配到具体批次。

### 11.5 对账

每次 LOD 切换生成 `SimulationCheckpoint`：

```java
record SimulationCheckpoint(
    SubjectRef scope,
    LodLevel from,
    LodLevel to,
    long gameTime,
    Map<AssetKey, Long> quantities,
    List<ActiveProcessRef> processes,
    List<ObligationRef> obligations,
    long sourceRevision
) {}
```

升层时验证：资产守恒、重复人物、过期预留、容器冲突和时间单调性。发现冲突时冻结相关批次并记录诊断，不使用“取较大值”等静默修复。

## 12. 领域事件与审计

### 12.1 事件信封

```java
record DomainEvent(
    UUID eventId,
    String type,
    long gameTime,
    SubjectRef actor,
    List<SubjectRef> subjects,
    EvidenceRef cause,
    VisibilityRule visibility,
    Importance importance,
    CompoundTag payload
) {}
```

事件流用途：

- 更新关系与记忆；
- 产生可传播知识；
- 更新市场成交统计；
- 形成编年史摘要；
- 支持测试和管理员审计。

### 12.2 保留策略

- 权利转移、契约、死亡、职位与争议：长期保留；
- 普通消费与重复劳动：按日/批次聚合；
- UI 临时查看：不进入世界历史；
- 每类事件有容量、聚合和重要性策略；
- 裁剪事件不能破坏当前权利的依据，必要时先形成不可变摘要凭据。

## 13. 现有代码的具体改造点

### 13.1 `NpcBase`

现状问题：集中持有身份、所有权、职业、命令、库存、弓箭槽、交易、物流和大量行为，形成超大聚合体。

目标：

- 保留身体属性、实体同步、动画、导航和战斗适配；
- 通过 `CharacterId` 查询长期人物状态；
- 用 36 格 `NpcPersonalInventory` 替换 9 格背包和弓箭槽；
- 通过动作/服务调用合同、权利、交易和知识；
- 逐步移除 `isOwned/ownerUUID/originalFaction` 作为规则来源。

### 13.2 `NpcEquipMenu`

- 改为权限感知的 `NpcInventoryMenu`；
- 服务端创建菜单前要求有效 `ACCESS`；
- 每个槽位根据 `USE/MANAGE/TRANSFER` 决定可拿取、可放入或只读；
- 默认不从人物交互入口打开；用于共同整备、搜查、医疗或调试等特定场景。

### 13.3 `NpcCommandScreen`

- 旧的固定按钮面板停止扩展；
- 拆为情境动作盘、人物志、合同详情和职责面板；
- 命令由 `AuthorityScope` 动态生成；
- 坐骑、农耕、物流等专业命令进入对应职责面板，不挤在同一 176×248 窗口。

### 13.4 `NpcStatsPayload`

- 废止完整状态发送；
- 由 `NpcViewSnapshot` 和各会话专用投影取代；
- 精确属性、AI 分值、隐藏库存和全部交易意图不进入普通客户端；
- 所有字符串长度、列表数量和枚举解码加硬上限与合法性验证。

### 13.5 `NpcTradePacket` 与 `NpcTradeRegistry`

现状存在的结构性问题：

- 客户端用数组索引选择交易；
- 玩家成本被扣除后直接创建结果；
- NPC 库存、资产权利和接收容量不参与；
- 交易列表由职业模板决定；
- 并发与报价版本无法正确表达。

目标：以 `TradeSession + TradeProposal + ExchangeService + TransferService` 完全取代。职业注册表只可保留为“初始需求/生产知识/市场偏好”的内容配置，不能生成商品。

### 13.6 现有社会系统

- `NpcEmploymentContract` 可迁移为第一种 `ServiceRelationship`；实物工资、欠薪和期限逻辑保留。
- `NpcWorkAuthority` 的单选结构改为多个授权来源；`PLAYER` 删除。
- `NpcLifeRecord` 拆分生活状态、关系、信念、成员资格和决定摘要，避免每次改变复制整个大记录。
- `HouseholdRecord` 保留，但共享储物改为容器与权利记录，不暗示全员共有。
- `WarehouseRecord` 保留空间注册；增加控制者、受益所有者、访问策略和修订号。
- `SettlementEconomyRecord` 退化为只读统计投影，不能作为物资真值。
- `WorldHistoryData` 保留编年史职责；底层新增可审计领域事件，编年史只选取其摘要。

## 14. 存档与迁移

### 14.1 版本

新增 `SagadysseyDataVersion = 2`。每类 SavedData 记录自己的 schema 版本；迁移幂等、可中断、可重复验证。

迁移前要求：

- 为最后一个 v1.x 可运行状态建立 Git 标签；
- 明确备份提示；
- 在世界副本上完成 dry-run 报告；
- 迁移写入完成标记前不删除旧字段。

### 14.2 NPC 物品栏

顺序：

1. 创建 36 格个人物品栏；
2. 原 9 格背包按原顺序迁入；
3. 旧弓槽和箭槽物品迁入下一空格；
4. 如果旧数据异常超量，放入绑定该人物的迁移保管箱，不得掉落或删除；
5. 计算迁移前后物品指纹与数量，记录报告；
6. 成功提交后才标记旧槽已迁移。

### 14.3 所有者与阵营

- `ownerUUID` → 一条迁移来源的无固定期限 `ServiceRelationship`，范围只覆盖旧版本确实允许的行为；
- 玩家可以在后续交互中重签正式合同；
- `originalFaction` → 原制度成员资格/来历记录；
- 当前 player faction 不再覆盖人物身份；
- 对无法判断的冲突生成迁移警告，不擅自选择身份。

### 14.4 旧交易

- 固定交易表不迁移为现货，因为其商品从未真实存在；
- 将职业模板转换为初始 `TradeIntent`、生产知识或市场偏好；
- 世界首次进入 v2 时，为既有商人只登记其真实背包/仓库资产；
- 不为保持旧界面可用而注入免费库存；
- 可提供一次受配置控制的世界初始化补货，但必须记录明确来源为“v2 世界迁移配置”，且默认关闭。

## 15. 测试策略

### 15.1 不变量测试

- 任意成功转移前后，各物品类别和组件指纹总量守恒；
- 失败交易不改变任何库存、声明、债务或关系；
- 没有 `TRANSFER` 权的人不能通过 GUI、快捷键或伪造包出售资产；
- 只读权不能通过 shift-click、拖拽、数字键或断线重连绕过；
- 普通客户端载荷不含无权知道的精确字段；
- 同一事件不会因重包重复应用；
- 实体卸载/重载不改变人物身份和契约。

### 15.2 属性与并发测试

- 随机生成拆分/合并/转移序列，检查权利声明和数量守恒；
- 两个交易会话竞争同一物品；
- 提案后库存、权限、位置或容量变化；
- 成交提交时一方断线或实体卸载；
- LOD 降级/升级反复循环；
- 迁移中途崩溃后重启；
- 多人同时查看、递交、搜查和交易同一 NPC。

### 15.3 知识泄露测试

- 对同一 NPC，用陌生人、亲属、雇主、仓管和管理员五种观察者生成投影快照；
- 对序列化后的原始字节扫描隐藏姓名、物品、精确数值和合同字段；
- 验证客户端无法通过请求其他 `actionId`、旧 revision 或猜测人物 UUID 获取信息；
- 验证人物忘记/知识过期后投影撤回或降为陈旧描述。

### 15.4 LOD 等价性测试

同一固定种子场景分别以 L0/L1 和 L2 推进若干游戏日，比较：

- 总输入、输出和损耗；
- 每个主体的资产归属；
- 饥饿、伤亡和违约方向；
- 订单完成与延迟；
- 允许随机波动，但不得出现无来源资源或相反的制度结果。

### 15.5 场景验收

首个可重复端到端验收场景固定为 `grain_road_lab`（断粮商路实验场）。它用
`baseline`、`shortage`、`blockade` 和 `recovery` 四个档位，在同一空间与主体上
分别验证正常供给、局部短缺、商路袭击和真实交付后的恢复。

场景不规定玩家必须先找哪名 NPC，也不把对话写成答案脚本。农民、商人、守卫、
粮仓执事和玩家从各自的观察、记录、证词与授权出发；物品、交易、运输、战斗和
救济必须共享同一组后台事实。详细夹具、知识矩阵、自动断言和真人指标见
[游戏内纵向验收设计](./05-gameplay-validation.md)。

该场景通过前，不扩展大规模政治战争或复杂币制。

### 15.6 模型用途与验证档案

每个模拟子模型提交时附带 `ModelCard`：

```java
record SimulationModelCard(
    String modelId,
    String purpose,              // explanation, gameplay generation, approximation
    String scope,
    List<String> assumptions,
    List<SourceNote> evidence,
    List<ParameterRange> parameters,
    List<ValidationScenario> validationScenarios,
    List<String> knownLimitations
) {}
```

验证分为：

- **程序验证**：代码是否忠实实现设计和守恒规则；
- **理论验证**：规则是否与采用的社会/经济理论相符；
- **行为验证**：是否生成目标模式、失败模式和边界结果；
- **角色体验验证**：玩家能否从界面理解信息来源、权利和后果；
- **跨实现对接**：细粒度与聚合模型在固定场景下是否方向一致。

不得用单次“看起来合理”的运行截图代替验证，也不得把缺乏历史资料的参数描述为精确历史事实。

## 16. 监控与调试

管理员工具需要显示普通玩家看不到的：

- 真值与指定人物信念的对照；
- 权利解析链；
- 物品转移审计；
- 交易提案和预留；
- AI 候选、主观输入和选择；
- LOD、调度积压与对账错误；
- 存档迁移前后清单。

调试工具必须有显式权限，数据包与普通交互协议分离，避免调试便利变成正式架构依赖。
统一面板、三栏事实对照、只读快照、差异与报告导出遵循
[测试者观察与后台真值面板](./06-tester-observability.md)。

## 17. 完成定义

一个 v2 子系统只有同时满足以下条件才算完成：

- 数据归属和生命周期明确；
- 命令与查询边界明确；
- 权限在服务端验证；
- 产生可审计事件；
- 不变量有自动化测试；
- 多人并发和断线情形已覆盖；
- 存档升级和回滚策略明确；
- UI 不泄露后台真值；
- 在至少两个 LOD 层级下行为一致或有明确限制。
