# Experiential Labs 平台文档 · 完整摘要

> 来源：https://platform.experientiallabs.ai/docs（全部 23 个页面，已抓取至 `%TEMP%\exlabs_docs\`）
> 定位：**OpenAI 兼容模型网关**——一个 base URL 前置所有模型（托管供应商 / 自带密钥 BYOK / 平台积分 / 自建模型），改掉 base_url 和 key 即可迁移，Chat Completions 与 Responses API（含流式）以及 Anthropic Messages API 全部支持。

---

## 1. Get started（入门）

### Overview（概览）
- 核心：base-url 替换。`https://api.experientiallabs.ai/v1` + `xpl_` 密钥，OpenAI 协议原样可用。
- 模型按 slug 调用（如 `claude-opus-5`），slug 经 **provider waterfall**（按序尝试的供应商路由链）解析，容量/传输错误自动故障转移。
- 两条付费通道（均零加价）：**BYOK 透传**（自己的供应商密钥，供应商直接计费）与 **平台积分**（按公开目录价扣减余额）。
- 网页端能做的一切，代理（agent）都能用同一个组织 API key 通过 API 完成。

### Quickstart（快速开始）
- 登录 → Settings → API Keys 建密钥（`xpl_...` 仅显示一次）→ `export EXPLABS_API_KEY=xpl_...`
- 首次调用：`POST /v1/chat/completions`；流式加 `stream: true`；Responses API 在 `/v1/responses`。
- `previous_response_id` 可在任意 worker 上续接；过期/未知 id 返回 `400 previous_response_not_found`。

### The core loop（核心闭环）
1. 建密钥（网页动作，API key 不能建/吊销 key）
2. `GET /v1/models` 列出可调用的 slug
3. `POST /v1/chat/completions` 照常调用
4. `GET /api/gateway/usage/daily?org_id=...&scope=org&group_by=day` 读用量；`GET /api/gateway/catalog` 看别名与通道解析

### Authentication（认证）
- 一个组织 key 走天下：`Authorization: Bearer <key>`；形如 `xpl_` + 40 位小写 hex。
- 例外：`GET /api/models*` 公开目录免密钥（带 key 则附加本组织私有行）；`/v1/messages` 额外接受 `x-api-key`（Anthropic SDK 兼容）。
- 一个 key 能做：推理、目录读写、自定义模型、瀑布编辑、BYOK 连接、用量读取、org key 列表。
- 一个 key 不能做：铸/吊销 API key、改其他 key 的限额、平台管理路由。
- 认证失败统一 401 `code=invalid_key`，不区分缺失/格式/过期/吊销。

### Setup prompts（设置提示词）
- 一组可直接粘贴给编码代理的第一人称提示词：从代理内创建账号（`POST /api/signup/instant`，错误码 409/403/428/429/400 逐一处理）、在现有项目接入网关、给各种编码代理接线、从 OpenRouter/其他供应商一键迁移、用 provisioning key 配置 identities。
- 机器可读版在 `/llms.txt`。

---

## 2. Guides（指南）

### Models（模型）
- 目录 = 公开行 + 本组织自定义/本地模型。`GET /api/models` 支持 modality/category/provider/min_context/价格/supports/retention 过滤与 preferred/price/age/context/throughput 排序。
- **区域可用性**：OpenAI/Anthropic/Gemini 模型校验请求 IP 所在地区，不符返回 `403 model_location_not_supported`；该规则跟随供应商回退、转售与 BYOK 全程生效。
- **Experiential Cloud**：平台自托管精选模型，同 slug 调用。
- **两条通道**：`customer_managed`（BYOK）与 `host_managed`（平台积分，运营侧种入，绝不自声明）。
- 统计来源标注：`stats_source='openrouter'`（种子估计）→ 30 天内足量请求后变 `'observed'`（实测）；`pricing_source='estimate'` 仅展示、绝不计费。
- ZDR 可浏览：每行带 `retention` 判定（`zdr_all_rungs` / `zdr_enforceable` / `not_zdr`），可用 `?retention=` 过滤。

### The waterfall（瀑布路由）
- slug → 有序 rung（路由级）列表；每 rung 可开关（Use toggle）、可拖动排序；至少保持一个开启。
- 公共目录模型的开关仅是显示偏好；组织自有模型的顺序才是路由链。
- 服务取"最高的可用开启 rung"；认证/传输/供应商错误可下移；BYOK 连接不健康则剔除直至恢复；供应商限速 **不** 故障转移（保护热缓存）；跨供应商广谱故障转移是默认关闭的可选项。
- BYOK 密钥可带 **fallback rules**（仅在上方 rung 以选定失败类型失败后才拨号，绝不首发）。
- **提示缓存**是路由属性（带 cache 标签与 Cached $/M 价格），无组织级开关；回退可能丢热缓存。
- "Add a way" 三选项：加 API key（BYOK，任意套餐）、Serve it yourself（Pro，自有端点按模型原生方言中转）、Add a local model（Pro，注册 OpenAI 兼容服务器为组织私有模型）。
- **Free rung**：促销免费层固定在链顶，展示专用；"Past the free limit" 开关默认关（超出即停），开启后超量走积分（需绑卡 + $1 验证）。

### Adding models（添加模型）
- **BYOK**：`PUT /api/orgs/{org_id}/provider-connections/{provider}` 单次 upsert + `/check` 验证；密钥只写不读（仅回显末 4 位）。各供应商字段：openai/anthropic/gemini/openrouter 需要 API key；fireworks 加 account id；azure_openai 要 key+endpoint+api_version+部署映射；bedrock 要 AWS 凭证+区域；vertex 要 service-account JSON+project+location；modal 要 base_url+token 对。
- **Local model**：`POST /api/models` 注册 OpenAI 兼容服务器（slug、base_url、supported_params 声明、endpoint_api_key 可选），Pro 功能。
- **Serve it yourself**：把现有模型路由到自有端点（保持模型原生方言与响应形状），加前做一次活体验证，Bedrock/Vertex/Azure/Gemini 类需要专属凭证的不支持。

### Data controls（数据控制）
- **服务路径本身零内容**：计量账本仅存元数据（数据库约束拒绝正文）。
- 内容存储窗口：捕获的 prompt/响应（≤1MiB/4MiB，30 天）与 Responses 续接（30 天）受组织级 `capture_prompt_content` 开关管（默认开；关闭需 Pro，关闭即追溯删除）；batch 文件 48h/到期后 24h；Idempotency-Key 重放窗口 24h 仅在内存。BYOK 请求任何套餐都绝不留内容。
- 开关 API：`GET/PUT /api/orgs/<org_id>/telemetry-settings`（Free 关闭被 403 `needs_subscription`）。
- **供应商策略**（每组织一行）：`allowed_providers`（企业版）、`require_zdr`、`require_no_training`、`allowed_processing_regions`；rung 粒度预派发过滤，全部 rung 被滤除时 403 `model_not_granted`。
- **ZDR 矩阵**：bedrock/fireworks/experiential_cloud/zai/novita(自托管)/modal/local = ZDR；openai/azure/anthropic/gemini/vertex/tencent/qwen/cerebras/wafer/xai = 非 ZDR；openrouter 需 `require_zdr` 时强制 `provider.zdr + data_collection=deny` 约束派发。Claude Fable/Mythos 全系无 ZDR 路由（Anthropic 强制 30 天留存）。
- **一键 ZDR**：`PUT /api/orgs/<org_id>/zero-data-retention {"enabled":true}` 同时关闭存储 + 打开两个路由开关（单事务、审计）；GET 返回 enabled/partial/facts/models_with_zdr_route。
- **每请求 ZDR**：body 顶层 `provider: {"zdr": true}`（只收紧不放松），成功响应带 `x-gateway-zdr: true`。
- **溯源头**：`x-request-id`、`x-gateway-provider`、`x-gateway-zdr`、`x-gateway-route-depth`、`x-gateway-route-reason`、`x-gateway-canonical-model`、`x-gateway-alias(-revision)`；正文顶层 provider 字段与 usage.cost/usage.is_byok 同帧（幂等重放除外）。
- 事后查询：`GET /api/v1/generation?id=<x-request-id>`；`GET /api/gateway/usage/events`（每请求 provider/lane/attempt_count）；`GET /api/v1/usage` 结算导出。
- 诚实披露：无租户级 ZDR 证书、无供应商签名逐响应证明、失败尝试不对外、区域过滤≠全平台驻留、元数据始终保留。

### OpenAI compatibility（OpenAI 兼容性）
- 三类处理：**Honored**（rung 支持则遵守）/ **丢弃并披露**（`x-experiential-ignored-parameters`）/ **拒绝**（400 `unsupported_parameter` 或 `invalid_parameter`）。
- 所有路由均拒绝：audio、modalities、logit_bias、seed、functions/function_call、prediction、prompt_cache_retention、OpenRouter 式 provider/route 对象。
- **Web 搜索全路由可服务**：Chat `web_search_options`、OpenRouter `plugins:[{id:"web"}]` 与 `:online` 后缀、Responses `web_search` 工具、Anthropic `web_search_*` 全等价；非原生路由由网关跑 Exa 搜索（默认 $0.007/次，计入 usage.cost，BYOK 不收）；搜索失败不失败请求。
- **Tool search**：`openrouter:tool_search` + `defer_loading: true` 延迟加载数百工具，BM25/正则匹配后同请求内重拨，最多 3 轮，客户端只见最终 tool call。Responses 用 `{"type":"tool_search"}`，Messages 用 `tool_search_tool_bm25_20251119` / `tool_search_tool_regex_20251119`。
- **结构化输出**：`response_format` json_object/json_schema（Responses 用 text.format）；rung 能力靠探测习得，不能服务则跳过 rung，全军覆没 400 `unsupported_capability`；绝不静默降级；非 OpenAI 供应商要 `strict: true`。
- **提示缓存**：供应商侧实现；`prompt_cache_key` 作为路由提示不外传；cached_tokens 原样回传（Chat/Responses/Messages 各字段名）。
- **可复用推理**：`include:["reasoning.encrypted_content"]` 加密推理项多轮回放；跨供应商/组织封印的重放会被剥离并 `x-gateway-replay-repair: encrypted_reasoning_stripped` 披露。
- 供应商回退：承诺前（未提交）才回退；限速且最近流量多为缓存命中时返回 429 保热缓存；`provider.order`/`route` 拒收。
- 常见门禁错误：429 insufficient_quota / card_required / free_limit_reached / model_requires_payment(_purchase)；400 unsupported_capability / unsupported_parameter。

### Anthropic API（Anthropic 接口）
- `POST /v1/messages` 完整翻译到共享聊天面：任何目录模型（不只 Claude）都能被 Anthropic SDK/Claude Code 调用。
- 流式为标准 Anthropic SSE；错误用 Anthropic 信封但语义同错误表。
- 翻译边界：extended thinking 全 Anthropic 路由原样透传（签名块往返），非 Anthropic 推理路由翻译为 reasoning effort（披露 `thinking->reasoning_effort:<tier>`），无推理路由丢弃披露；cache_control 标记在支持的适配器上保留；图片每请求最多 100 块；PDF 需支持文档的路由；`tool_result.is_error` 在非原生线折叠进结果文本；Idempotency-Key 不支持；`/v1/messages/count_tokens` 回网关自估。
- Claude Code：`ANTHROPIC_BASE_URL=https://api.experientiallabs.ai`（无 /v1 后缀）+ `ANTHROPIC_API_KEY`；无键 HEAD `/api/hello` 探活按 Anthropic 原样回 200。

### Errors（错误）
- 统一 OpenAI 信封，按稳定 `code` 分支：`model_location_not_supported`(403)、`invalid_json/invalid_request/invalid_parameter`(400)、`unsupported_capability`（整能力缺失→换模型）vs `unsupported_parameter`（单字段被拒→删字段）、`refusal`（策略拒，额外 `refusal_reason` 封闭词表：cyber_policy/cbrn/content_policy/recitation/data_inspection/unspecified）、`previous_response_not_found`、`invalid_key`(401)、`model_not_granted`(403，注意 slug 是点式 `claude-fable-5.1` 不是 Anthropic 短横线 id)、`idempotency_conflict`(409)、`insufficient_quota`(429，非瞬态)、`org_under_review`(429 隔离审查)、`unavailable_route`(429/503)、`gateway_overloaded`、`provider_internal`(502 上游已收单后死)、**`empty_completion`（200 + `x-gateway-warning` 头，模型空回合，别原样重试，改 last turn 或换模型）**、`all_routes_failed`(502)、`provider_output_too_large`、`gateway_draining`(503)、`deadline_exceeded`(504)。
- insufficient_quota 前缀词表：free_limit_reached / free_tier_requires_payment / model_requires_payment / model_requires_purchase / promo_byok_only / org_rate_limit / org_token_rate_limit(_hour/_day) / key_daily_cap / insufficient_credits——各自恢复路径明确。
- 重试规则：429/502/503/504 指数退避；400/401/403/409 先修请求；歧义网络失败可能双计费 → 用 `Idempotency-Key` 头获得字节级重放。
- **Claude Code 误导症状对照表**："Not logged in · Please run /login"（其实是无键或拒绝过自定义 key，绝不 /login，用 --resume）、403 alias（点式 slug）、502 provider stream failed（上游偶发，重试 10 次）、未知模型 200k 假设（设 `CLAUDE_CODE_MAX_CONTEXT_TOKENS=max_input_tokens` 向下取整，绝不用 context_window）、compaction 失败（新会话）、/model picker 藏 Fable（settings.json 手写点式 slug）。

---

## 3. Integrations（集成）

### Integrate the gateway（接入网关）
- 两大粘贴即用提示词：**从当前供应商/网关切换**（找现有集成→换 base URL→模型 id 映射→迁成本读取→验证）与**产品集成**（客户端接线→`safety_identifier`/`user` 做端客户归因→结算用量导出对接 Metronome/Orb/Stripe 计费→可选 key-per-customer）。
- TypeSafe Jev 决策模型：`POST /v1/systemone` 原生端点（choice/noul/score 三问题类型；≤32 问题/请求、64 选项、2-10 级；≈32K 输入 token 上限；无流式、无聊天 facade、无幂等）。
- `/api/v1` 兼容面：`POST /chat/completions`、`/responses`（含 WebSocket 升级）、`/messages`、`GET /models`（`<vendor>/<slug>` 与 `:free` 拼写）；`GET /api/v1/models/{author}/{slug}/endpoints` 看瀑布；`GET /api/v1/providers` 供应商列表。省略字段一律 null/缺省，绝不造假。

### Cost API（成本 API）
- 三种读法（由轻到重）：① 每响应内联 `usage.cost`（USD；幂等键请求除外，读作可选）；② `GET /api/v1/generation?id=<x-request-id>` 单请求详情（org 域，404 即非本组织）；③ **结算用量导出** `GET /api/v1/usage`（账单同步用）：keyset 游标翻旧页、每 run 从头扫 + 按 row id 去重、窗口 24h/7d/30d（默认 7d）、30 天外不可导出。
- 行字段：id/created_at/model/provider/attribution_label/real_cost_usd（=平台扣费+BYOK 估算）/cost_usd（平台积分）/estimated_cost_usd/pricing_known/token 四项/status/api_key_id。
- `safety_identifier`（旧别名 `user`）→ 归因标签 `attribution_label`，一 key 按客户分组。
- BYOK 结算 cost 0 + `is_byok:true` + `usage.cost_details.upstream_inference_cost` 为供应商侧归属成本。
- 对账：日 sum(cost_usd) 应与 `/api/v1/credits` 的 total_usage 日变动吻合；null 归因行要呈现不要丢。
- 计费外置：本地 outbox 表（幂键=row id）→ 独立投递步 → Metronome/Orb/Stripe metered（margin 放计费系统，同步只送原始成本）。
- 余额：`GET /api/v1/credits`（total_credits − total_usage）；活动 `GET /api/v1/activity`。新组织欢迎赠金但需邮箱验证或首笔卡扣才解锁支出。

### Account API（账户 API）
- **inference key vs provisioning key**：同一 `xpl_` 前缀 + `is_provisioning` 能力位；provisioning 才能访问 `/api/v1/keys*` 全家族（含 GET）与 identities/budgets 路由，普通推理 key 一律 403。首个 provisioning key 由组织管理员在面板铸。
- `GET /api/v1/key` 读当前 key 的 usage/limit/limit_remaining（日上限）。
- Key CRUD：`GET/POST/PATCH/DELETE /api/v1/keys`（{hash}=行 uuid 非 secret；POST 一次性返回明文；PATCH `disabled:true` 吊销终态；body: name/limit(日 USD 上限)/provisioning/identity_id）。
- **Identities**（命名支出桶）：`GET/POST/PATCH /api/orgs/{org_id}/identities`（id 正则 `^[a-z][a-z0-9]*([._-][a-z0-9]+)*$`、平台级唯一、409 回 suggested_id）；key 挂唯一 identity → 用量按 identity 汇总。
- **Budgets**：`GET/PUT/DELETE /api/orgs/{org_id}/budgets`（周期 `*` 或 YYYY-MM；scope identity|key；limit_nano_usd，$1=1e9；0=硬零上限；key 只能替换/删自建行，默认 identity 与 admin 行 403）。
- 按身份用量：`GET /api/orgs/{org_id}/usage/by-identity?window=24h|7d|30d`。
- 泄露的 provisioning key 也无法改模型访问、冻结或解冻组织。

### Coding agents（编码代理）
- 通用三件套：base URL `https://api.experientiallabs.ai/v1`、`Authorization: Bearer`、裸 slug。**`export` 关键词是承重墙**（shell 局部变量子进程不可见）。
- **先隔离验证**：两 curl 冒烟（列模型+一次小补全），再每个代理用真实 MCP/插件工具面在一次性环境（mktemp 目录/throwaway CODEX_HOME/临时 HOME）里跑"列出工具+读 README 首标题"的真工具回环，通过才动真配置。
- 各代理落点：
  - **Codex CLI**：`~/.codex/config.toml` `[model_providers.explabs]`（wire_api="responses" 唯一支持值）；`model_reasoning_effort="max"` 顶配（ultra 被拒）；别设 requires_openai_auth。
  - **OpenCode**：`opencode.json` provider 块（@ai-sdk/openai-compatible）；limit/cost 要手填（catalog nano-USD ÷ 1e9）。
  - **Hermes Agent**：`~/.hermes/config.yaml` + `~/.hermes/.env`；api_mode chat_completions；会话标题副调用带 temperature 的坑已说明。
  - **Pi**：`~/.pi/agent/models.json`；apiKey 字面量 `$EXPLABS_API_KEY` 运行时插值；models.json 每次 /model 重载。
  - **Cline**：UI 设置 OpenAI Compatible。
  - **VS Code Copilot**：`chatLanguageModels.json` customendpoint 条目；`modelOptions: {temperature: null, top_p: null}` 是承重的（null 删字段，否则 Copilot 硬发 0.1/1 被钉采样模型 400）；key 存 VS Code 加密密钥库。
  - **Cursor**：需付费套餐；Settings→Models→API Keys + Override Base URL；请求经 Cursor 服务器中转；Tab 补全仍走 Cursor。
  - **Claude Code**：ANTHROPIC_BASE_URL（无 /v1）+ ANTHROPIC_API_KEY（**绝不用 AUTH_TOKEN**，claude.ai OAuth 会压过它）；点式 slug；非 Anthropic 模型设 `CLAUDE_CODE_MAX_CONTEXT_TOKENS`（=max_input_tokens 向下取整到 5 万）；`[1m]` 后缀可用 1M 窗口；OpenRouter 式拼写 `vendor/slug`、`:free` 兼容；thinking 配置翻译；每回合成本读 message_delta 而非 Claude Code 自算总成本。
  - **Conductor**：repo 级 `.claude/settings.json` env 块（committed）+ key 放 git-ignore 的 `.conductor/settings.local.toml`；picker 别名用 `ANTHROPIC_DEFAULT_<ALIAS>_MODEL` 重映射。
- **Key 轮换安全序**：铸新 → 隔离验证 → 换配置开新会话 → 才吊销旧 key（顺序颠倒=代理当场断粮）。
- 一 key 一代理即可在用量流按工具拆分支出。

---

## 4. Billing & usage（计费与用量）

### Credits & billing（积分与计费）
- 两条通道零加价；credit = 1 美分别名，一切按积分计。
- Free 账户周期性赠金从 **$1 卡验证**（退还余额）开始；30 天计划是"补足至 500"而非额外 500；Pro 用购买配额替换 Free 补给。
- **账户池轮换**：同供应商多账号成池，额度尽/持续限速（15 分钟内限速风暴；单次限速不轮换以保热缓存）时轮换，每 5 分钟出判定，成功 key 检查可复活账号。API：`.../provider-connections/accounts/usage`、`.../accounts/{setup_alias}/routing`、`.../provider-connections/reorder`。
- 支出三层控制：per-key（日花费/RPM/TPM）、budget（月度或循环，scope 团队/key/模型/identity/路由池）、spend alerts。`GET /api/gateway/keys/{id}/limits` 读生效限额（null=不设限）。
- **免费层**：今日 gpt-6-astra（50 万输入/10 万输出每日；20 万/4 万每小时）与 claude-fable-5.1（37.5 万/7.5 万日；10 万/3 万小时）；缓存输入不计入；超限 429 不烧积分；credits overflow 打开后按目录价走积分（org 级开关，首次真实支付自动开启，或 POST /api/credits-overflow 手动开，未验证 402）。
- `service_tier:"flex"` 对 gpt-5.6-sol 透传 OpenAI flex 并按 50% 计；不支持则 400 unsupported_capability。

### Spend & intelligence（支出与智能）
- Spend 面板（管理员）：金额纳美分整数（1e9=$1）；一个 Spend 图含积分+BYOK 估值+免费列表价；请求 vs 供应商尝试分开计数（尝试含重试/回退并按供应商分摊成本）。
- 维度过滤：模型/API key/成员/端用户标签/供应商/资金通道/API 面/结果/错误/重复 prompt/来源；标签 `X-Explabs-Tags: {"run":"..."}` 头（≤16 项 8KiB，key ≤64 字符，`explabs.` 前缀保留）。
- CSV 导出全分组（≤1 万组）；快照 24 小时有效；结算时点用量，>15 分钟标记旧快照。
- Intelligence（Pro 只读、花积分）：对聚合证据提问；证据缺口明示不脑补；失败/取消运行可仍计模型用量；对账走网关账本不双扣。
- API 同源：`POST /api/orgs/<org_id>/spend/query|facets|series|activity|export|latest|snapshot|builds`。
- 隐私：标签/标签值里绝不放密钥、prompt、PII；隐私擦除保留匿名金额。

### Telemetry（遥测）
- `GET /api/gateway/usage/daily`（group_by: day/day_model/model/member；spend_nano_usd）与 `GET /api/gateway/usage/events`（分页逐请求流）。
- **自带痕迹**：`POST /api/orgs/<org_id>/telemetry/traces/pull`（braintrust/langsmith/langfuse/posthog/mastra/postgres，凭证一次性用不落库）或 upload（保留式签名 URL 两小时 + finalize 验证 50MB/格式/SHA-256；source_kind: otel-genai/otlp/langfuse/langsmith/phoenix/braintrust/mastra/posthog/chat-json）；Arize/Phoenix 走 upload。

---

## 5. Providers（供应商侧）

### Become a provider（成为供应商）
- 自助通道仅收公共 OpenAI 兼容 HTTPS 端点；原生 SDK 集成属手工对接。
- 你控制：**价格**（整数纳美分/百万 token，含缓存/推理/服务层费率卡；降价审批即生效，涨价 ≥7 天生效）、**容量**（自报限额，随时 429 拒单不掉惩罚）、**生命周期**（加模型/计划性弃用/整体下线）。
- 收入进平台 provider wallet（扣除约定平台费），按请求 payouts。
- 注册双门：**manifest PR**（对 github.com/experientiallabs/platform 提交 providers/<slug>/manifest.json；CI 免凭证校验 schema/身份守卫/:free 绑定/流式必备/价格合理性/可达性探测）或**平台内申请**（org 管理员在 Inference 页提交）。
- Manifest schema 要点：provider{slug/display_name/family="openai_compatible"/base_url(公网 https ≤2048 无 query/fragment/私网)/auth_mode(house_key|none)/contact/terms(带时区时间戳)/data_policy(必填四值)} + 可选 limits/models（wire_id 唯一、binding existing|new、prices 纳美分整数、capabilities 白名单词汇表且 supports_streaming 必须真）。
- 禁忌：PR 内绝无私密、绝不冒绑他人 wire id、绝不编价格/能力/数据政策（具合同约束力）。
- 合并后：管理员关联组织→密钥带外交换入 Vault→canary 殿后（仅上方 lane 失败/卸载时接流量）→观测数据驱动晋升 live；日常变更走 Inference 页审批制，不再走 PR。

### Provider guide（供应商指南）
- 已入驻供应商的操作参考（模型、价格、canary、429、钱包），仅对 provider 组织显示；注册见上一页。

---

## 6. Reference（参考）

### API reference（API 参考）
- 推理 `/v1`：GET /v1/models；POST /v1/chat/completions；POST /v1/responses（previous_response_id 任意 worker）；POST /v1/messages（Anthropic）。
- 目录与自定义 `/api`：GET /api/models（公开免 key）、GET /api/models/{slug}、GET /api/models/{slug}/providers、POST /api/models、POST /api/models/{slug}/providers、GET|PUT /api/models/{slug}/waterfall（共享模型的自链必须含自己的 byok rung）。
- 供应商连接：GET list / PUT connect（或轮换）/ POST check / POST spend-refresh。
- 用量与 key：GET /api/gateway/usage/daily、usage/events、catalog、keys/{id}/limits；GET /api/keys。
- 边缘别名：GUI 工具追加到裸主机的 /models、/chat/completions、/responses、/messages、/batches、/files 自动映射 /v1 孪生路径。
- 机器可读全量参考：`/llms.txt`（含完整免费层配额、错误表、核心闭环）。

---

## 速查卡片

| 任务 | 调用 |
|---|---|
| 列可调模型 | `GET https://api.experientiallabs.ai/v1/models` |
| 聊天补全 | `POST /v1/chat/completions`（或 `/api/v1/` 同形） |
| Responses | `POST /v1/responses` |
| Anthropic/Claude Code | `POST /v1/messages`，`ANTHROPIC_BASE_URL=https://api.experientiallabs.ai` |
| 单请求成本 | `GET /api/v1/generation?id=<x-request-id>` |
| 余额 | `GET /api/v1/credits` |
| 结算导出（计费同步） | `GET /api/v1/usage?limit=1000` |
| 用量日汇总 | `GET /api/gateway/usage/daily?org_id=...&scope=org` |
| 读/写瀑布 | `GET|PUT /api/models/{slug}/waterfall` |
| 连 BYOK | `PUT /api/orgs/{org_id}/provider-connections/{provider}` |
| 开 ZDR（组织级） | `PUT /api/orgs/{org_id}/zero-data-retention {"enabled":true}` |
| 每请求 ZDR | body `"provider": {"zdr": true}` |
| 开关内容捕获 | `PUT /api/orgs/<org_id>/telemetry-settings {"capture_prompt_content": false}` |
| 决策模型 Jev | `POST /v1/systemone` |
