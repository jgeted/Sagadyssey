# 世界模拟 v2：研究依据与设计推论

本文件记录 v2 采用的外部理论依据。目标不是把学术概念原样搬进游戏，而是利用它们纠正容易失真的抽象。

## 1. 研究方法

资料选择遵循三条原则：

1. 优先原始论文、诺贝尔演讲、大学出版社和学术机构页面；
2. 每条资料必须导出具体机制，不作为装饰性引用；
3. 历史材料用于说明社会结构可能如何工作，不把某个地区或世纪误当成全部中世纪。

## 2. 社会身份不是单一金字塔

剑桥的晚期中世纪英格兰社会史导论指出，社会不能只按一个阶级或等级轴理解；同一个人同时处于阶级、身份群体、秩序和性别等多种位置中。[Cambridge University Press](https://www.cambridge.org/core/books/abs/social-history-of-england-12001500/introduction-social-structure-and-economic-change-in-late-medieval-england/6C38D587661482DB5E43859228AEA06E)

设计推论：

- 不用一个 `socialClass` 决定称呼、权限、职业和人格；
- 出身、职业、职位、家庭角色、法律状态和制度成员资格分别记录；
- 权威必须带语境：作坊大师的权威不能自动扩展到军队或家庭；
- NPC UI 应呈现玩家已经获知的具体身份，而不是后台统一标签树。

## 3. 家庭是生产与消费单位，也不只包含亲属

Roberta Gilchrist 对中世纪家庭的研究把 household 描述为居住、生产、消费及社会再生产的中心，并提醒家庭可能包含非亲属的仆役等成员，现代公私领域的划分也不能直接套用。[Cambridge University Press](https://www.cambridge.org/core/books/abs/medieval-life/medieval-household-the-material-culture-of-everyday-life/A0DDFC581B0EB33A6CC9526567CD51DA)

设计推论：

- 家庭不是亲属列表，也不是共享箱子的别名；
- 家庭成员可包括学徒、仆役、寄居者和受抚养者；
- 家庭有共同预算和生产安排，但仍存在个人财产；
- 食物短缺应首先落到具体家庭的取得与分配，而不是只修改聚落总值。

## 4. 财产是一束可分离的权利

Schlager 与 Ostrom 对自然资源制度的分析区分不同的权利束，并据此区分 authorized user、claimant、proprietor 和 owner。[JSTOR 页面](https://www.jstor.org/stable/3146375) · [论文 PDF](https://faculty.econ.ucsb.edu/~tedb/Courses/Ec100C/Readings/OstromSchlager.pdf)

设计推论：

- `ownerUUID` 不足以表达真实财产关系；
- 必须区分进入/查看、使用、消耗、管理、排除、收益和转让；
- 运输货物、雇主配发装备、家庭储粮和寄售商品可以由不同人持有、使用和拥有；
- 偷窃改变占有，不应自动洗白所有权；
- 玩家看到 NPC 的物品，不代表有权拿走。

## 5. 经济行为嵌在社会关系中

Granovetter 的“嵌入性”论述强调，经济行动嵌在持续的社会关系网络中；只把人看成孤立的效用计算者，或只看成完全受规范支配，都遗漏了实际关系网络。[Stanford Sociology](https://sociology.stanford.edu/publications/economic-action-and-social-structure-problem-embeddedness)

设计推论：

- 信任、亲属、声誉、债务与制度资格会改变交易条件；
- 赠礼、互惠、工资、税和现货买卖不能共用一个“报价配方”模型；
- 商人价值来自网络、信息、运输和信用，而不是职业生成库存；
- 欠薪既是经济债务，也是关系和声誉事件。

关于赠礼与市场之间的连续谱，可参考“economy of regard”对赠礼、互惠和市场混合形式的讨论。[Oxford Research Archive](https://ora.ox.ac.uk/objects/uuid%3A77321e56-1583-4253-ac04-9d011cab2c4b)

设计推论：

- 赠礼不要求即时等价，却可能产生感激、义务或怀疑；
- UI 必须让“赠礼”“付款”“借用”“交付”具有不同语义；
- 单纯移动 ItemStack 不能完整表达社会后果。

## 6. 知识分散，价格只是信号

Hayek 在《The Use of Knowledge in Society》中把经济问题描述为分散知识的利用问题，并说明价格系统如何压缩、传递局部变化的信息。[American Economic Association PDF](https://www.aeaweb.org/aer/top20/35.4.519-530.pdf)

设计推论：

- 不设所有 NPC 都能读取的全局准确市场价；
- 人物只知道自己观察到的库存、成交、传闻和路线情况；
- 本地价格是近期公开交易的信号，允许陈旧、偏差和操纵；
- 远方短缺必须通过旅人、账簿、信件或真实货流传播后才影响决策。

## 7. 人受限于认知与计算能力

Herbert Simon 的有限理性研究强调，人和组织需要在知识、注意力和计算能力有限的情况下作出选择。[Nobel Prize lecture](https://www.nobelprize.org/prizes/economic-sciences/1978/simon/lecture/) · [讲稿 PDF](https://www.nobelprize.org/uploads/2018/06/simon-lecture.pdf)

设计推论：

- NPC 不搜索全世界的最优食物、工作或交易；
- 候选行动来自当前可见机会、日程、消息和记忆；
- 采用满意化、带噪声的选择，并形成短期承诺；
- NPC 会犯由错误信息或时间压力造成、但可以解释的错误。

Douglass North 进一步强调，需要区分现实世界与行动者对世界的理解，制度、心智模型和学习共同塑造选择。[Nobel Prize lecture](https://www.nobelprize.org/prizes/economic-sciences/1993/north/lecture/)

设计推论：

- 后台事实与人物信念必须分层；
- 同一制度可以被不同人物以不同方式理解和承认；
- 规则不能只存在为硬编码限制，还应通过学习、传闻、执法和处罚进入人物认知。

## 8. 知识依赖证词、信任与分歧

社会认识论研究把证词、说话者可靠性、信任和意见分歧视为重要知识问题。[Stanford Encyclopedia of Philosophy](https://plato.stanford.edu/entries/epistemology-social/)

设计推论：

- 每条非直接观察知识应保留来源与可信度；
- NPC 转述消息时传播的是自己的信念，不是服务器真相；
- 同一事件可以形成互相冲突的叙述；
- 人物志要标示“亲眼所见、本人所述、某人传闻、文书记录”等来源。

## 9. 领主权威既个人化又制度化

关于英格兰乡村道德经济的研究指出，领主权威同时具有个人和制度性质，混合保护、代表与剥削，政治权威也不能简单等同于土地所有权。[Cambridge University Press](https://www.cambridge.org/core/books/abs/moral-economy-of-the-countryside/lordship/E4F54898459C7022F8CC15EFA83F2690)

设计推论：

- “领主 faction 拥有区域内一切”是错误抽象；
- 征税、征役、司法、保护和土地收益分别建模；
- 权威需要正当性、执行能力和被支配者的承认；
- 拒绝命令可以是违约、犯罪、政治抵抗或家庭义务冲突，不能只有“AI 失败”。

## 10. 行会既提供服务，也可能排他

Sheilagh Ogilvie 对欧洲行会的研究总结了训练、质量、互助等主张，同时分析行会排斥竞争、操纵市场并让强势成员受益的情况。[University of Oxford](https://www.history.ox.ac.uk/publication/1145236/scopus)

设计推论：

- 行会不应只是职业经验加成；
- 它可以授予培训、认证、互助、市场准入和争议仲裁；
- 同时可以收会费、限制外来者、保护既得利益；
- 玩家加入行会获得机会，也接受规则和义务。

## 11. 仿真模型应明确实体、变量与调度

ODD 协议用于规范基于个体模型的描述，使模型假设、实体、过程、初始化和子模型更完整、可理解并可复现。[论文页面](https://www.researchgate.net/publication/228969790_The_ODD_protocol_A_review_and_first_update) · [课程资料与论文](https://ees4760.jgilligan.org/downloads/04-odd_protocol/)

设计推论：

- 技术设计明确 Purpose、Entities、State variables、Scales、Process scheduling、Design concepts、Initialization、Input 和 Submodels；
- 每个新系统必须说明谁持有状态、何时更新、依赖什么信息；
- 禁止把关键规则散落在 GUI、实体 tick 和网络包中。

## 12. 大规模模拟需要动态细节层级

关于大规模基于个体城市模拟的研究说明了细节与可扩展性之间的权衡，并使用随关注区域和事件变化的动态细节层级获得性能收益。[AAMAS paper PDF](https://aamas.csc.liv.ac.uk/Proceedings/aamas2011/papers/C5_B67.pdf)

设计推论：

- 玩家附近进行实体级模拟，远方聚落进行事件级模拟；
- 细节切换必须有明确快照与对账机制；
- 远方不能只为“看起来合理”随机构造资源结果；
- 同一初始条件在不同细节层级下应维持相近的守恒结果和方向性后果。

## 13. 信用、债务和市场信任不是现代附加物

关于晚期中世纪英格兰信用经济的研究指出，赊购和短期信用广泛存在，债务关系依靠个人信誉、誓言、行会成员资格、商标、质量条例、标准度量衡和司法追偿共同维持。[Cambridge University Press：Secret Debts](https://www.cambridge.org/core/books/theology-of-debt-in-late-medieval-english-literature/secret-debts/733921FCD2C00F9D3C4EDC3F138AE0BC)

相关研究还表明，战争、铸币短缺和对未来的担忧会收缩信用；流言本身也可能改变商人的风险判断并形成自我强化的衰退。[Enterprise & Society](https://www.cambridge.org/core/journals/enterprise-and-society/article/uncertainty-staple-credit-and-the-measurement-of-later-medieval-business-confidence/508ECA5CD2EEC74939C7043CFD7B39D2)

设计推论：

- 交易不能只有现货栏，必须允许延期交货、赊欠、担保和分期清偿；
- 信用额度是特定债权人对特定债务人的判断，不是全局 `creditScore`；
- 欠债同时生成资产负债、履约风险、记忆和可传播声誉；
- 文书、见证、行会担保和抵押改变的是举证与追偿能力，不会魔法般保证履约；
- 市场恐慌可以由消息和信心传播，但不得直接改写真实库存。

## 14. 市场需要度量衡、鉴定与争议处理

中世纪经济思想研究把度量衡与铸币标准视为主权和市场秩序的一部分：标准既减少争议，也影响税赋与日常交易。[Cambridge University Press：Sovereign concerns](https://www.cambridge.org/core/books/abs/medieval-economic-thought/sovereign-concerns-weights-measures-and-coinage/14A5AAFE549524E47170619049A0600C)

早期中世纪乡村市场的研究也提醒，硬币并非每笔小额交换都能使用；可信的商品货币、记账单位和信用都可能承担交换媒介作用。[Cambridge University Press：Markets and Marketing](https://www.cambridge.org/core/books/abs/moral-economy-of-the-countryside/markets-and-marketing/08277ED11081310BB3EDEF2704AC1B6B)

设计推论：

- 数量、重量、成色、品质和耐久是可检验但未必人人都能精确判断的属性；
- 市集、行会或领主机构可以维护秤、印记、币制和质量规则，并从中取得权力与收入；
- 欺诈不是随机坏事件，而是“实际品质、卖方陈述、买方认知、证据与执法”之间的差异；
- 货币是支付工具之一，物物交换、赊欠、记账和实物工资必须共存；
- UI 应区分“对方声称的品质”“你观察到的品质”和“经可信鉴定的品质”。

## 15. 家庭会经历生命周期，工作关系也不只有日薪

历史家庭研究强调，家庭构成与年龄、职业、继承和工作模式共同变化；家庭不能被视为生成后永久不变的名单。[Cambridge University Press：Family Forms in Historic Europe](https://www.cambridge.org/core/books/abs/family-forms-in-historic-europe/introduction/6E7C0159B479E05B3D40E4093588973A)

中世纪劳动研究区分住在雇主家中的长期仆役，与居住在自己家庭、按日或按件受雇的劳动者；另有家庭劳动、劳役和受制度约束的服务关系。[Cambridge University Press：The wage-earners](https://www.cambridge.org/core/books/abs/standards-of-living-in-the-later-middle-ages/wageearners/FD4F413E2C27CE55E7B911D0B02DD6A9) 农村劳动市场材料也显示，家庭生产、短工、季节工、食宿工资和长期服务可以同时存在。[Continuity and Change](https://www.cambridge.org/core/journals/continuity-and-change/article/regulation-of-the-rural-market-in-waged-labour-in-fourteenthcentury-england/C7726EC8A2D0C628ACFF49428FDA95A7)

设计推论：

- 家庭需要成立、扩张、分户、婚入、寄居、继承和解体状态；
- 工作关系要区分任务工、日工、长期住家服务、学徒、家庭协作与强制义务；
- 食宿、保护、训练、工具使用和身份机会都可能是报酬的一部分；
- 同一人对家庭、雇主和行会的义务可能冲突，冲突进入决策而非由一个优先级枚举永久裁定；
- 模拟不应假定所有服务都是现代自由雇佣，也不应把历史上的强制关系包装成无后果的普通职业。

## 16. 共同资源需要治理，而不只是“公共箱子”

Ostrom 对复杂经济系统的研究反对把市场或单一国家控制当作唯一解，强调信任、沟通、自组织和多中心治理在共同资源中的作用。[Nobel Prize lecture](https://www.nobelprize.org/prizes/economic-sciences/2009/ostrom/lecture/) 中世纪村落与 commons 研究也显示，共同林地、水源、河岸和农牧空间通常依赖集体规则、冲突协商和风险共担。[International Review of Social History](https://www.cambridge.org/core/journals/international-review-of-social-history/article/silent-revolution-a-new-perspective-on-the-emergence-of-commons-guilds-and-other-forms-of-corporate-collective-action-in-western-europe/70995C97C0FE9701FAB7EA07E4D0CE68)

设计推论：

- `PUBLIC` 不是“任何人无限取用”，共同资源仍有成员、季节、配额、维护和制裁规则；
- 村民可以共同管理牧场、森林、水利或磨坊，而无需由一个 faction owner 全权控制；
- 沟通、巡查、违规历史和共同收益影响合作；
- 地方制度可以相互重叠：村社管水，领主管税，行会管质量，家庭管劳动力；
- 多中心治理要求权限判定保留管辖语境，不能只找“最高 owner”。

## 17. 游戏 UI 应在世界一致性与可用性之间取平衡

Jørgensen 对游戏界面的研究指出，开发者需要在功能性与虚构世界的一致性之间平衡；玩家也能够接受符合游戏惯例的系统界面。[Games and Culture](https://journals.sagepub.com/doi/pdf/10.1177/1555412012440315)

CHI PLAY 对有 HUD 与无 HUD 版本的比较说明，沉浸并不简单等于删掉非叙事界面；界面形式、玩家经验和任务需要会共同影响参与感。[Removing the HUD](https://eprints.whiterose.ac.uk/id/eprint/130581/) 另一项 HUD 实证研究也发现，常驻界面可以提高玩家对环境的理解，因此应依据决策频率选择场景内、空间化或屏幕叠层，而不是教条地只用其中一种。[International Journal of Human-Computer Studies](https://www.sciencedirect.com/science/article/pii/S1071581915001779)

Paradox 在《Crusader Kings III》主机 UI 的设计记录中采用了混合方式：保留地图作为主要上下文，通过 Command Bar、Radial 与 Quick Access 减少深层菜单跳转，并避免大量全屏菜单切断玩家对世界的连续感。[Paradox development diary](https://www.paradoxinteractive.com/games/crusader-kings-iii/news/ck3-console-dev-diary-3-uiux-and-controls)

Xbox 无障碍指南要求重要文本、图标、对比、焦点和提示具备可配置性，并建议 PC 1080p 的默认正文达到 18 像素；关键信息不应只依赖颜色，还应同时使用文字、图形或其他通道。[XAG 101: Text display](https://learn.microsoft.com/en-us/gaming/accessibility/xbox-accessibility-guidelines/101) · [XAG 103: Additional channels](https://learn.microsoft.com/en-us/gaming/accessibility/xbox-accessibility-guidelines/103)

不确定性可视化研究表明，界面如何表示不确定性会影响使用者的判断与信心，因此不能把模糊知识偷偷显示成精确数值，也不能只用难以区分的颜色暗示。[不确定性可视化分类研究](https://pmc.ncbi.nlm.nih.gov/articles/PMC4319674/)

设计推论：

- “沉浸”不等于删除所有界面；世界内动作负责高频互动，人物志和账簿负责复杂信息；
- 信息卡必须显示来源、新旧程度和可信区间，不显示伪精确百分比；
- 不确定信息使用文字类别、范围、来源图标和时间，而不只靠颜色；
- 玩家自己的物品、已经公开的报价和已签契约应精确显示，因为人物确实可核对；
- 后台调试真值与正式 UI 分离，原型中可以并排展示以验证边界。

为避免所有系统都退化成同一种卡片列表，演示采用问题驱动的视觉语法：

- 人物交互使用场景、对白与人物志，优先保留对象感；
- 社会结构使用节点与有类型的关系边，表达重叠身份和义务；
- 经济使用仓储节点与物流路径，显示数量从哪里来到哪里去；
- 战斗使用空间态势、最后所见位置、行动边界与命令意图；
- 四个页面共享事件状态，不把系统画成互不相干的仪表盘。

### 17.1 复杂流动适合直接显示依赖关系

关于表格与 Sankey 流图的比较研究发现，在理解模拟参数依赖时，流式表达能减少使用者重新在脑中拼接关系的负担。[Tables or Sankey Diagrams?](https://arxiv.org/abs/2601.10232)

设计推论：

- 经济页先画出仓库、运输容器、目的地和损失去向，再提供精确账簿；
- 流线宽度只有在表示可比较数量时才使用，不能纯装饰；
- 任何聚合流都可追溯到真实容器与转移事件；
- 社会关系不是物质流，不应套用同一图形语言。

### 17.2 战术命令应表达意图与边界

美国海军陆战队关于指挥与控制的条令把重点放在比对手更快形成有效决定和行动，也强调不确定性下的判断、沟通与下级主动性，而非把部属当成逐动作遥控单元。[MCDP 6: Command and Control](https://www.marines.mil/News/Publications/MCPEL/Electronic-Library-Display/Article/898678/mcdp-6/)

设计推论：

- 玩家下达任务意图、保护对象、活动边界与撤退条件；
- NPC 可以依据局部态势采用合理执行方式，也可拒绝越权命令；
- 战术 UI 区分服务器真相、共同态势与某个人的最后所见；
- 命令反馈必须说明是否被收到、是否被接受及当前为什么无法执行。

## 18. 仿真可信度需要验证，不只是“看起来会动”

ODD 协议的后续更新强调，模型应说明设计理由、用途和评估方法，而不仅列出实体和流程。[Journal of Artificial Societies and Social Simulation](https://jasss.soc.surrey.ac.uk/23/2/7.html) 关于基于个体模型验证的方法综述也区分理论、数据、行为复现、可视化、角色扮演等多种验证方式，并指出不存在适合所有模型的单一验证法。[JASSS：Methods That Support the Validation of Agent-Based Models](https://jasss.soc.surrey.ac.uk/27/1/11.html)

设计推论：

- 每个模拟模块都必须声明用途：解释、生成玩法、近似历史模式，还是预测短期结果；
- “现实”不能只靠开发者直觉，需要不变量测试、极端情景、固定种子对比和玩家角色扮演验证；
- 参数不以“历史准确”为名写死，必须标注来源、假设和适用世界模板；
- 重点验证因果方向与守恒，避免追求无法证明的伪精确人口或价格数字；
- 演示原型本身是界面与心智模型验证工具，不等同于完成底层模拟。

## 19. 避免制造一个不存在的“统一中世纪”

本研究引用的许多历史资料来自英格兰或西欧不同时段，只能证明某些制度组合曾经存在，不能证明所有地区都采用同一结构。v2 因此增加 `WorldSocialProfile`：

- 设定采用的地理、技术和制度灵感范围；
- 定义家庭形成、继承、劳动、市场、宗教和政治关系的初始规则；
- 允许世界历史改变这些规则；
- 所有资料驱动参数标明“灵感来源”，而不是宣称普遍历史真相。

Sagadyssey 的目标是因果可信的历史幻想世界，不是把多个世纪的欧洲制度混成静态背景板。

## 20. 资料如何约束本设计

两轮研究最终转化为十二条工程约束：

1. 多重成员关系替代单一身份枚举；
2. 权利束替代所有权布尔值；
3. 信念存储替代全知 AI；
4. 真实库存和原子转移替代交易配方生成；
5. 家庭和制度作为经济主体，替代聚落共享背包；
6. 具有对账的不等精度模拟，替代所有 NPC 每 tick 全量运行；
7. 人际信用、具体债权和担保替代全局信用分；
8. 度量、品质、鉴定和证据成为交易的一部分；
9. 家庭与工作关系随生命周期改变；
10. 共同资源由具体成员规则治理，不等于免费公物；
11. UI 同时保留世界一致性、来源可见性和操作清晰度；
12. 模型必须声明用途并接受守恒、行为、迁移和角色体验验证。

若未来的功能与这些约束冲突，应先修改总体设计并说明理由，而不是在代码中建立例外。
