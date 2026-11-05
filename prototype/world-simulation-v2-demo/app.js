(() => {
  "use strict";

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const initialState = () => ({
    activeView: "person",
    talkDone: false,
    giftDone: false,
    tradeDone: false,
    contractDone: false,
    orderAccepted: false,
    encounter: false,
    resolved: false,
    resolution: null,
    socialNode: "mara",
    socialFilter: "known",
    truthOpen: false,
    player: { bread: 5, charcoal: 18, emerald: 6, hoe: 0 },
    mara: { bread: 0, charcoal: 2, emerald: 1, hoe: 1, arrows: 15, apple: 2, letter: 1 },
    stock: { north: 86, east: 7, convoy: 0 },
    relation: 18,
    offerCharcoal: 12,
    offerEmerald: 2,
    lastEvent: "你在铁匠铺外遇见玛拉。东区市集的面包即将售罄。",
    eventTime: "16:40"
  });

  let state = initialState();
  let toastTimer;

  const ui = {
    systemTabs: $$(".system-tab"),
    views: $$(".system-view"),
    threadSteps: $$(".thread-step"),
    truthToggle: $("#truthToggle"),
    truthDrawer: $("#truthDrawer"),
    closeTruth: $("#closeTruth"),
    truthContent: $("#truthContent"),
    resetButton: $("#resetButton"),
    eventText: $("#eventText"),
    eventTime: $("#eventTime"),
    eventJump: $("#eventJump"),
    toast: $("#toast"),
    talkAction: $("#talkAction"),
    giftAction: $("#giftAction"),
    tradeAction: $("#tradeAction"),
    contractAction: $("#contractAction"),
    tradeModal: $("#tradeModal"),
    commitTrade: $("#commitTrade"),
    acceptOrderAction: $("#acceptOrderAction"),
    commandButtons: $$(".command-button"),
    networkCanvas: $("#networkCanvas"),
    socialDetail: $("#socialDetail")
  };

  const socialRecords = {
    mara: {
      avatar: "玛", title: "玛拉", subtitle: "铁匠学徒 · 河桥居民",
      rows: [["家庭位置", "黛娜之女；承担工坊收入与弟弟口粮"], ["职业身份", "铁匠行会学徒；可使用行会工具，不得出售"], ["公共身份", "河桥居民；受议事会保护，也负有临时协作义务"]],
      rights: ["她的私人财物仍由她决定是否赠与或出售", "母亲能代表家庭谈共同储粮，不能出售玛拉的私物", "护送约定只授权路线同行与紧急防御"]
    },
    daina: {
      avatar: "黛", title: "黛娜", subtitle: "户主 · 玛拉的母亲",
      rows: [["家庭位置", "户主与家庭共有储粮的日常管理者"], ["生计", "为工坊做账，也照料幼子"], ["与你的关系", "只听玛拉提起过你；尚未亲自见面"]],
      rights: ["可以分配家庭共有粮食", "不能处分玛拉的私人报酬与工具", "会依据玛拉转述的履约经历判断你"]
    },
    rusk: {
      avatar: "鲁", title: "鲁斯克", subtitle: "受抚养者 · 玛拉的弟弟",
      rows: [["家庭位置", "未成年；由家庭供养"], ["当前处境", "东区缺粮首先压缩他的口粮"], ["信息来源", "玛拉本人刚刚提及；你未见过他"]],
      rights: ["拥有被照料与领取家庭口粮的正当期待", "不因年幼而成为户主的财产", "其处境会改变玛拉对风险与报酬的判断"]
    },
    guild: {
      avatar: "⚒", title: "铁匠行会", subtitle: "职业制度 · 不是单一 NPC",
      rows: [["成员规则", "学徒、师傅与大师有不同资格"], ["共同资产", "铁镐、熔炉与部分木炭由行会所有"], ["执行能力", "可暂停资格、追索工具并为成员作证"]],
      rights: ["玛拉可持有并使用行会铁镐，但不能出售", "行会可核验她是否完成学徒义务", "工坊停火会影响本地农具维修与运输能力"]
    },
    council: {
      avatar: "⌂", title: "河桥议事会", subtitle: "聚落治理制度",
      rows: [["管理范围", "公共仓库、道路与紧急配给"], ["当前行动", "发布 #RH-17 面包运输委托"], ["正当性", "来自居民承认与执行公共事务的能力"]],
      rights: ["能调拨公共储备，但不能把私人食物当公粮", "可委托承运和检查交付结果", "对货损的追责需要事件、证词与账簿证据"]
    },
    player: {
      avatar: "你", title: "旅人", subtitle: "玩家人物 · 也受世界规则约束",
      rows: [["公开身份", "在河桥短暂停留的外来者"], ["财产权", "拥有自己的背包物品；无权打开玛拉的背包"], ["制度位置", "尚无公职；权限只能来自物权、约定或明确委托"]],
      rights: ["可以赠与或交易自己拥有的物品", "签约后能提出约定范围内的战术意图", "接受运输后只能保管货物，不能据为己有"]
    }
  };

  function escapeHtml(value) {
    return String(value).replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
  }

  function showToast(message) {
    clearTimeout(toastTimer);
    ui.toast.textContent = message;
    ui.toast.hidden = false;
    toastTimer = setTimeout(() => { ui.toast.hidden = true; }, 3300);
  }

  function setEvent(message, time = state.eventTime) {
    state.lastEvent = message;
    state.eventTime = time;
    ui.eventText.textContent = message;
    ui.eventTime.textContent = time;
  }

  function openModal(element) {
    element.hidden = false;
    document.body.classList.add("modal-open");
  }

  function closeModal(element) {
    element.hidden = true;
    if ($$(".modal-layer").every((layer) => layer.hidden)) document.body.classList.remove("modal-open");
  }

  function nextSuggestedView() {
    if (!state.contractDone) return { view: "person", label: "查看人物" };
    if (!state.orderAccepted) return { view: "economy", label: "查看运输" };
    if (!state.resolved) return { view: "combat", label: "查看遭遇" };
    return { view: "social", label: "查看后果" };
  }

  function switchView(view) {
    if (!$("[data-view-panel='" + view + "']")) return;
    state.activeView = view;
    ui.systemTabs.forEach((button) => {
      const active = button.dataset.view === view;
      button.classList.toggle("active", active);
      if (active) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    ui.views.forEach((panel) => panel.classList.toggle("active", panel.dataset.viewPanel === view));
    renderAll();
  }

  function renderThread() {
    let activeIndex = 0;
    if (state.contractDone) activeIndex = 1;
    if (state.orderAccepted) activeIndex = 2;
    if (state.resolved) activeIndex = 3;
    ui.threadSteps.forEach((step, index) => {
      step.classList.toggle("active", index === activeIndex);
      step.classList.toggle("complete", index < activeIndex || (index === 3 && state.resolved));
    });
    const suggestion = nextSuggestedView();
    ui.eventJump.dataset.target = suggestion.view;
    ui.eventJump.innerHTML = `${suggestion.label} <span>→</span>`;
  }

  function knowledgeEntry(icon, title, detail, source) {
    return `<article class="knowledge-entry"><span>${icon}</span><div><strong>${escapeHtml(title)}</strong><small>${escapeHtml(detail)} · <b>${escapeHtml(source)}</b></small></div></article>`;
  }

  function systemGlimpse(domain, title, detail, source) {
    return `<article class="system-glimpse ${domain}"><span>${domain === "social" ? "社会" : domain === "economy" ? "经济" : "战斗"}</span><div><strong>${escapeHtml(title)}</strong><small>${escapeHtml(detail)}</small></div><em>${escapeHtml(source)}</em></article>`;
  }

  function renderPerson() {
    $("#breadCount").textContent = state.player.bread;
    ui.talkAction.querySelector("small").textContent = state.talkDone ? "继续询问近况" : "问她为何望向东区";
    ui.giftAction.disabled = state.giftDone || state.player.bread < 1;
    ui.giftAction.querySelector("strong").textContent = state.giftDone ? "已经递交" : "递出面包";
    ui.giftAction.querySelector("small").textContent = state.giftDone ? "所有权已经转移" : "赠礼 · 一份";
    ui.tradeAction.disabled = !state.talkDone || state.tradeDone;
    ui.tradeAction.querySelector("strong").textContent = state.tradeDone ? "交换完成" : "提出交换";
    $("#tradeActionNote").textContent = state.tradeDone ? "铁锄已经属于你" : state.talkDone ? "只谈双方真实拥有的物品" : "先了解她的需求";
    ui.contractAction.disabled = !state.tradeDone || state.contractDone;
    ui.contractAction.querySelector("strong").textContent = state.contractDone ? "护送约定生效" : "签订护送约定";
    $("#contractActionNote").textContent = state.contractDone ? "两日内 · 路线防御范围" : state.tradeDone ? "两日 · 4 绿宝石 + 口粮" : "需要先形成可信往来";

    const entries = [knowledgeEntry("◒", "看起来疲惫", "你从姿态与动作判断；不显示精确疲劳值", "刚刚观察")];
    if (state.talkDone) {
      entries.push(knowledgeEntry("⌂", "家住东区，要照料母亲和弟弟", "这是她愿意告诉你的家庭情况", "本人所述"));
      entries.push(knowledgeEntry("◆", "工坊需要木炭", "她愿用自己修好的铁锄交换", "本人所述"));
    }
    if (state.giftDone) entries.push(knowledgeEntry("▰", "收下你的一份面包", "一次赠礼形成经历，不等于永久好感加成", "共同经历"));
    if (state.tradeDone) entries.push(knowledgeEntry("⇄", "完成一笔现货交换", "木炭与绿宝石已经实际进入她的背包", "共同记录"));
    if (state.contractDone) entries.push(knowledgeEntry("◇", "同意护送东区运输", "仅限路线同行、看守货物与紧急防御", "共同契约"));
    $("#knowledgeList").innerHTML = entries.join("");

    const socialGlimpse = state.contractDone
      ? ["护送约定已经生效", "你只获得路线同行与紧急防御权限", "共同契约"]
      : state.talkDone
        ? ["家庭与行会义务重叠", "她要供养家人，也必须遵守学徒规则", "本人所述"]
        : ["公开身份：铁匠学徒", "更私密的关系尚未核实", "公开标记"];
    const economyGlimpse = state.tradeDone
      ? ["现货交换已经结算", "物品已改变持有人；没有凭空生成", "共同记录"]
      : state.talkDone
        ? ["工坊缺少木炭", "她愿意展示一把私有铁锄参与交换", "本人所述"]
        : ["似乎在等一笔物资", "需求与可交易物仍不确定", "有限观察"];
    const combatGlimpse = state.resolved
      ? ["林道遭遇已经结束", state.resolution === "retreat" ? "队伍安全撤回，委托未完成" : "可见损耗已进入伤势与履约记录", "共同经历"]
      : state.contractDone
        ? ["只接受有限护送职责", "可以护货与自卫，不能追击或掠夺", "共同契约"]
        : ["没有可见重伤", "精确生命、士气与箭矢数量不可见", "刚刚观察"];
    $("#systemGlimpses").innerHTML = [
      systemGlimpse("social", ...socialGlimpse),
      systemGlimpse("economy", ...economyGlimpse),
      systemGlimpse("combat", ...combatGlimpse)
    ].join("");

    $("#visibleGoods").innerHTML = state.talkDone && !state.tradeDone
      ? `<div class="goods-chip"><span>⌁</span><div><strong>修好的铁锄 ×1</strong><small>玛拉主动展示 · 私人物品 · 愿意谈</small></div></div>`
      : state.tradeDone
        ? `<div class="goods-chip"><span>✓</span><div><strong>铁锄已转给你</strong><small>成交后不再出现在她的可交易物中</small></div></div>`
        : `<em>交谈后才会出现</em>`;

    const relationLabel = state.contractDone ? "有一份明确约定" : state.tradeDone ? "完成过一次交换" : state.giftDone ? "记得你的善意" : state.talkDone ? "愿意与你谈条件" : "只是见过几面";
    $("#relationshipLabel").textContent = relationLabel;
    $("#relationMeter").style.width = `${Math.min(86, state.relation)}%`;
    $("#contractState").textContent = state.contractDone ? "有效至第 14 日" : "尚无";
    $("#contractSummary").textContent = state.contractDone
      ? "你可在北仓—东区路线上请求同行、护货与紧急防御；不能命令掠夺、远离路线或处分她的私物。"
      : "你现在不能命令她跟随、作战或处分她的财物。";
  }

  function renderSocialDetail() {
    const record = socialRecords[state.socialNode] || socialRecords.mara;
    ui.socialDetail.innerHTML = `
      <section class="detail-identity"><span class="detail-avatar">${record.avatar}</span><div><h3>${record.title}</h3><span>${record.subtitle}</span></div></section>
      <section class="detail-section"><span>重叠身份</span><div class="detail-rows">${record.rows.map(([key, value]) => `<div class="detail-row"><small>${key}</small><strong>${value}</strong></div>`).join("")}</div></section>
      <section class="detail-section"><span>权利与边界</span><div class="rights-list">${record.rights.map((right) => `<span>${right}</span>`).join("")}</div></section>
      <section class="detail-section"><span>信息边界</span><div class="detail-row"><small>你为何知道</small><strong>${state.socialFilter === "public" ? "公开名册、行会标记与议事会告示" : state.talkDone ? "观察、玛拉本人陈述与共同经历" : "公开身份与有限观察；私密关系尚未核实"}</strong></div></section>`;
  }

  function renderSocial() {
    $("#playerMaraEdge").classList.toggle("active", state.contractDone);
    $("#contractEdgeLabel").classList.toggle("active", state.contractDone);
    $("#contractEdgeLabel").textContent = state.contractDone ? "两日护送契约" : "尚无约定";
    $("#playerSocialRole").textContent = state.contractDone ? "护送合同当事人" : "没有正式关系";
    ui.networkCanvas.classList.toggle("public-mode", state.socialFilter === "public");
    $$('[data-social-filter]').forEach((button) => button.classList.toggle("active", button.dataset.socialFilter === state.socialFilter));
    $$(".network-node").forEach((button) => button.classList.toggle("selected", button.dataset.node === state.socialNode));
    renderSocialDetail();

    const card = $("#socialConsequence");
    card.className = "consequence-card";
    if (!state.resolved) {
      card.innerHTML = `<p class="overline">世界会记住行为</p><strong>结果尚未发生</strong><span>履约、逃跑、伤亡和货损会分别改变家庭、行会与议事会对你的判断。</span>`;
    } else if (state.resolution === "retreat") {
      card.classList.add("warning");
      card.innerHTML = `<p class="overline">事件已经传播</p><strong>人员平安，但东区仍然缺粮</strong><span>玛拉认可你没有拿生命冒险；议事会记录运输未完成，东区家庭对下一次配给更焦虑。</span>`;
    } else {
      card.classList.add("success");
      card.innerHTML = `<p class="overline">事件已经传播</p><strong>护送履约成为共同记忆</strong><span>玛拉与家庭提高对你的可靠性判断；议事会登记交付，行会得知工坊口粮压力暂时缓解。</span>`;
    }
  }

  function renderEconomy() {
    $("#northStockMetric").textContent = state.stock.north;
    $("#eastStockMetric").textContent = state.stock.east;
    $("#northStockNode").textContent = `${state.stock.north} 面包`;
    $("#marketStockNode").textContent = `${state.stock.east} 面包`;
    $("#convoyStockNode").textContent = state.stock.convoy ? `${state.stock.convoy} 面包` : state.resolved ? "货舱为空" : "尚未装货";
    $("#convoyStateLabel").textContent = state.resolved ? (state.resolution === "retreat" ? "已经返回北仓" : "货物已经交付") : state.orderAccepted ? "运输途中 · 议事会所有" : "等待承运人";
    $("#convoyNode").classList.toggle("loaded", state.orderAccepted && !state.resolved);
    $("#activeFlow").classList.toggle("active", state.orderAccepted && !state.resolved);
    $("#carrierFact").textContent = state.orderAccepted ? "你 · 已签收" : "尚未确定";
    $("#escortFact").textContent = state.orderAccepted ? "玛拉 · 限定路线" : state.contractDone ? "玛拉 · 等待装货" : "尚未确定";
    ui.acceptOrderAction.disabled = !state.contractDone || state.orderAccepted;
    ui.acceptOrderAction.querySelector("span").textContent = state.orderAccepted ? (state.resolved ? "运输委托已经结算" : "货物已装载 · 前往林道") : "接受并装载真实货物";
    $("#acceptOrderNote").textContent = state.orderAccepted ? (state.resolved ? "查看社会结果与账簿" : "24 个面包现位于货驴货箱") : state.contractDone ? "确认保管责任与交付地点" : "先与玛拉签订护送约定";
    $("#integrityText").textContent = `面包总量：北仓 ${state.stock.north} + 运输中 ${state.stock.convoy} + 东区 ${state.stock.east}${state.resolved && state.resolution !== "retreat" ? "；交战损失已单独记录" : "；没有在 UI 打开时生成商品"}。`;
    $("#economyAlert").hidden = state.resolved && state.resolution !== "retreat";
  }

  function battleLogs() {
    const logs = [];
    if (!state.orderAccepted) logs.push(["计划", "接受运输委托后，队伍才能出发。​"]);
    if (state.orderAccepted) {
      logs.push(["16:47", "北仓账簿扣除 24 个面包；货物进入货驴货箱。​"]);
      logs.push(["16:54", "玛拉在林边停步，示意前方可能有人。​"]);
    }
    if (state.resolution === "escort") {
      logs.push(["16:55", "你要求贴身护送；玛拉确认命令在约定范围内。​"]);
      logs.push(["16:57", "三支箭逼退劫匪；混乱中遗失 1 个面包。​"]);
      logs.push(["17:03", "23 个面包交给东区市集，账簿完成交割。​"]);
    } else if (state.resolution === "hold") {
      logs.push(["16:55", "玛拉占住石桥路口，为货驴争取通过时间。​"]);
      logs.push(["16:58", "她用掉五支箭并擦伤手臂；货物没有损失。​"]);
      logs.push(["17:04", "24 个面包全部交付；需要安排伤口照料。​"]);
    } else if (state.resolution === "retreat") {
      logs.push(["16:55", "你下令带货撤回；玛拉没有继续接敌。​"]);
      logs.push(["17:02", "人员与货物返回北仓，委托标记为未完成。​"]);
    }
    return logs;
  }

  function renderCombat() {
    const battlefield = $("#battlefield");
    battlefield.classList.toggle("encounter", state.encounter && !state.resolved);
    battlefield.classList.toggle("resolved", state.resolved);
    battlefield.classList.remove("order-escort", "order-hold", "order-retreat");
    if (state.resolution) battlefield.classList.add(`order-${state.resolution}`);
    $("#battleCargo").textContent = `${state.stock.convoy} 面包`;
    ui.commandButtons.forEach((button) => {
      button.disabled = !state.orderAccepted || state.resolved;
      button.classList.toggle("active", button.dataset.order === (state.resolution || "escort"));
    });
    $("#commandScope").textContent = state.contractDone ? "护送合同 · 路线防御" : "无护送约定";
    $("#commandScopeDetail").textContent = state.contractDone ? "玛拉可拒绝掠夺、追击或离开路线" : "无法向玛拉下达战斗指令";
    $("#cohesionReadout").textContent = state.resolved ? (state.resolution === "retreat" ? "安全撤回 · 委托未完成" : "仍能协同行动") : state.orderAccepted ? "谨慎，但愿意同行" : "尚未组成";
    $("#ammoReadout").textContent = state.resolved ? (state.resolution === "retreat" ? "没有射击" : state.resolution === "hold" ? "明显消耗了数支箭" : "射出少量箭矢") : "玛拉自带；数量不详";
    $("#threatReadout").textContent = state.resolved ? "袭击已经结束" : state.encounter ? "一人可见，另一处只有声响" : "路线传闻有劫匪";

    const missionState = $("#missionState");
    missionState.className = "mission-state";
    if (state.resolved) {
      missionState.classList.add(state.resolution === "retreat" ? "" : "success");
      missionState.querySelector("span").textContent = state.resolution === "retreat" ? "安全撤回" : "已经交付";
    } else if (state.encounter) {
      missionState.classList.add("active");
      missionState.querySelector("span").textContent = "遭遇威胁 · 等待决定";
    } else {
      missionState.querySelector("span").textContent = "尚未出发";
    }

    $("#battleTitle").textContent = state.encounter && !state.resolved ? "林道遭遇：护住货物还是撤退" : state.resolved ? "林道行动已经结束" : "林道护送计划";
    $("#battleCallout").innerHTML = state.resolved
      ? `<span>结果</span><p>${state.resolution === "retreat" ? "人员与货物返回北仓；东区短缺没有解决。" : "交战结果已经进入库存账簿、人物记忆和制度记录。"}</p>`
      : state.encounter
        ? `<span>你所知道的态势</span><p>只确认一名持械者；右下方只有声响。选择意图，玛拉会依据约定与自身判断执行。</p>`
        : `<span>计划</span><p>虚线表示约定允许的活动范围。尚无命令权时，你只能提出请求。</p>`;
    $("#maraBattleState").textContent = state.resolution === "hold" ? "手臂擦伤" : state.resolved ? "仍能行动" : "弓已上弦";
    $("#combatLog").innerHTML = battleLogs().map(([time, message]) => `<li><time>${time}</time><p>${message}</p></li>`).join("");

    const afterAction = $("#afterAction");
    afterAction.className = "after-action";
    if (!state.resolved) {
      afterAction.innerHTML = `<span>行动结果</span><strong>尚未发生交战</strong><p>货损、伤势、履约与逃跑都会继续进入经济和社会系统。</p>`;
    } else if (state.resolution === "retreat") {
      afterAction.classList.add("warning");
      afterAction.innerHTML = `<span>行动结果</span><strong>全员撤回 · 委托未完成</strong><p>0 伤亡，0 货损；北仓重新入库 24，东区仍只有 7 个面包。</p>`;
    } else if (state.resolution === "hold") {
      afterAction.classList.add("success");
      afterAction.innerHTML = `<span>行动结果</span><strong>24 个面包全部交付</strong><p>玛拉手臂擦伤、消耗 5 支箭；议事会与家庭分别记录后果。</p>`;
    } else {
      afterAction.classList.add("success");
      afterAction.innerHTML = `<span>行动结果</span><strong>23 个面包抵达东区</strong><p>遗失 1 个面包、消耗 3 支箭，无人受伤；损失有明确去向。</p>`;
    }
    $("#combatAlert").hidden = !state.encounter || state.resolved;
  }

  function renderTradeModal() {
    $("#charcoalOffer").textContent = state.offerCharcoal;
    $("#emeraldOffer").textContent = state.offerEmerald;
    const score = state.offerCharcoal + state.offerEmerald * 4;
    let reply = "“我眼下最缺的是能续炉火的木炭。”";
    if (state.offerCharcoal >= 8 && score < 20) reply = "“方向没错，但这些还不够换走锄头。”";
    if (state.offerCharcoal >= 8 && score >= 20) reply = "“这些正好够我把炉火续上。可以。”";
    $("#tradeReply").textContent = reply;
    ui.commitTrade.disabled = !(state.offerCharcoal >= 8 && score >= 20 && state.offerCharcoal <= state.player.charcoal && state.offerEmerald <= state.player.emerald);
    ui.commitTrade.textContent = ui.commitTrade.disabled ? "玛拉尚未同意" : "双方同意 · 提交交换";
  }

  function renderTruth() {
    ui.truthContent.innerHTML = `
      <section class="truth-section"><h3>人物精确状态</h3><div class="truth-grid"><span>CharacterId</span><code>7c9a…4e21</code><span>饥饿</span><code>${state.giftDone ? 34 : 76} / 100</code><span>疲劳</span><code>61 / 100</code><span>信任：玛拉 → 玩家</span><code>${state.relation} / 100</code><span>士气内部值</span><code>${state.resolution === "hold" ? 58 : 66} / 100</code><span>箭矢</span><code>${state.mara.arrows} / 普通背包格</code></div></section>
      <section class="truth-section"><h3>玛拉的完整背包 · 普通客户端未收到</h3><ul class="truth-list"><li><strong>面包 ×${state.mara.bread}</strong> · Mara private</li><li><strong>木炭 ×${state.mara.charcoal}</strong> · Mara private</li><li><strong>绿宝石 ×${state.mara.emerald}</strong> · Mara private</li><li><strong>箭矢 ×${state.mara.arrows}</strong> · 普通 36 格背包；没有 NPC 专用槽</li><li><strong>苹果 ×${state.mara.apple}</strong> · hidden</li><li><strong>书与笔 ×1（账册）</strong> · custody only</li><li><strong>铁镐 ×1</strong> · Guild owned; POSSESS + USE; no TRANSFER</li></ul></section>
      <section class="truth-section"><h3>库存守恒</h3><div class="truth-grid"><span>北仓面包</span><code>${state.stock.north}</code><span>运输中面包</span><code>${state.stock.convoy}</code><span>东区面包</span><code>${state.stock.east}</code><span>交战损失</span><code>${state.resolution === "escort" ? 1 : 0}</code><span>系统内合计</span><code>${state.stock.north + state.stock.convoy + state.stock.east + (state.resolution === "escort" ? 1 : 0)} / initial 93</code></div></section>
      <section class="truth-section"><h3>权限解析</h3><ul class="truth-list"><li><strong>玩家 → 玛拉：</strong>${state.contractDone ? "REQUEST_FOLLOW + ROUTE_DEFENSE；第 14 日到期" : "无命令授权"}</li><li><strong>玩家 → 运输货物：</strong>${state.orderAccepted ? "POSSESS + DELIVER；无 CONSUME / TRANSFER" : "无"}</li><li><strong>玛拉 → 行会锤：</strong>POSSESS + USE；无 TRANSFER</li><li><strong>调试提醒：</strong>AI 阈值、库存和精确士气不会进入 NpcViewSnapshot。</li></ul></section>`;
  }

  function renderGlobal() {
    if (state.resolved && state.resolution !== "retreat") {
      $("#worldStatus").textContent = "东区暂时稳住";
      $("#worldStatusNote").textContent = `${state.stock.east} 个面包已经入市`;
    } else if (state.resolved) {
      $("#worldStatus").textContent = "短缺仍在继续";
      $("#worldStatusNote").textContent = "运输安全返回，但没有交付";
    } else if (state.orderAccepted) {
      $("#worldStatus").textContent = "运输队遇袭";
      $("#worldStatusNote").textContent = "林道上的决定会影响三户家庭";
    } else {
      $("#worldStatus").textContent = "粮食紧张";
      $("#worldStatusNote").textContent = "东区只能维持约一天";
    }
    ui.eventText.textContent = state.lastEvent;
    ui.eventTime.textContent = state.eventTime;
    renderThread();
  }

  function renderAll() {
    renderGlobal();
    renderPerson();
    renderSocial();
    renderEconomy();
    renderCombat();
    renderTradeModal();
    renderTruth();
  }

  function talk() {
    if (!state.talkDone) {
      state.talkDone = true;
      state.relation += 4;
      $("#npcSpeech").innerHTML = `<span>本人所述</span><p>“东区没面包了。我得照看家里，也得给工坊找木炭。”</p>`;
      setEvent("玛拉说出自己的家庭责任与工坊需求；这是她的陈述，不是后台替你认证的绝对事实。", "16:42");
      showToast("人物志新增：家庭关系、工坊需求与一件愿意交易的私物。 ");
    } else {
      $("#npcSpeech").innerHTML = state.contractDone
        ? `<span>当前约定</span><p>“只走北仓到东区这条路。若有危险，我们先护住人和货。”</p>`
        : state.tradeDone
          ? `<span>本人所述</span><p>“你按说好的交换了。若要我护送，把路线、报酬和边界讲清楚。”</p>`
          : `<span>本人所述</span><p>“木炭最要紧。锄头是我的，师傅的锤子可不在交易里。”</p>`;
      setEvent("你继续交谈；玛拉只解释她愿意披露且与你当前行动有关的内容。", state.eventTime);
    }
    renderAll();
  }

  function giveBread() {
    if (state.giftDone || state.player.bread < 1) return;
    state.player.bread -= 1;
    state.mara.bread += 1;
    state.giftDone = true;
    state.relation += 9;
    $("#npcSpeech").innerHTML = `<span>共同经历</span><p>“谢谢。我会带回去和家里人分。” 她把面包收进自己的背包。</p>`;
    setEvent("一份面包从你的私人财产转为玛拉的私人财产；家庭尚未因此自动获得所有权。", "16:43");
    showToast("递交完成：无需打开 NPC 背包；数量、持有人和来源都已更新。 ");
    renderAll();
  }

  function openTrade() {
    if (!state.talkDone || state.tradeDone) return;
    state.offerCharcoal = Math.min(12, state.player.charcoal);
    state.offerEmerald = Math.min(2, state.player.emerald);
    renderTradeModal();
    openModal(ui.tradeModal);
  }

  function commitTrade() {
    const score = state.offerCharcoal + state.offerEmerald * 4;
    if (state.tradeDone || state.offerCharcoal < 8 || score < 20 || state.player.charcoal < state.offerCharcoal || state.player.emerald < state.offerEmerald || state.mara.hoe < 1) {
      showToast("库存或报价已经变化，交换没有提交。 ");
      return;
    }
    state.player.charcoal -= state.offerCharcoal;
    state.player.emerald -= state.offerEmerald;
    state.mara.charcoal += state.offerCharcoal;
    state.mara.emerald += state.offerEmerald;
    state.mara.hoe -= 1;
    state.player.hoe += 1;
    state.tradeDone = true;
    state.relation += 10;
    closeModal(ui.tradeModal);
    $("#npcSpeech").innerHTML = `<span>共同记录</span><p>“说定了。锄头归你，木炭和绿宝石归我。”</p>`;
    setEvent(`现货交换原子提交：${state.offerCharcoal} 份木炭和 ${state.offerEmerald} 枚绿宝石换取铁锄；没有生成任何物品。`, "16:45");
    showToast("交换完成：双方真实库存与财产权同时更新。 ");
    renderAll();
  }

  function signContract() {
    if (!state.tradeDone || state.contractDone) return;
    state.contractDone = true;
    state.relation += 11;
    $("#npcSpeech").innerHTML = `<span>共同契约</span><p>“两日内，北仓到东区。只护人和货，不追杀，不抢东西。”</p>`;
    setEvent("你与玛拉签下两日护送约定：授权来自条款，不来自人物所有权。", "16:46");
    showToast("有限授权已经生效。生计页现在可以接受运输委托。 ");
    renderAll();
  }

  function acceptOrder() {
    if (!state.contractDone || state.orderAccepted || state.stock.north < 24) return;
    state.stock.north -= 24;
    state.stock.convoy += 24;
    state.orderAccepted = true;
    state.encounter = true;
    setEvent("北仓实际扣除 24 个面包并装入货驴；队伍在林道发现可疑身影。", "16:54");
    showToast("运输已经开始。行动页出现威胁；你的命令范围来自护送约定。 ");
    renderAll();
    setTimeout(() => switchView("combat"), 350);
  }

  function resolveCombat(order) {
    if (!state.orderAccepted || state.resolved) return;
    state.resolved = true;
    state.encounter = false;
    state.resolution = order;
    if (order === "escort") {
      state.stock.convoy -= 1;
      state.mara.arrows -= 3;
      state.stock.east += state.stock.convoy;
      state.stock.convoy = 0;
      state.relation += 8;
      setEvent("队伍贴身护送货驴通过林道：23 个面包交付，1 个在混乱中遗失，无人受伤。", "17:03");
      showToast("交付完成：货损、弹药消耗、关系与市场库存已经同时更新。 ");
    } else if (order === "hold") {
      state.mara.arrows -= 5;
      state.stock.east += state.stock.convoy;
      state.stock.convoy = 0;
      state.relation += 6;
      setEvent("玛拉守住路口，24 个面包全部交付；她擦伤手臂并消耗了五支箭。", "17:04");
      showToast("交付完成：货物无损，但伤势和弹药消耗成为后续责任。 ");
    } else {
      state.stock.north += state.stock.convoy;
      state.stock.convoy = 0;
      state.relation += 2;
      setEvent("你选择带货撤退。人员与 24 个面包安全返回北仓，但东区运输委托未完成。", "17:02");
      showToast("撤退完成：人员与货物保全，短缺和未履约记录仍然存在。 ");
    }
    renderAll();
  }

  function toggleTruth(force) {
    state.truthOpen = typeof force === "boolean" ? force : !state.truthOpen;
    ui.truthDrawer.hidden = !state.truthOpen;
    ui.truthToggle.setAttribute("aria-pressed", String(state.truthOpen));
    renderTruth();
  }

  function resetDemo() {
    closeModal(ui.tradeModal);
    toggleTruth(false);
    state = initialState();
    $("#npcSpeech").innerHTML = `<span>观察</span><p>她停下手里的活，反复望向通往东区的路。</p>`;
    switchView("person");
    showToast("演示已重置。四个系统仍可随时查看，但行动必须从人物与约定开始。 ");
  }

  ui.systemTabs.forEach((button) => button.addEventListener("click", () => switchView(button.dataset.view)));
  ui.threadSteps.forEach((button) => button.addEventListener("click", () => switchView(button.dataset.jump)));
  ui.eventJump.addEventListener("click", () => switchView(ui.eventJump.dataset.target));
  ui.talkAction.addEventListener("click", talk);
  ui.giftAction.addEventListener("click", giveBread);
  ui.tradeAction.addEventListener("click", openTrade);
  ui.contractAction.addEventListener("click", signContract);
  ui.commitTrade.addEventListener("click", commitTrade);
  ui.acceptOrderAction.addEventListener("click", acceptOrder);
  ui.truthToggle.addEventListener("click", () => toggleTruth());
  ui.closeTruth.addEventListener("click", () => toggleTruth(false));
  ui.resetButton.addEventListener("click", resetDemo);

  $$("[data-close]").forEach((button) => button.addEventListener("click", () => closeModal(document.getElementById(button.dataset.close))));
  $$(".modal-layer").forEach((layer) => layer.addEventListener("click", (event) => { if (event.target === layer) closeModal(layer); }));
  $$('[data-trade-step]').forEach((button) => button.addEventListener("click", () => { state.offerCharcoal = Math.max(0, Math.min(state.player.charcoal, state.offerCharcoal + Number(button.dataset.tradeStep))); renderTradeModal(); }));
  $$('[data-emerald-step]').forEach((button) => button.addEventListener("click", () => { state.offerEmerald = Math.max(0, Math.min(state.player.emerald, state.offerEmerald + Number(button.dataset.emeraldStep))); renderTradeModal(); }));
  ui.commandButtons.forEach((button) => button.addEventListener("click", () => resolveCombat(button.dataset.order)));
  $$(".network-node").forEach((button) => button.addEventListener("click", () => { state.socialNode = button.dataset.node; renderSocial(); }));
  $$('[data-social-filter]').forEach((button) => button.addEventListener("click", () => { state.socialFilter = button.dataset.socialFilter; renderSocial(); }));

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      if (!ui.tradeModal.hidden) closeModal(ui.tradeModal);
      else if (state.truthOpen) toggleTruth(false);
      return;
    }
    if (!ui.tradeModal.hidden || state.truthOpen || event.ctrlKey || event.metaKey || event.altKey) return;
    if (state.activeView === "person") {
      if (event.key.toLowerCase() === "e") talk();
      if (event.key.toLowerCase() === "g") giveBread();
      if (event.key.toLowerCase() === "t" && !ui.tradeAction.disabled) openTrade();
      if (event.key.toLowerCase() === "c" && !ui.contractAction.disabled) signContract();
    }
  });

  renderAll();
})();
