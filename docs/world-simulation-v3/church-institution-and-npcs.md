# 世界模拟 v3：教会、宗教机构与 NPC 模块

> 状态：实现前设计基线。本文只规定领域边界、数据与验收标准，不修改 Java。
>
> 目标：让宗教机构成为有人员、地点、财产、日程、记录、服务、利益和冲突的社会主体，而不是提供增益的按钮、凭空发放物品的商店，或披着教会皮肤的单一阵营 NPC。

## 1. 结论

首版宗教模块采用“世界阵营—地方机构—人物职位—真实资产”四层模型：

1. `sagadyssey:church` 继续表示玩家可能听说的广域政治与文化阵营；
2. 每一座实际教堂或修院对应独立的 `InstitutionKind.TEMPLE` 机构，拥有自己的 UUID、章程、成员、职位、账簿、仓库和地方关系；
3. 神职人员、执事、修士/修女、抄写员/教员是人物所承担的职位或岗位，不是新的生物种类，也不自动抹去人物的家庭、出身、职业技能和个人意见；
4. 仪式、慈善、教学、登记、调解和政治影响都由人物、时间、地点、知识、物资与授权共同产生；
5. 本模块不引入“信仰点”“点击祈祷获得属性”“捐绿宝石立刻洗声望”之类抽象兑换。

因此可以先实现一个完整纵向切片：雾月小教堂被登记为地方宗教机构，司祭主持公共仪式，执事管理济贫仓，修士承担共同生活与照料，抄写员教学并保管婚丧文书；物资短缺、传言、派系压力和账目滥用都能改变其行为。

## 2. 现有代码审计与兼容边界

### 2.1 可以直接复用的基础

| 现有能力 | 当前代码/资源 | 本模块的用法 |
|---|---|---|
| 广域教会阵营 | `data/sagadyssey/sagadyssey/faction/church.json` | 只承担广域外交投影、名称与玩家对该阵营的已知声望，不等同于某一地方教堂 |
| 阵营关系 | `data/sagadyssey/faction_relations.json` | 保留王国—教会联盟、教会—秘法不信任等世界初值；地方机构关系允许偏离 |
| 宗教机构类型 | `social.institution.InstitutionKind.TEMPLE` | 每个地方教堂/修院建立独立 `InstitutionRecord` |
| 职位与狭义职权 | `InstitutionOffice`、`OfficeAuthority`、`JurisdictionClaim` | 任命、财库管理、处分成员、调解与地方管辖必须有可审计授权 |
| 成员资格 | `InstitutionMembership` | 记录本地机构成员身份；不能用一个阵营 ID 代替全部社会关系 |
| 真实财产权 | `worldsim.rights.*` | 祭器、济贫粮、账簿、个人财物和公共用品分别授权 |
| 原子物品转移 | `worldsim.transfer.*` | 捐赠、发放救济、归还借书、支付纸张等必须产生收据且守恒 |
| 主观知识 | `knowledge.BeliefRecord`、`KnowledgeStateData` | 讲道、公告、文书、流言、见证与私密谈话分别保留来源和披露级别 |
| 制度与司法 | `social.justice.*` | 账目争议、财产争议、职位滥用和文书真伪走证据与裁决，不直接改数值 |
| 建筑与聚落索引 | `BuildingRecord`、`SettlementRegistryData` | 用真实生成建筑的位置和所属聚落建立机构，建筑自身不生成虚拟库存 |
| 世界历史 | `WorldHistoryData` | 只收录任命、重大仪式、婚丧、丑闻和制度冲突等摘要 |
| 日程与生活 | `NpcSchedulePlanner`、`NpcLifeData` | 新计划器接入工作、个人、休息与紧急需求，不再另开互相争抢的 Goal |

### 2.2 当前兼容入口

现有 `chapel_misty_moon` 已具备以下入口：

- 结构配置类别为 `church`；
- `SettlementRegistryData` 会把独立的教堂锚点登记为 `SANCTUARY`，默认阵营为 `sagadyssey:church`；
- 固定测试世界已有“雾月圣所”、`chapel_misty_moon`、圣所济贫仓和教士共同户；
- 固定场景暂时用 `BARD + HIGH_RULER` 表示“雾月司祭”，用 `MEDIC + SWORN` 表示“圣所医师”。

这些数据不删除，但需要迁移语义：

- `BARD` 不能继续作为司祭的真实职业含义，只在兼容期作为显示/能力占位；
- `NpcOffice.HIGH_RULER` 只是旧的粗粒度身份投影，真正的宗教职位必须进入 `InstitutionOffice`；
- 教士共同户与教会机构是两个主体：共同户承担居住、饮食与日常照料，机构承担职位、服务、财库和公共责任；
- 圣所济贫仓必须绑定实际容器、权利与机构用途，不能只是固定场景里的一组统计物品。

### 2.3 必须避免的双重真值

当前存在 `FactionRelationMatrix`、`PoliticalStateData` 和 `InstitutionRelation` 三种关系表达。v3 必须规定权威边界：

| 层级 | 权威数据 | 含义 |
|---|---|---|
| 广域阵营初值与玩家已知投影 | `FactionRegistry/FactionRelationMatrix` | 数据包定义的公开世界印象，不保存地方事件全过程 |
| 世界政治现状 | `PoliticalStateData` | 王国、教会等宏观势力当前的联盟、紧张、战争与正当性 |
| 地方组织间关系 | `InstitutionData` | 某教堂与某领主家政机构、行会、议会的具体协定或管辖冲突 |

地方教堂与领主发生什一税争议，不应立刻把整个 `church ↔ kingdom` 改成战争。地方事件先改变机构关系、人物记忆和区域舆论；只有累积到明确阈值并产生政治事件后，才由世界政治服务修改宏观关系。

## 3. 不可破坏的不变量

1. **宗教不是魔法资源。** 不设全局“信仰点”；仪式不会凭空给予攻击、防御、丰收或治疗效果。
2. **人物不是职位。** 司祭死亡、辞职或被撤职后机构仍可存在，职位会空缺并等待合法任命。
3. **建筑不是机构。** 教堂可以被废弃、争夺或改作他用；机构也可以暂借民居举行仪式。
4. **阵营不是地方教堂。** 同属教会阵营的两个机构可拥有不同章程、财富、声誉和地方关系。
5. **成员资格不是服从所有权。** 宗教机构不拥有成员身体与全部财产，只拥有章程和契约明确授予的权利。
6. **服务需要成本。** 慈善要消耗真实食物，抄写要消耗纸墨，照明和做饭要消耗燃料，工作人员要进食和休息。
7. **资产必须守恒。** 捐赠、施舍、采购、借阅和侵占都必须经过 `TransferService`；失败不得吞物或复制物。
8. **文书不是世界真相。** 婚姻、死亡、捐赠或债务记录是带签署人和来源的证据，可以缺失、过时、争议或伪造。
9. **知识属于知情者。** 司祭不会自动知道全镇秘密；私密陈述不得进入公共流言池。
10. **仪式占用真实时间与地点。** 无主持者、无可用场地、主持者正在逃命时，仪式必须延期或取消。
11. **权力必须来自职位。** 管理财库、查看密档、主持登记、处罚成员、代表机构谈判分别需要狭义职权。
12. **玩家信息受认识限制。** 玩家只能看到公开信息、亲眼观察、获准查阅或由他人告知的内容；测试模式除外。
13. **静止必须可解释。** 宗教 NPC 若长时间站立，后台必须显示正在等候参与者、缺少材料、守候病人、值守或计划到期时间。
14. **LOD 不得改变结算规则。** 近景动画和远景批次的物资净变化、责任主体与证据结果必须可对账。

## 4. 世界对象与分层

### 4.1 五个不同对象

| 对象 | 示例 | 持久标识 | 说明 |
|---|---|---|---|
| 宗教传统 `ReligiousTraditionDefinition` | 雾月传统 | `ResourceLocation` | 数据包定义的礼仪历、习俗倾向、职位模板；不是“神是否真实”的后台答案 |
| 广域阵营 `Faction` | `sagadyssey:church` | `ResourceLocation` | 玩家对大势力的政治认识和声望投影 |
| 地方机构 `ReligiousInstitutionRecord` | 雾月圣所教团 | UUID | 有章程、职位、成员、财库、辖区和独立历史 |
| 建筑 `BuildingRecord` | 雾月小教堂 | building key | 物理场地与兴趣点集合，可损坏、失去或更换控制者 |
| 人物 `CharacterRecord` | 雾月司祭艾琳 | UUID | 可同时属于家庭、机构、阵营、契约和关系网 |

### 4.2 宗教身份不简化为单一布尔值

首版不模拟完整内心信念哲学，但至少区分：

- `institution membership`：是否为某地方机构成员；
- `office`：是否持有可撤销的宗教职位；
- `attendance memory`：是否参加过某次公开仪式；
- `declared affiliation`：人物对外自述的归属；
- `relationship`：人物对具体神职人员或机构的信任、尊重、恐惧、恩惠与怨恨；
- `belief records`：人物听说的教义、丑闻、承诺与事件。

不得仅凭“同阵营”推断一个人虔诚、诚实、服从或愿意捐赠。

## 5. 首批 NPC 角色

首批不新增实体类型，仍使用 `NpcBase + CharacterId`。职业技能、机构职位和生活身份分开保存。

### 5.1 司祭/随军或地方神职人员 `CLERGY_OFFICIANT`

职责：

- 准备并主持公开仪式；
- 接受公开请求并安排婚丧或纪念礼；
- 提供非魔法性的安慰、调解和见证；
- 在获授权时签署宗教文书、代表机构发言；
- 监督执事但不自动拥有全部财库权限。

典型输入：仪式用品、食物、可用场地、参与者名单、登记申请。

典型输出：仪式事件、见证记录、公开讲话、私人记忆和关系变化；不输出免费状态增益。

不应承担：全天站在讲坛循环挥手；凭职业自动治伤；个人收走所有捐赠。

### 5.2 执事/机构管事 `DEACON_STEWARD`

职责：

- 管理济贫仓、日常采购、公共餐食与预约；
- 登记捐赠用途、发放救济并保存收据；
- 维护机构日程、场地和用品；
- 向有权限的职位提交账目摘要。

执事是首版经济闭环的核心。财库管理权、济贫物发放权和成员处分权必须分别授予，不能因为叫“执事”就全开。

### 5.3 修士/修女 `COMMUNITY_RELIGIOUS`

职责由地方章程选择，不固定成万能职业：

- 共同祈祷和场地维护；
- 烹饪、农作、照料访客或病弱者；
- 参与抄写、教学、接待与济贫；
- 执行共同户中的饮食、休息和家务。

修士/修女可共享机构成员身份，但私人随身物和机构财产仍分开。首版不引入性别专属能力或强制行为差异。

### 5.4 抄写员/教员 `SCRIBE_TEACHER`

职责：

- 保管公开文书和受限文书；
- 在真实纸、墨、书和工作时间充足时抄写；
- 有学生和场地时开课；
- 记录婚礼、死亡、职位任命、捐赠与重要调解；
- 根据查阅权限提供副本或口头摘要。

教学不会直接兑换旧科技点。它生成学习进度、已知事实、技能经验或资格证据，具体技能系统另行接入。

### 5.5 可复用而不新增专属职业的人员

- `MEDIC` 可与宗教机构建立服务关系，成为圣所医师，但医术来自职业技能而非神职；
- `WORKER/FARMER/COURIER` 可受机构雇佣，负责修缮、耕作和运输，不需要变成修士；
- `BARD` 可作为唱诗者、节庆表演者或访客，但不得继续充当司祭的永久代理类型；
- 守卫来自民兵、领主或合同。神职机构没有凭空生成武装的权限。

### 5.6 首发明确暂缓

- 魔法祝福、驱魔、复活、神罚和按键治疗；
- 宗教战争、审判官、圣骑士军团和大规模十字军系统；
- 全大陆教阶、选举、分裂、教派神学树；
- 可刷取的赎罪券或“捐赠换声望”固定配方；
- 自动婚配和人口出生。首版只承接已有双方关系/家庭决定并主持、见证与登记；
- 尸体搬运与完整墓园管理。首版死亡后可举行纪念/葬礼事件并登记，不伪装成已经实现尸体系统；
- 大规模朝圣队伍和远距离学校网络；
- 让教员替代旧研究树。旧科技点退役后，本模块也不得以宗教名义恢复同类抽象门槛。

## 6. 地方机构结构

### 6.1 机构组成

每个地方宗教机构至少包含：

- 基础 `InstitutionRecord(kind=TEMPLE)`；
- `ReligiousInstitutionRecord` 扩展；
- 一个主要建筑 key，可有附属住宅、田地、墓园、教室和仓库；
- 独立财库容器与济贫容器；
- 章程、传统、地方管辖与对外关系；
- 职位、成员、服务合同和共同户关联；
- 公开活动日历、财务摘要和记录目录；
- 当前运营状态：`ACTIVE / IMPAIRED / VACANT / DISPUTED / DISSOLVED`。

### 6.2 职位与职权

现有 `OfficeAuthority` 可继续承担通用权力，但宗教模块需要新增狭义职权：

```text
PERFORM_PUBLIC_RITE
CERTIFY_VITAL_RECORD
MANAGE_ALMS
MANAGE_LIBRARY
TEACH_PUBLICLY
ACCESS_RESTRICTED_RECORDS
PUBLISH_PUBLIC_NOTICE
RECEIVE_CONFIDENTIAL_STATEMENT
REPRESENT_INSTITUTION
AUDIT_ACCOUNTS
```

其中：

- `MANAGE_TREASURY` 不隐含 `MANAGE_ALMS`；
- `PERFORM_PUBLIC_RITE` 不隐含民事裁判权；
- `RECEIVE_CONFIDENTIAL_STATEMENT` 不允许传播内容；
- `CERTIFY_VITAL_RECORD` 只能证明举行/登记行为，不能强制覆盖家庭或政治系统的真值；
- `SANCTION_MEMBER` 只影响机构成员资格，不能扣押私人财物，除非另有裁决与权利依据。

### 6.3 地方管辖

教会可能主张的首批辖区：

- 本机构成员纪律；
- 仪式与档案标准；
- 济贫资源的分配规则；
- 教堂建筑内部秩序；
- 婚丧文书的见证资格；
- 与地方议会共同承担的调解。

税收、刑罚、土地所有权和军队指挥不是宗教机构的默认权限。若某世界剧本确实授予，必须建立 `JurisdictionClaim`，允许领主、议会或其他机构提出冲突主张。

## 7. 财产、账簿与经济闭环

### 7.1 容器分工

一座首版教堂至少区分以下资产域：

| 容器/账目 | 控制主体 | 允许内容 | 默认披露 |
|---|---|---|---|
| 财库 `treasury` | 机构；财库职员可管理 | 绿宝石、贵重物、支付凭据 | 只公布汇总，不公开精确槽位 |
| 济贫仓 `alms_store` | 机构；执事发放 | 面包、谷物、衣物、基础药材 | 公布是否短缺和发放规则 |
| 公共厨房 `communal_kitchen` | 机构或教士共同户 | 食物、燃料、炊具 | 成员/服务人员可用 |
| 礼仪用品柜 `rite_store` | 机构 | 蜡烛、书、装饰、耗材 | 普通玩家只看到允许外露的物品 |
| 图书与档案 `library/archive` | 机构 | 书、纸、墨、公开与受限文书 | 按记录级别与职位授权 |
| 私人物品栏 | 人物 | 个人衣物、食物、工具、礼物 | 仍受人物自己的披露和权利控制 |

禁止使用“教会共享背包”。每个容器通过 `RightsStateData` 建立控制者、受益者、访问策略与细粒度授权。

### 7.2 收入

可用收入必须有实际来源：

- 自愿捐赠；
- 明确服务费用或习惯性礼物，但可被拒绝、减免或争议；
- 机构土地与作坊的真实产出；
- 王国、领主、商人或家庭的合同拨款；
- 房租、借阅押金或教育费用；
- 其他机构按条约支付的款项。

首版不使用固定“每日 +N 绿宝石”。远景结算也只能消费已登记资产或已确认债权。

### 7.3 支出

- 工作人员口粮、工资或共同生活供给；
- 蜡烛、燃料、纸、墨、书和维修材料；
- 济贫食物与衣物；
- 教学和医疗合同费用；
- 向聚落、领主或上级机构支付的合法义务；
- 运输和护卫成本；
- 债务与赔偿。

### 7.4 捐赠状态机

```text
OFFERED
  -> INTENT_CONFIRMED
  -> RIGHTS_VALIDATED
  -> CAPACITY_RESERVED
  -> TRANSFERRED
  -> RECEIPT_RECORDED
  -> ALLOCATED(general | alms | repair | education)
```

任一步失败都必须回滚预留。捐赠只改变该机构的资产、收据、相关人物认识和地方声誉事件；广域阵营声望只能经事件聚合缓慢影响，不能固定 `+5`。

指定用途捐赠采用“用途约束账目”：实物仍在真实容器中，但账簿记录有多少价值/批次被承诺给济贫、维修或教学。挪用指定用途资产会产生可审计违规事件。

### 7.5 慈善状态机

```text
REQUESTED -> REVIEWING -> APPROVED -> RESERVED -> ISSUED -> FOLLOW_UP -> CLOSED
                         \-> WAITING_STOCK
             \-> DENIED / REFERRED
```

审核依据是执事已知的信息，而不是后台全知：家庭成员数、近期领粮、饥饿观察、见证、仓储余量和章程优先级。错误拒绝、重复领取、偏袒亲属和冒领都可能发生，并通过证据和审计纠正。

发放必须从 `alms_store` 真实转移到领取者或家庭容器，使用 `ISSUE` 意图并保存 `TransferReceipt`。库存不足时建立采购/物流请求，NPC 改做可行工作，而不是继续空发动画。

## 8. 日程、计划与空间行为

### 8.1 与统一计划器的关系

宗教 NPC 不注册一组永远并行抢占的专属 Goal。宗教模块只提供候选计划，由世界统一计划器选择；实体 Goal 仅执行已承诺的计划步骤。

优先级建议：

1. 逃生、战斗、自救；
2. 紧急照料与严重生理需求；
3. 已开始且不可随意中断的公开仪式、临终陪伴或交接；
4. 有预约和参与者的婚丧、教学、救济窗口；
5. 机构职责与物资请求；
6. 家庭责任、进食与休息；
7. 社交、阅读、散步、个人祈祷与其他自由活动。

计划承诺必须使用具体 `planId`，例如 `rite:uuid`、`charity:uuid`、`lesson:uuid`，不能只锁定粗粒度的 `WORK`。

### 8.2 首版日程样例

以下为默认模板，不是强制所有传统一致：

| 时间段 | 司祭 | 执事 | 修士/修女 | 抄写员/教员 |
|---|---|---|---|---|
| 清晨 | 个人准备、公共晨祷 | 开门、检查用品 | 共同活动、厨房/场地 | 整理书籍与课表 |
| 上午 | 接待、预约仪式 | 济贫窗口、采购 | 农作/照料/维修 | 教学或抄写 |
| 中午 | 进食、休息 | 进食、记账 | 进食、休息 | 进食、休息 |
| 下午 | 婚丧、调解、探访 | 配送救济、盘点 | 服务工作、学习 | 教学、查阅、登记 |
| 黄昏 | 公共仪式或公告 | 关库、汇总账目 | 共同活动 | 保存文书 |
| 夜间 | 回住处；紧急呼叫除外 | 回住处 | 回共同户 | 回住处 |

每七个游戏日可配置一个主要公共集会日；特殊事件可插入纪念日、灾后悼念或丰收感恩活动。日历只创建计划，不保证参与者都会出现。

### 8.3 空间兴趣点

教堂模板需要可寻路的语义点：

- `entrance`：开门、迎接和疏散；
- `officiant_spot`：主持位置；
- `assembly_slots[]`：参与者位置与容量；
- `alms_counter`：救济交接；
- `archive_desk`：登记与查阅；
- `teaching_area`：授课；
- `kitchen`、`residence_beds[]`、`storage_links[]`；
- `quiet_visit_slots[]`：安静交谈和等待，不用于读取私密内容。

不要通过扫描“像讲台的方块”猜语义。首版为现有 NBT 添加独立的空间清单 JSON；未来重新导出 NBT 时可使用 structure data marker，但清单仍是可验证的权威配置。

### 8.4 公共仪式状态机

```text
PLANNED -> PREPARING -> ASSEMBLING -> PERFORMING -> DISMISSING -> SETTLING -> COMPLETE
             |              |              |
             +----------> BLOCKED/CANCELLED <+
```

必要条件：主持授权、可用场地、最小用品、主持者能到达。参与者不足可以缩小规模，不必无限等待。执行结果包括：

- 参与者的观察记忆；
- 参与人物之间的熟悉度或共同经历；
- 一条公开事件或普通历史摘要；
- 实际用品消耗与工作时间；
- 若失败，产生“延期/取消及原因”的可传播信息。

仪式不得直接生成物品、治愈伤口、清除犯罪或修改全阵营关系。

### 8.5 无任务时的合理行为

岗位暂时无工作时，NPC 按以下顺序选择有限活动：

- 完成收尾：归还用品、写记录、释放位置和预留；
- 检查一个待办队列：预约、救济、采购、维修、教学；
- 在有期限的接待位置等待；
- 阅读、整理、打扫、进食、探访成员或回家；
- 若全部不可行，进入带原因和下次复查时间的 `BOUNDED_IDLE`。

任何静止超过 200 tick 的宗教 NPC，在测试视图必须显示计划、阻塞原因和 `reviewAt`。

## 9. 仪式、教育与社会服务

### 9.1 婚礼与婚姻记录

宗教机构不是婚姻状态的唯一创造者。流程为：

1. 两名人物或合法代理提出预约；
2. 系统检查双方人物存在、可表达同意、时间不冲突；
3. 机构依据章程决定是否愿意主持，拒绝也必须有明确理由；
4. 参与者、主持人和见证人到场；
5. 完成仪式后创建 `VitalRecord(type=MARRIAGE)` 与领域事件；
6. 家庭/关系服务根据双方确认更新其自身状态；
7. 文书作为 `DOCUMENT` 来源进入获准知情者的知识系统。

若家庭状态更新失败，仪式记录保持“已举行但民事/家庭登记待处理”，不得强行制造配偶关系。

### 9.2 葬礼与死亡记录

死亡事实来自人物生命周期系统。宗教机构可以：

- 接收死亡消息，但可能晚到或有误；
- 预约纪念/葬礼；
- 邀请家人、同事或地方代表；
- 消耗仪式用品并举行活动；
- 创建死亡/葬礼文书和人物记忆；
- 触发安慰、怨恨、债务或公共舆论变化。

首版没有尸体系统时，不得显示“已埋葬尸体”作为物理事实；记录可区分 `DEATH_REPORTED`、`FUNERAL_HELD`、`BURIAL_UNCONFIRMED`。

### 9.3 教学

课程需要教师、学生、地点、时间和材料。状态：

```text
SCHEDULED -> MATERIALS_RESERVED -> ASSEMBLING -> TEACHING -> ASSESSED -> COMPLETE
```

只有实际到场的学生获得学习进度。进度表示具体能力或知识主题，不是全局科技解锁。书本可借阅，必须有借阅记录、归还期限和实际物品转移。

### 9.4 抄写与档案

抄写是生产批次：消耗纸、墨与工作时间，输出有来源的书或文书副本。副本携带原记录 ID、抄写者、时间和修订号；错误抄写或伪造是可检测风险，不应把所有副本视作绝对真相。

记录访问级别：

- `PUBLIC_INDEX`：标题、日期、是否存在；
- `PUBLIC_TEXT`：全文可查；
- `PARTY_ONLY`：当事人和合法代理；
- `OFFICE_RESTRICTED`：指定职位；
- `CONFIDENTIAL`：除明确授权外不进入普通查询和流言。

## 10. 知识、谣言与保密

### 10.1 信息来源

| 行为 | `KnowledgeSourceKind` | 默认披露 |
|---|---|---|
| 亲眼参加仪式 | `OBSERVATION` | 参加事实可分享，私人动机未知 |
| 司祭公开公告 | `TESTIMONY` | `PUBLIC`，保留讲话者来源 |
| 机构档案副本 | `DOCUMENT` | 由记录访问级别决定 |
| 当事人陈述 | `SELF_REPORT` | 由当事人授权范围决定 |
| 从多个线索推断滥用 | `INFERENCE` | 置信度较低，不能当成已判决事实 |

### 10.2 传播规则

- 公开讲道只传播讲话内容，不自动使所有听众相信；每人根据信任、关系、既有信念和是否听清生成不同置信度；
- 公告板只被靠近、阅读或从读者处听说的人获知；
- 仪式参与名单不是默认公开个人档案；
- “有人从济贫仓偷粮”可以成为流言，但人物所说的嫌疑人不等于后台真凶；
- 私密谈话和受限记录默认 `PRIVATE`，普通社交传播器必须硬性拒绝；
- 测试者可以查看来源链，但普通玩家不能看到隐藏的真实性标签。

### 10.3 玩家发现地方机构

玩家发现 `sagadyssey:church` 阵营不等于知道所有教堂。地方机构进入玩家人物志需要至少满足一种：

- 看见并识别建筑标志；
- 与成员交谈并获知；
- 阅读公开文书/地图；
- 从可信传言得知；
- 与该机构发生交易、捐赠、救济或仪式关系。

## 11. 政治影响、派系关系与冲突

### 11.1 政治影响不是光环

宗教机构的政治影响来自可追踪行为：

- 成员数量与地方信任；
- 是否兑现济贫和调解承诺；
- 掌握的文书、见证与教育网络；
- 与领主、议会、行会的条约；
- 能否公开传递消息并组织参与者；
- 财产、债务和对他人的实际依赖。

机构可以支持领主的正当性，也可以因拒绝背书、揭露滥用或争夺管辖而削弱它。所有影响都经事件和人物关系传播，不直接用“教会建筑范围 +20 合法性”。

### 11.2 与现有阵营的默认关系

- **王国**：广域默认联盟，但地方上可因税费、任命、庇护权和财产发生冲突；
- **商会**：默认中立，围绕采购、贷款、运输和慈善价格形成合作或争议；
- **秘法势力**：默认互不信任，但不能据此让每个司祭自动敌视每个相关人物；个体知识与地方关系优先；
- **荒野群体**：默认友好只代表初始政治倾向，不保证彼此熟悉；
- **强盗**：广域敌对，教堂可能成为抢劫目标。`church.can_be_hostile=false` 表示机构不主动转为战斗阵营，不表示 NPC 不能逃跑、求援或正当防卫。

### 11.3 现实冲突模型

首批支持以下争议：

- 捐赠用途挪用或账目缺口；
- 济贫偏袒、拒绝、重复领取或冒领；
- 婚丧文书真伪与登记遗漏；
- 教堂财产、维修责任和使用时段争议；
- 机构任命是否合法；
- 领主、议会与教堂对费用、成员纪律或调解权的重叠主张；
- 教员歧视外来者或只教关系户；
- 公开讲话传播错误消息或为某政治方背书。

冲突流程：主张 → 证据 → 论坛机构 → 裁决 → 执行/拒绝执行 → 关系和舆论后果。没有足够证据时可以保持“有争议”，不能强迫系统给出道德全知答案。

### 11.4 滥权与防护

滥权不是随机减声望事件，而是正常权限被不当使用：

- 持有财库权的人把物品转入私人容器；
- 有档案访问权的人泄露私密记录；
- 有任命权的人偏袒亲属；
- 司祭以拒绝仪式换取个人财物；
- 执事伪造救济发放收据。

每种行为必须产生实际转移、访问或文书记录，留下不同强度的证据。审计只能发现其可见部分；未被发现的滥用不会神秘地让所有人同时知道。

## 12. 数据模型

以下为目标模型，字段名用于固定边界，不要求一次全部落地。

```java
record ReligiousInstitutionRecord(
    UUID institutionId,
    ResourceLocation traditionId,
    ResourceLocation projectedFactionId,
    String primaryBuildingKey,
    Set<String> attachedBuildingKeys,
    UUID clergyHouseholdId,
    InstitutionOperatingState state,
    int localLegitimacy,
    long revision
) {}

record ReligiousOfficeProfile(
    ResourceLocation id,
    Set<OfficeAuthority> requiredAuthorities,
    Set<String> dutyKinds,
    ScheduleTemplate schedule,
    Set<ResourceLocation> eligibleTraditions
) {}

record InstitutionAssetBinding(
    UUID institutionId,
    String purpose,
    UUID containerId,
    String buildingKey,
    String poiKey,
    long revision
) {}

record RitePlan(
    UUID id,
    UUID institutionId,
    ResourceLocation riteType,
    BlockPos venue,
    UUID officiantId,
    List<UUID> participantIds,
    Set<UUID> witnessIds,
    List<ItemRequirement> requirements,
    long scheduledAt,
    RiteStatus status,
    String blockedReason,
    long revision
) {}

record CharityCase(
    UUID id,
    UUID institutionId,
    SubjectRef requester,
    SubjectRef intendedRecipient,
    String needKind,
    int requestedAmount,
    List<EvidenceRef> evidence,
    CharityStatus status,
    UUID decidedBy,
    UUID transferReceiptId,
    long openedAt,
    long reviewAt,
    long revision
) {}

record VitalRecord(
    UUID id,
    UUID institutionId,
    VitalRecordType type,
    List<UUID> subjects,
    UUID officiantId,
    Set<UUID> witnesses,
    long occurredAt,
    RecordAccess access,
    RecordStatus status,
    List<EvidenceRef> supportingEvidence,
    long revision
) {}

record InstitutionLedgerEntry(
    UUID id,
    UUID institutionId,
    LedgerEntryType type,
    UUID transferReceiptId,
    String earmark,
    int appraisedValue,
    UUID recordedBy,
    long recordedAt,
    long revision
) {}

record LessonSession(
    UUID id,
    UUID institutionId,
    UUID teacherId,
    String subject,
    List<UUID> enrolled,
    List<UUID> attended,
    List<ItemRequirement> materials,
    long scheduledAt,
    SessionStatus status,
    long revision
) {}
```

### 12.1 数据拆分原则

- `InstitutionData` 继续保存通用组织、成员、职位、关系与管辖；
- `ReligiousInstitutionData` 只保存宗教扩展、日历、仪式、慈善、档案目录和账目索引；
- 实际物品仍在实体容器和 `TransferLedgerData`，宗教数据不得保存平行库存数量；
- 人物知识仍在 `KnowledgeStateData`，宗教数据不得保存“全镇都知道”；
- 宏观政治仍在 `PoliticalStateData`；
- 世界历史只保存摘要，不取代完整业务记录；
- 所有可变记录包含 `revision`，命令带预期修订号，防止双击、重放和多人并发覆盖。

## 13. 服务 API 与领域事件

### 13.1 建议包结构

```text
social/religion/
  definition/   ReligiousTraditionDefinition, ReligiousOfficeProfile
  institution/  ReligiousInstitutionData, ChapelBootstrapService
  rite/         RitePlan, RiteService, RiteScheduler
  charity/      CharityCase, CharityService
  record/       VitalRecord, RecordService, RecordAccessService
  education/    LessonSession, EducationService, CopyingWorkOrder
  economy/      InstitutionLedgerEntry, InstitutionAccountingService
  knowledge/    ReligiousKnowledgeService
  audit/        InstitutionAuditService, ReligionAuditCommand
```

### 13.2 命令服务

```java
interface ChapelBootstrapService {
    BootstrapResult ensureInstitution(BuildingRecord chapel, long now);
}

interface RiteService {
    RitePlan schedule(ScheduleRiteCommand command);
    StepResult advance(UUID riteId, long now);
    RitePlan cancel(UUID riteId, SubjectRef actor, String reason, long now);
}

interface CharityService {
    CharityCase open(OpenCharityCaseCommand command);
    CharityDecision decide(UUID caseId, SubjectRef actor, Decision decision, long expectedRevision);
    TransferReceipt issue(UUID caseId, SubjectRef actor, Map<UUID, TransferEndpoint> endpoints);
}

interface RecordService {
    VitalRecord draft(DraftVitalRecordCommand command);
    VitalRecord certify(UUID recordId, SubjectRef actor, long expectedRevision);
    RecordProjection view(UUID recordId, SubjectRef observer);
}

interface InstitutionAccountingService {
    DonationReceipt receiveDonation(DonationCommand command);
    AuditReport audit(UUID institutionId, SubjectRef auditor, long from, long to);
}
```

所有写操作只在服务器主线程执行；客户端发送的是意图和会话版本，不发送结算结果、物品数组索引或“给我加声望”的请求。

### 13.3 领域事件

首批事件：

```text
ReligiousInstitutionEstablished
OfficeAppointed / OfficeRevoked / OfficeVacant
RiteScheduled / RiteStarted / RiteCompleted / RiteCancelled
DonationReceived / DonationEarmarked / DonationMisapplied
CharityRequested / CharityApproved / CharityIssued / CharityDenied
LessonHeld / ManuscriptCopied / RecordViewed
VitalRecordDrafted / VitalRecordCertified / VitalRecordContested
ConfidentialityBreached
InstitutionAuditCompleted
JurisdictionDisputed
PublicNoticeDelivered
```

事件用途包括关系、记忆、知识、政治聚合、历史摘要和测试审计。普通重复劳动按班次聚合；职位、财产、婚丧、争议和滥用长期保留。

## 14. 与 `sagadyssey:church` 和 `chapel_misty_moon` 的具体对接

### 14.1 建筑启动规则

当 `SettlementRegistryData` 登记 `structureId=chapel_misty_moon` 且 `category=church`：

1. 根据 `building.key + ":religious_institution"` 生成稳定 UUID；
2. 若该 UUID 已存在，返回现有机构，保证存档重载和重复结构回调幂等；
3. 若建筑已附着聚落，机构关联该 `settlementId`；否则沿用当前逻辑创建 `SANCTUARY`；
4. 建立 `InstitutionRecord(kind=TEMPLE)`，投影阵营设为 `sagadyssey:church`；
5. 读取 `chapel_misty_moon` 的机构空间清单，登记场地、容量和候选容器；
6. 只有找到并绑定真实容器后才建立财库/济贫仓权利上下文；缺失容器进入 `IMPAIRED`，不得生成虚拟箱子内容；
7. 将符合条件的现有 NPC 迁移为成员/职位；迁移不会改动物品或重复生成 NPC。

### 14.2 配套空间清单

建议新增数据包资源：

```text
data/sagadyssey/religious_institution_template/chapel_misty_moon.json
```

内容至少包括相对坐标、朝向变换和容量：

```json
{
  "structure": "sagadyssey:chapel_misty_moon",
  "tradition": "sagadyssey:misty_moon",
  "projected_faction": "sagadyssey:church",
  "points": {
    "entrance": [[0, 1, 0]],
    "officiant_spot": [[0, 1, 8]],
    "assembly_slots": [[-2, 1, 4], [0, 1, 4], [2, 1, 4]],
    "alms_counter": [[4, 1, 2]],
    "archive_desk": [[-4, 1, 6]],
    "teaching_area": [[-2, 1, -3]]
  },
  "containers": {
    "treasury": [[-4, 1, 8]],
    "alms_store": [[4, 1, 4]],
    "rite_store": [[2, 1, 8]],
    "archive": [[-4, 1, 7]]
  }
}
```

示例坐标只是格式说明，实施前必须用实际 NBT 校准，验证旋转和镜像。不能把示例直接当正确坐标。

### 14.3 固定场景迁移

- “雾月司祭”：保留人物 UUID、名字和随身物；创建本地 `InstitutionMembership` 与“主持司祭”职位；旧 `BARD/HIGH_RULER` 仅作为兼容投影，最终迁移到职业能力 + 机构职位；
- “圣所医师”：保留 `MEDIC`，建立服务合同或普通成员资格，不自动授予主持和财库权；
- “雾月教士共同户”：绑定机构 ID 作为关联共同户，不转成机构库存；
- “圣所济贫仓”：绑定 `alms_store`，原有面包和小麦作为真实初始资产；
- 现有广域阵营关系保持不变，地方机构从中获得初始关系模板但随后独立演化。

### 14.4 迁移兼容期

读取规则：优先读取宗教扩展；缺失时从建筑、旧身份字段和固定场景数据推导一次迁移。

写入规则：只写新模型；旧 `NpcOffice` 和职业显示字段由新模型派生，不双写业务真值。

迁移必须可中断、可重试，并提供报告：创建了哪些机构、绑定了哪些容器、迁移了哪些人物、哪些建筑因坐标或容器缺失而受损。

## 15. 玩家界面与测试者后端

### 15.1 普通玩家看到什么

对一座已发现教堂，界面显示叙述性信息：

- 名称、地点、公开传统与当前是否开放；
- 玩家已知的成员和公开职位；
- 今日公开活动、救济窗口、课程和公告；
- 玩家自己参与的预约、捐赠收据、借阅与文书；
- 公开账目摘要，例如“本周粮食紧张”，而非隐藏箱子的精确槽位；
- 玩家从观察/谈话/文件得知的争议，并注明来源和不确定性；
- 可执行的情境动作：询问、预约、捐赠、申请救济、查阅公开记录、提出争议。

不得显示：所有机构列表、未见 NPC 的精确名字、完整财库、私密记录、AI 分值、真正的贪污标记、全世界阵营关系和后台真相。

### 15.2 NPC 近身界面

仍遵守人物投影原则：

- 头部：玩家所知身份与公开职位；
- 当前观察：正在整理档案、主持仪式、等待救济物资等；
- 可交谈主题：根据双方共同知识、职位和关系生成；
- 可执行动作：只显示人物有权处理的服务；
- 若找错人，NPC 可转介到执事、司祭或抄写员，而不是每个人都打开同一个万能菜单。

### 15.3 测试模式必须显示

`/saga religion inspect <institution|npc|look>`：

- 机构 UUID、建筑 key、聚落、传统、阵营投影、运营状态；
- 成员、职位、职权来源、任命期限；
- 所有容器绑定、权利上下文、实际库存摘要与预留；
- 当前仪式/慈善/教学/抄写/登记计划及状态机步骤；
- 每名 NPC 当前 `planId`、候选分数、承诺截止、阻塞原因、下次复查；
- 账簿与转移收据对账差异；
- 公开、受限和私密知识的数量与来源链；
- 地方机构关系、管辖主张和宏观政治投影；
- 当前 LOD、最后推进时间、待对账项目；
- 最近领域事件和异常循环检测。

`/saga religion audit <institution>` 输出机器可比对摘要，不向普通客户端同步后台细节。

## 16. 性能与 LOD

### 16.1 分层预算

| 层级 | 条件 | 推进方式 |
|---|---|---|
| L0 实体级 | 玩家附近、正在互动/仪式 | 动作每 tick；传感 20–40 tick；计划复审 100–200 tick，按 UUID 错峰 |
| L1 区域级 | 已加载但无人关注 | 计划 400–1200 tick；仪式/教学按阶段跳转，省略逐帧动画 |
| L2 聚落级 | 区块未加载 | 每半日或每日推进预约、需求和已预留批次；不读取方块实体 |
| L3 世界级 | 长期远离 | 每周级制度事件、职位空缺和宏观义务；不完成没有资产快照支持的物品交易 |

### 16.2 事件驱动唤醒

以下事件可提前唤醒相关计划，而不是持续扫描：

- 仓库入货或预留失效；
- 预约时间临近；
- 参与者到场/离开；
- NPC 受伤、死亡、撤职；
- 建筑或容器损坏；
- 新慈善申请或公开争议；
- 政治关系发生重大改变。

### 16.3 降级与升级对账

降级前保存：人物语义位置、计划阶段、参与者、预留、容器修订号和未结算收据。

升级时：

- 确认人物仍存活且职位有效；
- 比对容器修订和预留；
- 将抽象完成结果落实为合法转移；
- 发现箱子被玩家搬空时转为 `BLOCKED/CONFLICT`，不得补发物品；
- 同一事件 ID 不得重复生成记忆、账目或声望影响。

## 17. 测试计划

### 17.1 单元与属性测试

1. 同一 `BuildingRecord` 重复启动不会创建第二机构、第二职位或第二初始库存；
2. 无 `MANAGE_ALMS` 的司祭不能发放济贫仓物品；
3. 财库有空间检查失败时捐赠不扣玩家物品；
4. 同一捐赠命令重放只产生一张收据和一次转移；
5. 私密记录不会被普通知识传播器选中；
6. 撤职后旧 office ID 不能继续授权；
7. 仪式取消会释放全部场地和物资预留；
8. 慈善发放前后所有相关容器的物品总量守恒；
9. 宗教登记不能越权修改家庭/死亡真值；
10. L0 与 L2 在相同初始快照和随机种子下产生相同净资产与业务状态。

### 17.2 场景 A：小教堂正常一日

**给定**：司祭、执事、修士、抄写员均在职；场地可达；仪式用品与食物充足。

**过程**：

1. 清晨各 NPC 从住处前往各自空间点；
2. 司祭准备仪式，参与者按容量到场；
3. 仪式完成后所有人散场，场地预留释放；
4. 执事开放济贫窗口并发放一份真实面包；
5. 抄写员为实际到场学生授课；
6. 晚间人员完成收尾并返回住处。

**验收**：任何 NPC 均不无限重复动作；物资减少准确；缺席者不获得参与记忆；后台能解释每次计划切换。

### 17.3 场景 B：济贫仓缺粮

**给定**：三户申请面包，济贫仓只剩两份。

**过程**：执事依据章程和已知证据审批，预留两份，第三案进入 `WAITING_STOCK`，建立采购/运输请求。

**验收**：不凭空生成第三份；被拒/等待者产生与其所知相符的关系和传言；补货到达后案件被事件唤醒。

### 17.4 场景 C：捐赠与指定用途

**给定**：玩家捐赠 16 小麦并指定济贫。

**过程**：确认意图、检查权利与容量、完成转移、生成收据和用途约束。

**验收**：普通玩家能看到自己的收据；不能看到完整财库；若执事把小麦转入私人箱子，审计可从转移链发现异常，但未见此事的 NPC 不会自动知道。

### 17.5 场景 D：婚礼登记失败恢复

**给定**：双方同意且仪式完成，但家庭服务在更新时因版本冲突失败。

**验收**：仪式不会重复；记录状态为“已举行、关系登记待处理”；重试使用同一事件 ID；不得生成两份婚姻或重复消耗用品。

### 17.6 场景 E：死亡消息与葬礼

**给定**：一名居民死亡，消息经过两名见证人传到教堂。

**验收**：司祭只知道来源为证词，安排葬礼；参加者形成记忆；记录区分死亡报告和已举行葬礼；没有尸体系统时不声称已完成物理埋葬。

### 17.7 场景 F：地方政治冲突

**给定**：领主要求动用济贫粮支付驻军，执事拒绝，双方都提出管辖依据。

**验收**：形成地方 `JURISDICTION_DISPUTE` 和证据；不立即改写全世界王国—教会关系；玩家只在获知后看到争议。

### 17.8 场景 G：私密信息防泄漏

**给定**：NPC 向有授权的司祭作私密陈述。

**验收**：记录为 `PRIVATE/CONFIDENTIAL`；普通交谈、公告和人物志均不显示；测试界面能看到访问审计但默认隐藏正文；若有人越权访问才产生泄密事件。

### 17.9 场景 H：存档、卸载与破坏

**给定**：仪式准备中退出游戏，期间教堂区块卸载；重载后玩家搬走用品箱内容。

**验收**：重载不重复扣物；升级对账发现修订冲突，仪式进入阻塞并解释“预留用品已不存在”；NPC 改做其他可行活动。

### 17.10 长稳测试

运行至少 14 个游戏日，记录：

- 每个 NPC 的计划类型时间占比；
- `BOUNDED_IDLE` 平均时长与原因；
- 重复事件/重复收据数；
- 转移守恒差异；
- 预约按时率与失败原因；
- 慈善积压与库存短缺；
- 知识泄漏计数；
- LOD 升降级对账失败；
- 主线程每 tick 开销和最坏峰值。

硬性通过标准：重复结算为 0、物品守恒差异为 0、私密知识泄漏为 0、无法解释的静止为 0。

## 18. 实施阶段

### C0：冻结旧捷径与建立迁移基线

- 禁止继续把 `BARD/HIGH_RULER` 当作宗教系统规则来源；
- 禁止新增“交物品直接加教会声望”的硬编码；
- 为固定场景和 `chapel_misty_moon` 建立迁移前快照；
- 明确旧科技点系统退役后，本模块不读取其点数或解锁状态；
- 建立机构启动、转移和知识隐私测试基座。

### C1：地方机构、空间清单与首批职位

- 实现 `ReligiousInstitutionData` 与幂等 `ChapelBootstrapService`；
- 为 NBT 校准空间清单；
- 绑定真实财库、济贫仓、档案柜和场地；
- 迁移固定场景人物、共同户和济贫仓；
- 新增狭义宗教职权与职位配置。

完成定义：进入固定场景后只生成一个地方机构，测试命令能解释所有绑定和迁移结果。

### C2：统一计划器接入与公共仪式

- 宗教候选计划接入统一计划生命周期；
- 完成准备、集合、主持、散场、收尾状态机；
- 实现空间容量和预留；
- 为无任务与受阻状态提供有期限替代行为。

完成定义：连续三个仪式无循环挥手、无永久站立、无参与者堆叠。

### C3：真实捐赠、济贫和账目

- 接入 Rights/Transfer/Logistics；
- 实现指定用途捐赠、慈善案件、库存短缺与补给请求；
- 添加账目审计和基础滥用检测；
- 普通玩家只看到投影后的摘要与自己的收据。

完成定义：物资全链路守恒，断货、箱满、重放和多人同时操作均安全。

### C4：教学、抄写、婚丧与知识传播

- 教学和抄写批次；
- 文书目录、访问级别和副本来源；
- 婚礼、死亡报告与葬礼记录；
- 公告、见证、传言和私密信息硬隔离。

完成定义：普通玩家不能通过 UI/网络包获知未授权记录；测试者可追溯完整来源链。

### C5：地方政治、管辖冲突与滥权

- 机构关系和管辖主张；
- 地方事件到宏观政治的聚合门槛；
- 挪用、偏袒、泄密、伪造等证据链；
- 裁决、补偿、撤职和未执行后果。

完成定义：地方争议不会直接污染全阵营真值，且所有制裁有职位/裁决依据。

### C6：LOD、可观测性与完整验收

- L0–L3 推进、事件唤醒、快照和对账；
- 玩家教堂界面、NPC 情境动作和测试者后端；
- 完成全部场景、14 日长稳和多人并发测试；
- 修订文档与数据包示例，使实现和设计一致。

## 19. 完成定义

宗教模块只有同时满足以下条件才算完成首版：

- 一座实际教堂能幂等建立一个地方机构；
- 四类首批角色有不同授权、日程、工作输入输出和合理空闲行为；
- 仪式、慈善、教育、抄写、婚丧至少各有一个可完成和一个可解释失败流程；
- 捐赠与救济完全基于真实物资、权利、容量、收据和预留；
- 阵营、机构、建筑、职位、家庭和人物没有混成一个对象；
- 地方关系与宏观政治不会互相覆盖真值；
- 玩家只看到其知识允许的宗教世界，测试者能看到完整可审计状态；
- 私密信息不泄漏，物品不复制，重载不重复结算；
- NPC 工作结束后会收尾、选取下一计划或进入有原因的等待，不再无解释站立；
- 没有任何功能依赖旧科技点、抽象信仰点或魔法按钮。
