import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const outputDir = process.argv[2]
  ? resolve(process.cwd(), process.argv[2])
  : resolve(here, "../exported");
const projectRoot = resolve(here, "../../..");
const version = readFileSync(resolve(projectRoot, "gradle.properties"), "utf8")
  .match(/^version=(.+)$/m)?.[1]?.trim();
if (!version) throw new Error("Cannot read version from gradle.properties");
mkdirSync(outputDir, { recursive: true });

const tones = {
  blue: ["#0D2A47", "#38BDF8", "#E0F2FE", "#7DD3FC"],
  indigo: ["#1E2455", "#818CF8", "#EEF2FF", "#A5B4FC"],
  purple: ["#2D1D46", "#C084FC", "#FAF5FF", "#D8B4FE"],
  green: ["#0B352F", "#34D399", "#ECFDF5", "#6EE7B7"],
  orange: ["#3A2416", "#FB923C", "#FFF7ED", "#FDBA74"],
  red: ["#3B1A27", "#FB7185", "#FFF1F2", "#FDA4AF"],
  slate: ["#172033", "#64748B", "#F1F5F9", "#94A3B8"],
};

const esc = (value = "") => String(value)
  .replaceAll("&", "&amp;").replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;").replaceAll('"', "&quot;");

function defs() {
  return `<defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#08111F"/><stop offset=".55" stop-color="#111C35"/><stop offset="1" stop-color="#17143A"/></linearGradient>
    <pattern id="grid" width="36" height="36" patternUnits="userSpaceOnUse"><path d="M36 0H0V36" fill="none" stroke="#C7D2FE" stroke-opacity=".045"/></pattern>
    <filter id="shadow" x="-25%" y="-30%" width="150%" height="170%"><feDropShadow dx="0" dy="10" stdDeviation="12" flood-color="#020617" flood-opacity=".38"/></filter>
    <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#60A5FA"/></marker>
    <marker id="arrowGreen" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#34D399"/></marker>
    <marker id="arrowOrange" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#FB923C"/></marker>
  </defs>`;
}

function shell(title, subtitle, width, height, body) {
  return `<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-labelledby="diagram-title diagram-desc" preserveAspectRatio="xMidYMid meet">
  <title id="diagram-title">${esc(title)}</title>
  <desc id="diagram-desc">${esc(subtitle)}</desc>
  ${defs()}
  <rect width="${width}" height="${height}" rx="28" fill="url(#bg)"/>
  <rect width="${width}" height="${height}" rx="28" fill="url(#grid)"/>
  <text x="52" y="58" fill="#F8FAFC" font-family="Inter, PingFang SC, Microsoft YaHei, sans-serif" font-size="28" font-weight="760">${esc(title)}</text>
  <text x="52" y="88" fill="#94A3B8" font-family="Inter, PingFang SC, Microsoft YaHei, sans-serif" font-size="14">${esc(subtitle)}</text>
  ${body}
  <text x="${width - 52}" y="${height - 25}" text-anchor="end" fill="#64748B" font-family="Inter, PingFang SC, sans-serif" font-size="11">AI 智能套件 · ${esc(version)}</text>
</svg>`;
}

function card(n) {
  const [fill, stroke, title, sub] = tones[n.tone || "blue"];
  const radius = n.kind === "decision" ? 22 : 18;
  const titleY = n.y + (n.subtitle ? n.h / 2 - 2 : n.h / 2 + 7);
  const subtitleY = titleY + 26;
  const badge = n.badge ? `<rect x="${n.x + 18}" y="${n.y + 14}" width="${Math.max(62, n.badge.length * 9 + 20)}" height="22" rx="11" fill="${stroke}" fill-opacity=".16"/><text x="${n.x + 29}" y="${n.y + 30}" fill="${sub}" font-family="Inter, PingFang SC, sans-serif" font-size="10" font-weight="750" letter-spacing=".7">${esc(n.badge)}</text>` : "";
  const offset = n.badge ? 12 : 0;
  return `<g filter="url(#shadow)">
    <rect x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}" rx="${radius}" fill="${fill}" stroke="${stroke}" stroke-opacity=".68"/>
    ${badge}
    <text x="${n.x + n.w / 2}" y="${titleY + offset}" text-anchor="middle" fill="${title}" font-family="Inter, PingFang SC, Microsoft YaHei, sans-serif" font-size="${n.fontSize || 17}" font-weight="720">${esc(n.title)}</text>
    ${n.subtitle ? `<text x="${n.x + n.w / 2}" y="${subtitleY + offset}" text-anchor="middle" fill="${sub}" font-family="Inter, PingFang SC, Microsoft YaHei, sans-serif" font-size="12.5">${esc(n.subtitle)}</text>` : ""}
  </g>`;
}

function edge(e, byId) {
  const a = byId.get(e.from), b = byId.get(e.to);
  const color = e.tone === "green" ? "#34D399" : e.tone === "orange" ? "#FB923C" : "#60A5FA";
  const marker = e.tone === "green" ? "arrowGreen" : e.tone === "orange" ? "arrowOrange" : "arrow";
  const dashed = e.dashed ? ' stroke-dasharray="7 7"' : "";
  let x1, y1, x2, y2, d, lx, ly;
  if (e.fromSide === "bottom" || e.toSide === "top" || Math.abs((b.y + b.h / 2) - (a.y + a.h / 2)) > Math.abs((b.x + b.w / 2) - (a.x + a.w / 2))) {
    x1 = a.x + a.w / 2; y1 = a.y + a.h;
    x2 = b.x + b.w / 2; y2 = b.y;
    const mid = (y1 + y2) / 2;
    d = `M${x1} ${y1} C${x1} ${mid},${x2} ${mid},${x2} ${y2}`;
    lx = (x1 + x2) / 2; ly = mid - 8;
  } else {
    const right = b.x >= a.x;
    x1 = right ? a.x + a.w : a.x; y1 = a.y + a.h / 2;
    x2 = right ? b.x : b.x + b.w; y2 = b.y + b.h / 2;
    const mid = (x1 + x2) / 2;
    d = `M${x1} ${y1} C${mid} ${y1},${mid} ${y2},${x2} ${y2}`;
    lx = mid; ly = (y1 + y2) / 2 - 9;
  }
  return `<path d="${d}" stroke="${color}" stroke-width="2.2" fill="none" marker-end="url(#${marker})"${dashed}/>
  ${e.label ? `<rect x="${lx - Math.max(28, e.label.length * 7 / 2 + 8)}" y="${ly - 15}" width="${Math.max(56, e.label.length * 7 + 16)}" height="22" rx="11" fill="#0B1220" stroke="${color}" stroke-opacity=".35"/><text x="${lx}" y="${ly}" text-anchor="middle" fill="${e.tone === "orange" ? "#FDBA74" : e.tone === "green" ? "#6EE7B7" : "#93C5FD"}" font-family="Inter, PingFang SC, sans-serif" font-size="11">${esc(e.label)}</text>` : ""}`;
}

function group(g) {
  return `<rect x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" rx="22" fill="#0F172A" fill-opacity=".52" stroke="#475569" stroke-opacity=".8"/>
  <text x="${g.x + 20}" y="${g.y + 28}" fill="#A5B4FC" font-family="Inter, PingFang SC, sans-serif" font-size="11" font-weight="750" letter-spacing="1.5">${esc(g.title)}</text>`;
}

function renderFlow(spec) {
  const byId = new Map(spec.nodes.map(n => [n.id, n]));
  const body = [
    ...(spec.groups || []).map(group),
    ...spec.edges.map(e => edge(e, byId)),
    ...spec.nodes.map(card),
    ...(spec.notes || []).map(n => `<text x="${n.x}" y="${n.y}" fill="${n.color || "#94A3B8"}" font-family="Inter, PingFang SC, sans-serif" font-size="${n.size || 13}">${esc(n.text)}</text>`),
  ].join("\n");
  return shell(spec.title, spec.subtitle, spec.width || 1400, spec.height || 700, body);
}

function renderSequence(spec) {
  const width = spec.width || 1400, height = spec.height || 700;
  const margin = 90, gap = (width - margin * 2) / (spec.actors.length - 1);
  const positions = new Map();
  let body = "";
  spec.actors.forEach((a, i) => {
    const x = margin + i * gap;
    positions.set(a.id, x);
    const [fill, stroke, title, sub] = tones[a.tone || "blue"];
    body += `<g filter="url(#shadow)"><rect x="${x - 92}" y="122" width="184" height="64" rx="17" fill="${fill}" stroke="${stroke}" stroke-opacity=".7"/><text x="${x}" y="151" text-anchor="middle" fill="${title}" font-family="Inter, PingFang SC, sans-serif" font-size="16" font-weight="720">${esc(a.title)}</text>${a.subtitle ? `<text x="${x}" y="172" text-anchor="middle" fill="${sub}" font-family="Inter, PingFang SC, sans-serif" font-size="11">${esc(a.subtitle)}</text>` : ""}</g><path d="M${x} 186V${height - 70}" stroke="${stroke}" stroke-opacity=".28" stroke-width="1.5" stroke-dasharray="6 7"/>`;
  });
  spec.messages.forEach((m, i) => {
    const y = 230 + i * (spec.rowGap || 55), x1 = positions.get(m.from), x2 = positions.get(m.to);
    const reverse = x2 < x1;
    const color = m.tone === "green" ? "#34D399" : m.tone === "orange" ? "#FB923C" : "#60A5FA";
    const marker = m.tone === "green" ? "arrowGreen" : m.tone === "orange" ? "arrowOrange" : "arrow";
    body += `<path d="M${x1} ${y}H${x2 + (reverse ? 8 : -8)}" stroke="${color}" stroke-width="2" ${m.dashed ? 'stroke-dasharray="7 6"' : ""} marker-end="url(#${marker})"/><rect x="${(x1+x2)/2 - Math.max(42,m.label.length*6/2+10)}" y="${y-30}" width="${Math.max(84,m.label.length*6+20)}" height="22" rx="11" fill="#0B1220"/><text x="${(x1+x2)/2}" y="${y-15}" text-anchor="middle" fill="${m.tone === "green" ? "#6EE7B7" : m.tone === "orange" ? "#FDBA74" : "#BFDBFE"}" font-family="Inter, PingFang SC, sans-serif" font-size="11.5">${esc(m.label)}</text>`;
  });
  if (spec.note) body += `<rect x="${spec.note.x}" y="${spec.note.y}" width="${spec.note.w}" height="${spec.note.h}" rx="16" fill="#3A2416" stroke="#FB923C" stroke-opacity=".5"/><text x="${spec.note.x+20}" y="${spec.note.y+30}" fill="#FDBA74" font-family="Inter, PingFang SC, sans-serif" font-size="13">${esc(spec.note.text)}</text>`;
  return shell(spec.title, spec.subtitle, width, height, body);
}

const diagrams = {
  "documentation-map": {
    title: "文档体系地图", subtitle: "从产品入口进入不同读者路径，每个页面只解决一个主要问题", height: 650,
    nodes: [
      {id:"home",x:570,y:125,w:260,h:82,title:"README",subtitle:"产品入口与快速导航",tone:"indigo"},
      {id:"start",x:70,y:300,w:190,h:82,title:"快速开始",subtitle:"安装 · 首次问答",tone:"green"},
      {id:"user",x:285,y:300,w:190,h:82,title:"用户手册",subtitle:"功能配置与案例",tone:"blue"},
      {id:"ops",x:500,y:300,w:190,h:82,title:"生产运维",subtitle:"部署 · 排障 · 升级",tone:"orange"},
      {id:"arch",x:715,y:300,w:190,h:82,title:"系统架构",subtitle:"RAG · 意图 · 数据",tone:"purple"},
      {id:"api",x:930,y:300,w:190,h:82,title:"API 参考",subtitle:"请求 · 响应 · SSE",tone:"indigo"},
      {id:"ref",x:1145,y:300,w:190,h:82,title:"参考资料",subtitle:"配置 · 兼容矩阵",tone:"slate"},
      {id:"task",x:370,y:480,w:300,h:80,title:"面向任务的操作路径",subtitle:"让站长能够独立完成配置",tone:"green"},
      {id:"truth",x:730,y:480,w:300,h:80,title:"面向事实的技术路径",subtitle:"让开发者能够理解与扩展",tone:"purple"},
    ],
    edges:[
      {from:"home",to:"start"},{from:"home",to:"user"},{from:"home",to:"ops",tone:"orange"},{from:"home",to:"arch"},{from:"home",to:"api"},{from:"home",to:"ref"},
      {from:"user",to:"task",tone:"green"},{from:"ops",to:"task",tone:"green"},{from:"arch",to:"truth"},{from:"api",to:"truth"},{from:"ref",to:"truth"},
    ]
  },
  "installation-journey": {
    title:"安装到首次成功",subtitle:"先验证基础能力，再建立索引，最后开放访客功能",height:420,
    nodes:[
      {id:"jar",x:55,y:170,w:190,h:86,title:"安装插件 JAR",subtitle:"Halo Console",tone:"slate",badge:"STEP 1"},
      {id:"start",x:285,y:170,w:190,h:86,title:"状态 STARTED",subtitle:"确认插件加载",tone:"blue",badge:"STEP 2"},
      {id:"model",x:515,y:170,w:220,h:86,title:"配置模型",subtitle:"Chat + Embedding",tone:"purple",badge:"STEP 3"},
      {id:"test",x:775,y:170,w:190,h:86,title:"连接测试",subtitle:"两项均通过",tone:"indigo",badge:"STEP 4"},
      {id:"index",x:1005,y:170,w:190,h:86,title:"全量索引",subtitle:"公开文章入库",tone:"orange",badge:"STEP 5"},
      {id:"verify",x:1235,y:170,w:120,h:86,title:"问答",subtitle:"回答 + 引用",tone:"green",badge:"DONE"},
    ],edges:[{from:"jar",to:"start"},{from:"start",to:"model"},{from:"model",to:"test"},{from:"test",to:"index",tone:"orange"},{from:"index",to:"verify",tone:"green"}]
  },
  "index-build-flow": {
    title:"文章索引构建",subtitle:"文章是事实来源，Lucene 是可以重建的派生知识索引",height:500,
    nodes:[
      {id:"post",x:55,y:190,w:190,h:90,title:"Halo 公开文章",subtitle:"已发布 · 公开 · 未删除",tone:"blue"},
      {id:"clean",x:290,y:190,w:175,h:90,title:"内容清洗",subtitle:"HTML / 空白 / 元数据",tone:"slate"},
      {id:"chunk",x:510,y:190,w:175,h:90,title:"文档切片",subtitle:"标题与句子边界",tone:"indigo"},
      {id:"bm25",x:750,y:130,w:190,h:82,title:"BM25 字段",subtitle:"关键词召回",tone:"blue"},
      {id:"embed",x:750,y:260,w:190,h:82,title:"Embedding",subtitle:"HNSW 向量召回",tone:"purple"},
      {id:"lucene",x:1010,y:190,w:210,h:90,title:"Lucene 索引",subtitle:"BM25 + HNSW",tone:"orange"},
      {id:"ready",x:1260,y:190,w:100,h:90,title:"可检索",subtitle:"RAG",tone:"green"},
    ],edges:[{from:"post",to:"clean"},{from:"clean",to:"chunk"},{from:"chunk",to:"bm25"},{from:"chunk",to:"embed"},{from:"bm25",to:"lucene"},{from:"embed",to:"lucene"},{from:"lucene",to:"ready",tone:"green"}]
  },
  "config-storage": {
    title:"配置与模型能力",subtitle:"AI Suite 保存业务配置，模型凭据由 Halo AI Foundation 管理",height:560,
    groups:[{x:420,y:120,w:540,h:320,title:"HALO EXTENSION STORE"}],
    nodes:[
      {id:"ui",x:55,y:225,w:220,h:90,title:"Console 配置页面",subtitle:"管理员编辑与测试",tone:"blue"},
      {id:"api",x:315,y:225,w:210,h:90,title:"配置 Endpoint",subtitle:"校验 · 保存",tone:"indigo"},
      {id:"cm",x:590,y:155,w:300,h:95,title:"ConfigMap",subtitle:"ai-suite-configmap · 普通 JSON",tone:"slate"},
      {id:"foundation",x:590,y:315,w:300,h:95,title:"AI Foundation",subtitle:"模型供应商 · API Key",tone:"orange"},
      {id:"props",x:1015,y:225,w:210,h:90,title:"AIProperties",subtitle:"统一读取与默认值",tone:"purple"},
      {id:"services",x:1260,y:225,w:100,h:90,title:"服务",subtitle:"RAG / AI",tone:"green"},
    ],edges:[{from:"ui",to:"api"},{from:"api",to:"cm"},{from:"cm",to:"props"},{from:"foundation",to:"services",tone:"orange"},{from:"props",to:"services",tone:"green"}]
  },
  "production-topology": {
    title:"生产部署拓扑",subtitle:"SSE、真实客户端 IP、模型网络与数据持久化必须同时成立",height:600,
    groups:[{x:45,y:125,w:880,h:350,title:"REQUEST PATH"},{x:980,y:125,w:375,h:350,title:"PLUGIN DEPENDENCIES"}],
    nodes:[
      {id:"browser",x:80,y:245,w:170,h:88,title:"访客浏览器",subtitle:"fetch + ReadableStream",tone:"blue"},
      {id:"cdn",x:295,y:245,w:170,h:88,title:"CDN / WAF",subtitle:"可选 · 禁止缓冲",tone:"slate"},
      {id:"nginx",x:510,y:245,w:170,h:88,title:"Nginx",subtitle:"proxy_buffering off",tone:"orange"},
      {id:"halo",x:725,y:245,w:170,h:88,title:"Halo :8090",subtitle:"插件运行时",tone:"indigo"},
      {id:"model",x:1030,y:165,w:270,h:82,title:"AI Foundation",subtitle:"统一模型能力",tone:"purple"},
      {id:"store",x:1030,y:270,w:270,h:82,title:"Extension Store",subtitle:"配置与业务记录",tone:"slate"},
      {id:"lucene",x:1030,y:375,w:270,h:82,title:"Lucene 索引",subtitle:"Halo 数据目录",tone:"green"},
    ],edges:[{from:"browser",to:"cdn"},{from:"cdn",to:"nginx",tone:"orange"},{from:"nginx",to:"halo",tone:"orange"},{from:"halo",to:"model"},{from:"halo",to:"store"},{from:"halo",to:"lucene",tone:"green"}]
  },
  "troubleshooting-tree": {
    title:"故障排查决策树",subtitle:"从插件状态开始逐层缩小范围，不要一上来同时修改所有参数",height:900,
    nodes:[
      {id:"issue",x:570,y:115,w:260,h:72,title:"功能异常",subtitle:"先确认故障边界",tone:"red"},
      {id:"loaded",x:555,y:225,w:290,h:78,title:"插件是否 STARTED？",subtitle:"Halo Plugin 状态",tone:"indigo",kind:"decision"},
      {id:"plugin",x:70,y:340,w:300,h:82,title:"检查插件加载",subtitle:"版本 · JAR · Scheme · Lucene",tone:"orange"},
      {id:"model",x:555,y:340,w:290,h:78,title:"模型测试是否通过？",subtitle:"Chat 与 Embedding",tone:"indigo",kind:"decision"},
      {id:"network",x:1030,y:340,w:300,h:82,title:"检查模型连接",subtitle:"URL · Key · 模型名 · 网络",tone:"orange"},
      {id:"index",x:555,y:455,w:290,h:78,title:"索引是否有文章？",subtitle:"公开文章数与切片",tone:"indigo",kind:"decision"},
      {id:"reindex",x:70,y:560,w:300,h:82,title:"检查索引构建",subtitle:"文章状态 · 维度 · 写权限",tone:"orange"},
      {id:"console",x:555,y:560,w:290,h:78,title:"后台调试是否正常？",subtitle:"回答、引用与 Trace",tone:"indigo",kind:"decision"},
      {id:"trace",x:1030,y:560,w:300,h:82,title:"检查 RAG Trace",subtitle:"阈值 · 候选 · Prompt",tone:"purple"},
      {id:"public",x:555,y:680,w:290,h:78,title:"只有访客端失败？",subtitle:"后台正常，公网异常",tone:"indigo",kind:"decision"},
      {id:"proxy",x:70,y:785,w:300,h:82,title:"检查公开链路",subtitle:"开关 · RBAC · Nginx · CDN",tone:"orange"},
      {id:"feature",x:1030,y:785,w:300,h:82,title:"检查具体业务",subtitle:"限流 · 日志 · 功能配置",tone:"green"},
    ],edges:[
      {from:"issue",to:"loaded"},{from:"loaded",to:"plugin",label:"否",tone:"orange"},{from:"loaded",to:"model",label:"是",tone:"green"},{from:"model",to:"network",label:"否",tone:"orange"},{from:"model",to:"index",label:"是",tone:"green"},{from:"index",to:"reindex",label:"否",tone:"orange"},{from:"index",to:"console",label:"是",tone:"green"},{from:"console",to:"trace",label:"否"},{from:"console",to:"public",label:"是",tone:"green"},{from:"public",to:"proxy",label:"是",tone:"orange"},{from:"public",to:"feature",label:"否",tone:"green"}
    ]
  },
  "request-routing": {
    title:"访客问题的双路径编排",subtitle:"快捷问题优先绑定路由；意图路径输出可信卡片，RAG 路径通过 AI Foundation 流式生成",height:700,
    nodes:[
      {id:"q",x:45,y:275,w:180,h:90,title:"访客请求",subtitle:"问题 · 历史 · 可选参数",tone:"blue"},
      {id:"endpoint",x:265,y:275,w:200,h:90,title:"公开问答接口",subtitle:"访客开关 · 参数校验 · 限流",tone:"indigo"},
      {id:"route",x:505,y:275,w:220,h:90,title:"路由解析",subtitle:"快捷路由优先 · 否则自动识别",tone:"purple"},
      {id:"pipeline",x:790,y:145,w:220,h:90,title:"意图处理管线",subtitle:"实时文章筛选与排序",tone:"orange"},
      {id:"intentresp",x:1060,y:145,w:245,h:90,title:"可信结构化结果",subtitle:"确定性导语 · 文章卡片 · 引用",tone:"orange"},
      {id:"rag",x:790,y:405,w:220,h:90,title:"RAG 检索管线",subtitle:"增强 · BM25 · HNSW · RRF",tone:"blue"},
      {id:"foundation",x:1060,y:405,w:245,h:90,title:"AI Foundation 模型网关",subtitle:"LlmClient · 对话模型 · 流式生成",tone:"purple"},
      {id:"sse",x:790,y:565,w:515,h:84,title:"两条路径统一 SSE 输出",subtitle:"引用 · 卡片 · 推理 · 回答片段 · 日志编号 · 异步日志",tone:"green"},
    ],edges:[{from:"q",to:"endpoint"},{from:"endpoint",to:"route"},{from:"route",to:"pipeline",label:"指定或命中意图",tone:"orange"},{from:"route",to:"rag",label:"未命中"},{from:"pipeline",to:"intentresp",tone:"orange"},{from:"rag",to:"foundation"}]
  },
  "frontend-boundaries": {
    title:"前后端与权限边界",subtitle:"三种浏览器入口分别进入公开或管理 API，再汇入同一服务层",height:560,
    groups:[{x:45,y:125,w:550,h:330,title:"BROWSER"},{x:650,y:125,w:705,h:330,title:"HALO JVM"}],
    nodes:[
      {id:"widget",x:80,y:165,w:210,h:78,title:"访客 Widget",subtitle:"原生 JS / CSS",tone:"blue"},
      {id:"console",x:80,y:270,w:210,h:78,title:"Vue Console",subtitle:"管理员页面",tone:"indigo"},
      {id:"editor",x:330,y:270,w:210,h:78,title:"Tiptap 扩展",subtitle:"编辑器写作辅助",tone:"purple"},
      {id:"public",x:700,y:165,w:240,h:78,title:"公开 Endpoint",subtitle:"匿名 RoleTemplate",tone:"green"},
      {id:"admin",x:700,y:300,w:240,h:78,title:"Console Endpoint",subtitle:"管理员认证",tone:"orange"},
      {id:"services",x:1040,y:230,w:260,h:90,title:"业务服务层",subtitle:"Chat · RAG · Intent · Writing",tone:"purple"},
    ],edges:[{from:"widget",to:"public",tone:"green"},{from:"console",to:"admin",tone:"orange"},{from:"editor",to:"admin",tone:"orange"},{from:"public",to:"services",tone:"green"},{from:"admin",to:"services"}]
  },
  "rrf-fusion": {
    title:"BM25、HNSW 与 RRF",subtitle:"用排名而不是不可比的原始分数，融合关键词和语义召回",height:480,
    nodes:[
      {id:"q",x:60,y:195,w:180,h:86,title:"查询",subtitle:"改写后 Query",tone:"slate"},
      {id:"bm25",x:330,y:120,w:220,h:86,title:"BM25 排名",subtitle:"专有名词与精确关键词",tone:"blue"},
      {id:"hnsw",x:330,y:270,w:220,h:86,title:"HNSW 排名",subtitle:"语义相似度",tone:"purple"},
      {id:"rrf",x:690,y:195,w:250,h:96,title:"RRF 融合",subtitle:"Σ 1 / (k + rank)",tone:"indigo"},
      {id:"ranked",x:1080,y:195,w:250,h:96,title:"统一候选顺序",subtitle:"去重后交给 Rerank / Top-N",tone:"green"},
    ],edges:[{from:"q",to:"bm25"},{from:"q",to:"hnsw"},{from:"bm25",to:"rrf"},{from:"hnsw",to:"rrf"},{from:"rrf",to:"ranked",tone:"green"}]
  },
  "index-lifecycle": {
    title:"索引生命周期",subtitle:"索引是派生数据；失败可重试，配置或内容变化后进入待更新状态",height:620,
    nodes:[
      {id:"none",x:80,y:230,w:190,h:86,title:"未索引",subtitle:"没有可检索切片",tone:"slate"},
      {id:"building",x:350,y:230,w:190,h:86,title:"构建中",subtitle:"切片 + Embedding + 写入",tone:"blue"},
      {id:"ready",x:650,y:155,w:200,h:86,title:"可检索",subtitle:"RAG 正常读取",tone:"green"},
      {id:"failed",x:650,y:350,w:200,h:86,title:"构建失败",subtitle:"保留错误并允许重试",tone:"red"},
      {id:"stale",x:990,y:155,w:200,h:86,title:"待更新",subtitle:"文章或配置已变化",tone:"orange"},
      {id:"clear",x:1100,y:350,w:200,h:86,title:"清除索引",subtitle:"回到未索引状态",tone:"slate"},
    ],edges:[
      {from:"none",to:"building",label:"重建"},{from:"building",to:"ready",label:"成功",tone:"green"},{from:"building",to:"failed",label:"失败",tone:"orange"},{from:"failed",to:"building",label:"修复后重试"},{from:"ready",to:"stale",label:"内容/配置变化",tone:"orange"},{from:"stale",to:"building",label:"同步/重建"},{from:"ready",to:"clear",label:"手动清除",tone:"orange"},{from:"clear",to:"none"}
    ]
  },
  "intent-detection": {
    title:"意图检测过程",subtitle:"启用路由按优先级排序并缓存 30 秒；失败或超时始终回到 RAG",height:680,
    nodes:[
      {id:"q",x:60,y:255,w:180,h:82,title:"用户问题",subtitle:"非空 Query",tone:"blue"},
      {id:"load",x:290,y:255,w:220,h:82,title:"加载启用路由",subtitle:"优先级降序 · 30s 缓存",tone:"slate"},
      {id:"regex",x:560,y:255,w:220,h:82,title:"正则是否命中？",subtitle:"CASE_INSENSITIVE + find",tone:"indigo",kind:"decision"},
      {id:"route",x:1100,y:145,w:230,h:82,title:"返回路由",subtitle:"进入 Pipeline",tone:"green"},
      {id:"fallback",x:470,y:410,w:220,h:82,title:"有 LLM 兜底？",subtitle:"llmFallback=true",tone:"indigo",kind:"decision"},
      {id:"llm",x:790,y:410,w:220,h:82,title:"LLM 意图分类",subtitle:"2 秒超时",tone:"purple"},
      {id:"rag",x:630,y:555,w:230,h:82,title:"返回 empty",subtitle:"进入默认 RAG",tone:"orange"},
    ],edges:[{from:"q",to:"load"},{from:"load",to:"regex"},{from:"regex",to:"route",label:"命中",tone:"green"},{from:"regex",to:"fallback",label:"未命中"},{from:"fallback",to:"llm",label:"有"},{from:"fallback",to:"rag",label:"没有",tone:"orange",fromSide:"bottom",toSide:"top"},{from:"llm",to:"route",label:"有效 ID",tone:"green"},{from:"llm",to:"rag",label:"失败 / none",tone:"orange",fromSide:"bottom",toSide:"top"}]
  },
  "processor-extension": {
    title:"新增 Pipeline Processor",subtitle:"处理器不是只加一个 Java 类，还需要同步校验、Console、测试和文档",height:430,
    nodes:[
      {id:"impl",x:45,y:165,w:200,h:90,title:"实现接口",subtitle:"PipelineProcessor",tone:"blue",badge:"JAVA"},
      {id:"type",x:275,y:165,w:190,h:90,title:"声明 type()",subtitle:"全局唯一标识",tone:"indigo"},
      {id:"bean",x:495,y:165,w:190,h:90,title:"Spring Bean",subtitle:"自动注入 Executor",tone:"purple"},
      {id:"validate",x:715,y:165,w:190,h:90,title:"保存校验",subtitle:"白名单与参数规则",tone:"orange"},
      {id:"ui",x:935,y:165,w:190,h:90,title:"Console 表单",subtitle:"动态参数编辑",tone:"blue"},
      {id:"test",x:1155,y:165,w:190,h:90,title:"测试与文档",subtitle:"试跑 · 单测 · 参考",tone:"green"},
    ],edges:[{from:"impl",to:"type"},{from:"type",to:"bean"},{from:"bean",to:"validate",tone:"orange"},{from:"validate",to:"ui"},{from:"ui",to:"test",tone:"green"}]
  },
  "builtin-intents": {
    title:"四个内置意图",subtitle:"每条路由由可组合处理器构成，管理员修改过的配置不会被随意覆盖",height:670,
    groups:[
      {x:45,y:120,w:640,h:430,title:"LATEST & HOT"},{x:715,y:120,w:640,h:430,title:"TAG & CATEGORY"}
    ],
    nodes:[
      {id:"latest",x:75,y:175,w:220,h:76,title:"最新文章",subtitle:"builtin-latest-posts",tone:"blue"},
      {id:"topic",x:360,y:150,w:250,h:76,title:"TOPIC_MATCH",subtitle:"主题综合匹配",tone:"purple"},
      {id:"time1",x:360,y:250,w:250,h:76,title:"TIME_SORT",subtitle:"desc · limit 10",tone:"green"},
      {id:"hot",x:75,y:400,w:220,h:76,title:"热门文章",subtitle:"builtin-hot-articles",tone:"orange"},
      {id:"visit",x:360,y:400,w:250,h:76,title:"VISIT_SORT",subtitle:"limit 10",tone:"green"},
      {id:"tag",x:745,y:175,w:220,h:76,title:"按标签",subtitle:"builtin-by-tag",tone:"blue"},
      {id:"tagmatch",x:1035,y:150,w:250,h:76,title:"TAG_MATCH",subtitle:"from_query",tone:"purple"},
      {id:"tagtime",x:1035,y:250,w:250,h:76,title:"TIME_SORT",subtitle:"desc · limit 10",tone:"green"},
      {id:"cat",x:745,y:400,w:220,h:76,title:"按分类",subtitle:"builtin-by-category",tone:"blue"},
      {id:"catmatch",x:1035,y:375,w:250,h:76,title:"CATEGORY_MATCH",subtitle:"from_query",tone:"purple"},
      {id:"cattime",x:1035,y:475,w:250,h:76,title:"TIME_SORT",subtitle:"desc · limit 10",tone:"green"},
    ],edges:[{from:"latest",to:"topic"},{from:"topic",to:"time1",tone:"green"},{from:"hot",to:"visit",tone:"green"},{from:"tag",to:"tagmatch"},{from:"tagmatch",to:"tagtime",tone:"green"},{from:"cat",to:"catmatch"},{from:"catmatch",to:"cattime",tone:"green"}]
  },
  "intent-example-flow": {
    title:"意图路由示例",subtitle:"“最近有哪些 AI 文章？”从规则命中到自然语言回答",height:430,
    nodes:[
      {id:"q",x:45,y:165,w:220,h:90,title:"用户问题",subtitle:"最近有哪些 AI 文章？",tone:"blue"},
      {id:"detect",x:305,y:165,w:210,h:90,title:"触发规则",subtitle:"命中最新文章意图",tone:"indigo"},
      {id:"topic",x:555,y:165,w:210,h:90,title:"TOPIC_MATCH",subtitle:"筛出 AI 主题文章",tone:"purple"},
      {id:"sort",x:805,y:165,w:210,h:90,title:"TIME_SORT",subtitle:"发布时间倒序",tone:"orange"},
      {id:"llm",x:1055,y:165,w:160,h:90,title:"LLM",subtitle:"组织回答",tone:"purple"},
      {id:"answer",x:1255,y:165,w:110,h:90,title:"结果",subtitle:"文字 + 引用",tone:"green"},
    ],edges:[{from:"q",to:"detect"},{from:"detect",to:"topic"},{from:"topic",to:"sort",tone:"orange"},{from:"sort",to:"llm"},{from:"llm",to:"answer",tone:"green"}]
  },
  "intent-priority": {
    title:"意图优先级设计",subtitle:"具体路由先于通用路由，宽泛兜底放在最后，避免通用规则截胡",height:500,
    nodes:[
      {id:"specific",x:80,y:140,w:330,h:82,title:"具体业务意图",subtitle:"Java 入门教程 · priority 200",tone:"green",badge:"FIRST"},
      {id:"general",x:535,y:220,w:330,h:82,title:"通用结构化意图",subtitle:"按标签查询 · priority 100",tone:"blue",badge:"SECOND"},
      {id:"broad",x:990,y:300,w:330,h:82,title:"宽泛兜底意图",subtitle:"语义分类 · priority 0",tone:"orange",badge:"LAST"},
    ],edges:[{from:"specific",to:"general",label:"未命中再继续"},{from:"general",to:"broad",label:"仍未命中"}],notes:[{x:80,y:430,text:"规则：越具体、误判成本越高的路由，优先级越高；最终仍未命中则进入 RAG。",color:"#CBD5E1",size:14}]
  },
};

const sequences = {
  "sse-proxy-flow": {
    title:"SSE 穿过反向代理",subtitle:"每一帧必须立即向浏览器转发，任何一层聚合都会造成“最后一次性显示”",height:650,
    actors:[{id:"browser",title:"浏览器",subtitle:"fetch stream",tone:"blue"},{id:"proxy",title:"Nginx / CDN",subtitle:"禁止缓冲",tone:"orange"},{id:"halo",title:"Halo 插件",subtitle:"SSE Endpoint",tone:"indigo"},{id:"llm",title:"模型服务",subtitle:"stream=true",tone:"purple"}],
    messages:[
      {from:"browser",to:"proxy",label:"POST /chat/stream"},{from:"proxy",to:"halo",label:"转发 JSON body"},{from:"halo",to:"llm",label:"Chat Completions stream"},{from:"llm",to:"halo",label:"token chunks",tone:"green",dashed:true},{from:"halo",to:"proxy",label:"citations / token / logId / DONE",tone:"green",dashed:true},{from:"proxy",to:"browser",label:"逐帧立即转发",tone:"green",dashed:true}
    ],note:{x:515,y:565,w:370,h:48,text:"关键：proxy_buffering off · CDN 不做响应聚合"}
  },
  "intent-pipeline": {
    title:"意图 Pipeline 执行时序",subtitle:"每一步消费上一步的文章集合，并把数量、参数和候选标题写入 Trace",height:760,rowGap:62,
    actors:[{id:"executor",title:"PipelineExecutor",tone:"indigo"},{id:"posts",title:"PostQuerySupport",tone:"blue"},{id:"p1",title:"Processor 1",tone:"purple"},{id:"p2",title:"Processor 2",tone:"orange"},{id:"trace",title:"PipelineTrace",tone:"green"}],
    messages:[
      {from:"executor",to:"posts",label:"查询公开文章"},{from:"posts",to:"executor",label:"初始 List<Post>",tone:"green",dashed:true},{from:"executor",to:"trace",label:"记录 fetch_posts",tone:"green"},{from:"executor",to:"p1",label:"候选 + query + params"},{from:"p1",to:"executor",label:"处理后候选",tone:"green",dashed:true},{from:"executor",to:"trace",label:"记录 in / out / posts",tone:"green"},{from:"executor",to:"p2",label:"上一步结果"},{from:"p2",to:"executor",label:"最终文章集合",tone:"green",dashed:true},{from:"executor",to:"trace",label:"记录最终步骤",tone:"green"}
    ]
  },
  "sse-event-sequence": {
    title:"访客聊天 SSE 事件顺序",subtitle:"引用、结构化结果和 logId 是命名事件，token 与 DONE 使用默认 data 事件",height:680,rowGap:64,
    actors:[{id:"client",title:"客户端",subtitle:"ReadableStream",tone:"blue"},{id:"api",title:"Chat Endpoint",subtitle:"text/event-stream",tone:"indigo"},{id:"service",title:"ChatService",subtitle:"Intent / RAG",tone:"purple"}],
    messages:[
      {from:"client",to:"api",label:"POST message + history"},{from:"api",to:"service",label:"开始流式问答"},{from:"service",to:"api",label:"引用 + 结构化结果 + token",tone:"green",dashed:true},{from:"api",to:"client",label:"event: citations（可选）",tone:"green",dashed:true},{from:"api",to:"client",label:"event: structured_result（可选）",tone:"green",dashed:true},{from:"api",to:"client",label:"data: {content: token}",tone:"green",dashed:true},{from:"api",to:"client",label:"event: logId",tone:"green",dashed:true},{from:"api",to:"client",label:"data: [DONE]",tone:"orange",dashed:true}
    ]
  },
  "first-rag-sequence": {
    title:"第一次 RAG 问答验证",subtitle:"从调试问题进入意图检测、混合检索、模型回答和可观察结果",height:760,rowGap:63,
    actors:[{id:"admin",title:"管理员",tone:"blue"},{id:"console",title:"调试页",tone:"indigo"},{id:"chat",title:"ChatService",tone:"purple"},{id:"intent",title:"IntentDetector",tone:"orange"},{id:"rag",title:"RAG + Lucene",tone:"blue"},{id:"llm",title:"Chat 模型",tone:"purple"}],
    messages:[
      {from:"admin",to:"console",label:"输入可验证问题"},{from:"console",to:"chat",label:"POST 调试请求"},{from:"chat",to:"intent",label:"检测意图"},{from:"intent",to:"chat",label:"未命中，进入 RAG",tone:"orange",dashed:true},{from:"chat",to:"rag",label:"检索上下文"},{from:"rag",to:"chat",label:"相关切片 + 引用",tone:"green",dashed:true},{from:"chat",to:"llm",label:"问题 + 上下文"},{from:"llm",to:"console",label:"SSE token",tone:"green",dashed:true},{from:"console",to:"admin",label:"回答 + 引用 + Trace",tone:"green",dashed:true}
    ]
  }
};

const stateSpecs = {
  "sse-client-state": {
    title:"SSE 客户端状态机",subtitle:"网络关闭不等同于正常结束；只有 DONE 才进入 Completed",height:520,
    nodes:[
      {id:"start",x:55,y:200,w:145,h:76,title:"开始",subtitle:"发起 POST",tone:"slate"},
      {id:"connecting",x:275,y:200,w:210,h:76,title:"Connecting",subtitle:"等待响应头",tone:"blue"},
      {id:"streaming",x:570,y:200,w:210,h:76,title:"Streaming",subtitle:"解析事件并增量渲染",tone:"indigo"},
      {id:"completed",x:940,y:125,w:210,h:76,title:"Completed",subtitle:"收到 [DONE]",tone:"green"},
      {id:"failed",x:940,y:300,w:210,h:76,title:"Failed",subtitle:"HTTP / 网络 / 解析错误",tone:"red"},
      {id:"end",x:1230,y:200,w:115,h:76,title:"结束",subtitle:"恢复输入",tone:"slate"},
    ],edges:[{from:"start",to:"connecting"},{from:"connecting",to:"streaming",label:"2xx + SSE"},{from:"connecting",to:"failed",label:"失败",tone:"orange"},{from:"streaming",to:"completed",label:"[DONE]",tone:"green"},{from:"streaming",to:"failed",label:"中断",tone:"orange"},{from:"completed",to:"end",tone:"green"},{from:"failed",to:"end",tone:"orange"}]
  }
};

for (const [name, spec] of Object.entries(diagrams)) {
  writeFileSync(resolve(outputDir, `${name}.svg`), renderFlow(spec), "utf8");
}
for (const [name, spec] of Object.entries(sequences)) {
  writeFileSync(resolve(outputDir, `${name}.svg`), renderSequence(spec), "utf8");
}
for (const [name, spec] of Object.entries(stateSpecs)) {
  writeFileSync(resolve(outputDir, `${name}.svg`), renderFlow(spec), "utf8");
}

console.log(`Rendered ${Object.keys(diagrams).length + Object.keys(sequences).length + Object.keys(stateSpecs).length} SVG diagrams to ${outputDir}`);
