# Buddy 固定版本规则与对照样本

此工具从已授权上游的固定提交 `016f048b5c636035ac0b56b0c4b9d89a742fba4a` 获取八个必要文件。下载首先校验已提交来源清单中的 SHA-256，再执行该固定版本的无 DOM 核心；不会加载 `main` 或下载站点 HTML。原始源码只保存到被忽略的 `data/research/buddy-pinned/<revision>/`。

需要 Node.js 24 和网络访问：

```powershell
node --experimental-vm-modules tools/buddy-rules/build.mjs
node --experimental-vm-modules tools/buddy-rules/build.mjs --check
node --experimental-vm-modules tools/buddy-rules/build.mjs --check --offline
```

`--check` 重建并逐字节比较产物，不覆盖已提交规则或样本。规则改变必须单独审阅，不能直接更新 Kotlin 的校验和来绕过差异。

已下载固定源码时可用 `--offline`，仍逐文件验证来源清单哈希；缺文件或哈希不符直接失败，不偷偷换版本。在线请求各自设置 20 秒超时，网络失败不覆盖已提交产物。

## 提交内容

- `core-domain/src/main/resources/buddy/rules-v1.json`：约 42 KB 的必要成长规则，包含 74 个上游名册条目、64 个职业、20 种可分配坐骑的映射与计算参数。不含图片、Wiki 或原始游戏全表。
- `sources-v1.json`：提交版本、来源 URL/哈希、生成文件哈希、缺失映射和计数。
- `core-domain/src/test/resources/buddy/baseline-v1.json`：1,032 组直接由上游 `eventOf` / `classAllowed` / `simulateLevel` 生成的对照，以及 12 组由原版 `queueRows` 生成的十步预测（含跳过记录）。并非用户备份或真实个人数据。

`BuddyRules.bundled()` 校验应用内置规则的固定 SHA-256；每次计算拒绝其他规则修订。九项属性固定映射为具名 `GrowthStat`，不按界面排列猜索引。计算保留个人零成长回退、职业等级临界、坐骑倍率/类型/路线限制与篇章保底。

## 当前边界

映射完全遵循固定版本 `personId` 的名字映射结果，包括两个伊修玛尔名册条目的上游行为；不以名字相似自行修正角色。`c065/c068/c069/c070` 在该版本没有对应成长规则，列入来源清单并拒绝计算，不填默认值。

`growth` 保留上游历史事件计算语义，另外返回职业合法性和实际生效坐骑；`forecast` 要求职业合法。不可用但已知的坐骑按上游不骑乘处理，并返回明确提示。未知角色、职业、坐骑或规则版本拒绝计算。

Android 的“工具 → 已知种子预测”是只读预览：用户输入种子、升级前等级和固定配置，预测后续十次或到 99 级；不计属性封顶，不改导入存档。领域层另测了跳过只消耗种子、不升级的队列。完整反推、导入记录回放、预测行修正、职业/考试推荐仍未完成，也未做真机布局或性能验收。

来源授权与原游戏权利边界见根目录 `THIRD_PARTY_NOTICES.md`。
