from __future__ import annotations

import json
import re
import sqlite3
from typing import Any

# Canonical Simplified Chinese names for Fire Emblem: Three Houses & Engage

TH_CHARACTERS: dict[str, str] = {
    "Alois": "阿罗伊斯·兰贾尔特",
    "Alois Rangeld": "阿罗伊斯·兰贾尔特",
    "Anna": "安娜",
    "Annette": "雅妮特·方提努·多米尼克",
    "Ashe": "亚修·多蓝",
    "Balthus": "巴鲁塔札尔·冯·亚达尔布雷希特",
    "Bernadetta": "贝尔娜提塔·冯·瓦立",
    "Byleth": "贝雷特 / 贝雷丝",
    "Byleth (F)": "贝雷丝（女）",
    "Byleth (M)": "贝雷特（男）",
    "Caspar": "卡斯帕尔·冯·贝尔谷里斯",
    "Catherine": "卡多莉奴",
    "Claude": "库罗德·冯·里刚",
    "Constance": "康丝坦洁·冯·努维尔",
    "Cyril": "锥里尔",
    "Dedue": "杜笃·莫利纳洛",
    "Dimitri": "帝弥托利·亚历山大·布雷达德",
    "Dorothea": "多洛缇雅·雅尔诺尔德",
    "Edelgard": "艾黛尔贾特·冯·弗雷斯贝尔古",
    "Felix": "菲力克斯·尤果·伏拉鲁达力乌斯",
    "Ferdinand": "菲尔迪南特·冯·艾吉尔",
    "Flayn": "芙莲",
    "Gilbert": "吉尔伯特·普罗尼斯拉夫",
    "Hanneman": "汉尼曼·冯·艾萨",
    "Hapi": "哈琵",
    "Hilda": "希尔妲·凡伦汀·哥纳利尔",
    "Hubert": "修伯特·冯·贝斯特拉",
    "Ignatz": "伊古纳兹·维克特",
    "Ingrid": "英谷莉特·布兰多尔·贾拉提雅",
    "Jeritza": "伊艾里扎·冯·弗琉慕",
    "Leonie": "雷欧妮·皮聂利",
    "Linhardt": "林哈尔特·冯·海弗林格",
    "Lorenz": "洛廉兹·海尔曼·古罗斯塔尔",
    "Lysithea": "莉丝缇亚·冯·科迪利亚",
    "Manuela": "玛努艾拉·卡扎古蓝达",
    "Marianne": "玛莉安奴·冯·艾德蒙",
    "Mercedes": "梅尔赛德司·冯·马尔特利兹",
    "Petra": "佩托拉·马克聂阿利",
    "Raphael": "拉斐尔·克里斯汀",
    "Rhea": "蕾雅",
    "Seteth": "西提司",
    "Shamir": "萨米亚·聂芙兰特",
    "Sothis": "苏谛斯",
    "Sylvain": "希尔凡·乔瑟·戈迪耶",
    "Yuri": "尤里斯·路克雷",
    # Non-playable / Bosses / Backstory
    "Jeralt": "杰拉尔特",
    "Rodrigue": "罗德里古",
    "Judith": "茱迪特",
    "Nader": "纳戴尔",
    "Randolph": "兰道夫",
    "Ladislava": "拉迪丝拉瓦",
    "Fleche": "芙蕾契",
    "Kostas": "科斯塔斯",
    "Lonato": "罗纳托",
    "Miklan": "迈克朗",
    "Metodey": "梅托杰",
    "Tomas": "托马斯",
    "Solon": "索龙",
    "Kronya": "克罗妮艾",
    "Thales": "塔烈斯",
    "Cornelia": "科涅莉亚",
    "Arundel": "阿伦戴尔",
    "Aelfric": "阿尔法卢特",
    "Seiros": "赛罗司",
    "Nemesis": "涅墨西斯",
    "Lambert": "兰伯特",
    "Acheron": "阿凯隆",
    "Pallardó": "帕拉尔多",
    "Gwendal": "格温达尔",
    "Myson": "迈森",
    "Odesse": "奥德塞",
    "Volkhard": "沃尔克哈德",
    "Duke Aegir": "艾吉尔公爵",
    "Count Bergliez": "贝尔谷里斯伯爵",
    "Count Hevring": "海弗林格伯爵",
    "Duke Ifan": "伊凡公爵",
    "Baron Gillingr": "吉林格尔男爵",
    "Baron Dominic": "多米尼克男爵",
    "Viscount Lochin": "洛钦子爵",
    "Holst": "霍尔斯特",
    "Alm": "阿鲁姆",
    "Celica": "赛莉卡",
    "Chrom": "库洛姆",
    "Corrin (female)": "神威（女）",
    "Corrin (male)": "神威（男）",
    "Ike": "艾克",
    "Lucina": "露琪娜",
    "Marth": "马尔斯",
    "Robin": "鲁弗莱",
    "Roy": "罗伊",
    "Tiki": "琪姬",
}

TH_CHAR_SHORT: dict[str, str] = {
    "Alois": "阿罗伊斯",
    "Anna": "安娜",
    "Annette": "雅妮特",
    "Ashe": "亚修",
    "Balthus": "巴鲁塔札尔",
    "Bernadetta": "贝尔娜提塔",
    "Byleth": "贝雷特 / 贝雷丝",
    "Byleth (F)": "贝雷丝（女）",
    "Byleth (M)": "贝雷特（男）",
    "Caspar": "卡斯帕尔",
    "Catherine": "卡多莉奴",
    "Claude": "库罗德",
    "Constance": "康丝坦洁",
    "Cyril": "锥里尔",
    "Dedue": "杜笃",
    "Dimitri": "帝弥托利",
    "Dorothea": "多洛缇雅",
    "Edelgard": "艾黛尔贾特",
    "Felix": "菲力克斯",
    "Ferdinand": "菲尔迪南特",
    "Flayn": "芙莲",
    "Gilbert": "吉尔伯特",
    "Hanneman": "汉尼曼",
    "Hapi": "哈琵",
    "Hilda": "希尔妲",
    "Hubert": "修伯特",
    "Ignatz": "伊古纳兹",
    "Ingrid": "英谷莉特",
    "Jeritza": "伊艾里扎",
    "Leonie": "雷欧妮",
    "Linhardt": "林哈尔特",
    "Lorenz": "洛廉兹",
    "Lysithea": "莉丝缇亚",
    "Manuela": "玛努艾拉",
    "Marianne": "玛莉安奴",
    "Mercedes": "梅尔赛德司",
    "Petra": "佩托拉",
    "Raphael": "拉斐尔",
    "Rhea": "蕾雅",
    "Seteth": "西提司",
    "Shamir": "萨米亚",
    "Sothis": "苏谛斯",
    "Sylvain": "希尔凡",
    "Yuri": "尤里斯",
    "Jeralt": "杰拉尔特",
    "Rodrigue": "罗德里古",
    "Judith": "茱迪特",
    "Nader": "纳戴尔",
    "Randolph": "兰道夫",
    "Kostas": "科斯塔斯",
    "Hubert": "修伯特",
    "Acheron": "阿凯隆",
}

TH_FACTIONS_AND_ROUTES: dict[str, str] = {
    "Black Eagles": "黑鹫学级",
    "Blue Lions": "青狮子学级",
    "Golden Deer": "金鹿学级",
    "Church of Seiros": "赛罗司圣教会",
    "Knights of Seiros": "赛罗司骑士团",
    "Church of Seiros / Knights of Seiros": "赛罗司圣教会 / 骑士团",
    "Ashen Wolves": "灰狼学级",
    "Updates / DLC": "更新与 DLC 角色",
    "Playable characters": "主角 / 初始可控角色",
    "Cindered Shadows": "煤暗之章",
    "Cindered Shadows DLC": "煤暗之章（DLC）",
    "Black Eagles / Silver Snow": "黑鹫学级 / 银雪之章",
    "Black Eagles / Crimson Flower": "黑鹫学级 / 红花之章",
    "Blue Lions / Azure Moon": "青狮子学级 / 苍月之章",
    "Golden Deer / Verdant Wind": "金鹿学级 / 翠风之章",
    "Silver Snow": "银雪之章",
    "Azure Moon": "苍月之章",
    "Verdant Wind": "翠风之章",
    "Crimson Flower": "红花之章",
    "Part I": "第一部（白云之章）",
    "Part II": "第二部",
    "Paralogues": "外传",
    "Main story": "主线章节",
    "DLC campaign": "DLC 剧情篇章",
}

TH_CLASSES: dict[str, str] = {
    "Commoner": "平民",
    "Noble": "贵族",
    "Myrmidon": "剑士",
    "Soldier": "士兵",
    "Fighter": "战士",
    "Monk": "修士",
    "Lord": "领主",
    "Mercenary": "佣兵",
    "Thief": "盗贼",
    "Armored Knight": "重骑士",
    "Cavalier": "轻骑士",
    "Brigand": "土匪",
    "Archer": "弓箭手",
    "Brawler": "拳斗士",
    "Mage": "魔道士",
    "Dark Mage": "暗魔道士",
    "Priest": "修道士",
    "Pegasus Knight": "天马骑士",
    "Hero": "勇者",
    "Swordmaster": "刀剑将领",
    "Assassin": "刺客",
    "Fortress Knight": "堡垒骑士",
    "Paladin": "圣骑士",
    "Wyvern Rider": "飞龙骑士",
    "Warrior": "勇士",
    "Sniper": "狙击手",
    "Grappler": "格斗家",
    "Warlock": "术士",
    "Dark Bishop": "暗主教",
    "Bishop": "主教",
    "Trickster": "骗术师",
    "War Monk , War Cleric": "武僧 / 战修女",
    "War Monk": "武僧",
    "War Cleric": "战修女",
    "Dark Flier": "暗天马",
    "Valkyrie": "女武神",
    "Falcon Knight": "圣天马骑士",
    "Wyvern Lord": "飞龙将领",
    "Mortal Savant": "墓志铭剑士",
    "Great Knight": "重装骑士",
    "Bow Knight": "弓骑士",
    "Dark Knight": "暗黑骑士",
    "Holy Knight": "神圣骑士",
    "War Master": "战争将领",
    "Gremory": "格莫瑞",
    "Dancer": "舞者",
    "Enlightened One": "涅槃那",
    "Armored Lord": "重装领主",
    "Emperor": "皇帝",
    "High Lord": "上级领主",
    "Great Lord": "大领主",
    "Wyvern Master": "飞龙统领",
    "Barbarossa": "巴巴罗萨",
    "Archbishop": "大司教",
    "Death Knight": "死神骑士",
    "Flame Emperor": "炎帝",
    "Agastya": "阿加斯提亚",
    "Saint": "圣者",
    "Ruffian": "暴徒",
    "Verrat": "叛逆者",
    "Lord of the Desert": "沙漠之王",
    "Lord of the Lake": "湖之王者",
    "King of Beasts": "百兽之王",
    "King of Fangs": "利牙之王",
    "King of Liberation": "解放之王",
    "King of Wings": "羽翼之王",
    "Hegemon Husk": "霸铠之骸",
    "The Immaculate One": "纯白无瑕者",
    "Black Beast": "黑兽",
    "White Beast": "白兽",
    "Wandering Beast": "流浪魔兽",
    "Demonic Beast": "魔兽",
    "Altered Demonic Beast": "变异魔兽",
    "Exp. Demonic Beast": "实验魔兽",
    "Flying Demonic Beast": "飞行魔兽",
    "Giant Demonic Beast": "巨大魔兽",
    "Wild Demonic Beast": "狂暴魔兽",
    "Giant Bird": "巨鸟",
    "Giant Crawler": "巨大爬虫",
    "Giant Wolf": "巨狼",
    "Golem": "哥雷姆",
    "Altered Golem": "变异哥雷姆",
    "Guardian Golem": "守护哥雷姆",
    "Titanus": "泰坦巨兵",
    "Umbral Beast": "影之魔兽",
}

TH_CRESTS: dict[str, str] = {
    "Aubin": "欧班之纹章",
    "Blaiddyd": "布雷达德之纹章",
    "Cethleann": "圣赛斯琳之纹章",
    "Charon": "卡戎之纹章",
    "Chevalier": "雪巴里埃之纹章",
    "Cichol": "圣奇霍尔之纹章",
    "Crest of Flames": "炎之纹章",
    "Daphnel": "达夫内尔之纹章",
    "Dominic": "多米尼克之纹章",
    "Ernest": "艾尔内斯特之纹章",
    "Fraldarius": "伏拉鲁达力乌斯之纹章",
    "Gautier": "戈迪耶之纹章",
    "Gloucester": "古罗斯塔尔之纹章",
    "Goneril": "哥纳利尔之纹章",
    "Indech": "圣因迪哈之纹章",
    "Lamine": "拉米奴之纹章",
    "Macuil": "圣马库伊尔之纹章",
    "Noa": "诺亚之纹章",
    "Riegan": "里刚之纹章",
    "Seiros": "圣赛罗司之纹章",
    "The Beast": "野兽之纹章",
    "Timotheos": "提莫特之纹章",
}

TH_WEAPON_CATEGORIES: dict[str, str] = {
    "Sword": "剑术",
    "Lance": "枪术",
    "Axe": "斧术",
    "Bow": "弓术",
    "Brawling": "格斗术",
    "Gauntlet": "格斗",
    "Reason": "理学",
    "Faith": "信仰",
    "Authority": "指挥",
    "Heavy Armor": "重装",
    "Riding": "马术",
    "Flying": "飞行",
    "Black magic": "黑魔法",
    "Dark magic": "暗魔法",
    "White magic": "白魔法",
}

TH_SKILLS_AND_ARTS: dict[str, str] = {
    # Personal & common abilities
    "Professor's Guidance": "师长指导",
    "Professor's Guidance+": "师长指导＋",
    "Imperial Lineage": "皇帝血脉",
    "Imperial Lineage+": "皇帝血脉＋",
    "Royal Lineage": "王者血脉",
    "Royal Lineage+": "王者血脉＋",
    "Leicester Lineage": "雷斯塔血脉",
    "Leicester Lineage+": "雷斯塔血脉＋",
    "Lady Knight": "骑士仪范",
    "Officer Duty": "骑士团长",
    "Honorable Spirit": "武士道",
    "Compassion": "慈悲",
    "Songstress": "歌姬",
    "Confidence": "自信满满",
    "Catnap": "午睡",
    "Persecution Complex": "被害妄想",
    "Watchful Eye": "观察眼",
    "Distinguished House": "名门贵族",
    "Fighting Spirit": "喧哗上等",
    "Born Fighter": "天生战士",
    "Philanderer": "好色",
    "Lone Wolf": "独行侠",
    "Staunch Shield": "主君之盾",
    "Live to Serve": "奉仕精神",
    "Goody Basket": "点心时间",
    "Lockpick": "开锁",
    "Advocate": "女神之加护",
    "Perseverance": "克己",
    "Mastermind": "天才",
    "Animal Friend": "动物之友",
    "Lily's Poise": "白百合的加护",
    "Rivalry": "不服输",
    "Veteran Knight": "历战骑士",
    "Crest Scholar": "纹章学者",
    "Infirmary Master": "保健室达人",
    "Guardian": "守护者",
    "Survival Instinct": "生存本能",
    "Aptitude": "良师",
    "Business Prosperity": "生意兴隆",
    "Hunter's Boon": "狩猎之理",
    "King of Grappling": "格斗王",
    "Circadian Beat": "随性节奏",
    "Monstrous Appeal": "魔物魅力",
    "Murderous Intent": "杀意",
    # Class / Equip / Learnables
    "Aegis": "圣盾",
    "Pavise": "大盾",
    "Ancient Dragon Wrath": "太古龙之怒",
    "Ancient Dragonskin": "太古龙鳞",
    "Anew": "再动",
    "Apocalypse": "默示录",
    "Archballista": "超级弩炮",
    "Armor Disruptor": "破甲一击",
    "Armorcrush": "碎甲击",
    "Armored Blow": "金刚一击",
    "Assassinate": "暗杀",
    "Astra": "流星",
    "Astral Blade": "星辰之刃",
    "Avo +10": "回避＋10",
    "Axe Crit +10": "斧必杀＋10",
    "Axefaire": "斧专家",
    "Axebreaker": "斧克制",
    "Swordbreaker": "剑克制",
    "Lancebreaker": "枪克制",
    "Bowbreaker": "弓克制",
    "Tomebreaker": "魔道克制",
    "Black Magic Range +1": "黑魔法射程＋1",
    "Black Magic Uses x2": "黑魔法次数×2",
    "Black Tomefaire": "黑魔法专家",
    "Dark Magic Range +1": "暗魔法射程＋1",
    "Dark Magic Uses x2": "暗魔法次数×2",
    "Dark Tomefaire": "暗魔法专家",
    "White Magic Heal +5": "白魔法回复量＋5",
    "White Magic Heal +10": "白魔法回复量＋10",
    "White Magic Uses x2": "白魔法次数×2",
    "White Tomefaire": "白魔法专家",
    "Bowfaire": "弓专家",
    "Bowrange +1": "弓射程＋1",
    "Bowrange +2": "弓射程＋2",
    "Brawl Avo +20": "格斗回避＋20",
    "Canto": "再移动",
    "Celestial Bow": "天穹之弓",
    "Charm": "魅力",
    "Close Counter": "近距离反击",
    "Commander": "统帅",
    "Conjure": "召唤",
    "Coral Cover": "珊瑚庇护",
    "Counterattack": "全距离反击",
    "Crit +20": "必杀＋20",
    "Crosswise Cut": "十字斩",
    "Curved Shot": "曲射",
    "Dance": "舞蹈",
    "Special Dance": "特别舞蹈",
    "Darting Blow": "飞燕一击",
    "Death Blow": "鬼神一击",
    "Fiendish Blow": "魔神一击",
    "Warding Blow": "明镜一击",
    "Uncanny Blow": "慧眼一击",
    "Duelist's Blow": "清流一击",
    "Defense +2": "防守＋2",
    "Resistance +2": "魔防＋2",
    "Speed +2": "速度＋2",
    "Strength +2": "力量＋2",
    "Magic +2": "魔力＋2",
    "HP +5": "HP＋5",
    "Dexterity +4": "技巧＋4",
    "Defensive": "守备阵型",
    "Defiant Avo": "觉醒回避",
    "Defiant Crit": "觉醒必杀",
    "Defiant Def": "觉醒防守",
    "Defiant Mag": "觉醒魔力",
    "Defiant Res": "觉醒魔防",
    "Defiant Spd": "觉醒速度",
    "Defiant Str": "觉醒力量",
    "Desperation": "攻立",
    "Destreza": "灵巧剑",
    "Double Lion": "双狮斩",
    "Dragonhaze": "龙穿",
    "Draw Back": "拉回",
    "Shove": "推击",
    "Swap": "替身",
    "Reposition": "带回",
    "Smite": "强力推击",
    "Duelist Sword": "决斗剑技",
    "Earth's Boon": "大地加护",
    "Earth's Kiss": "大地之吻",
    "Encloser": "围弓箭",
    "Enhanced Fortitude": "强化坚韧",
    "Entrap": "困陷",
    "Expel": "驱逐",
    "Fierce Iron Fist": "猛烈铁拳",
    "Fistfaire": "格斗专家",
    "Lancefaire": "枪专家",
    "Swordfaire": "剑专家",
    "Sword Crit +10": "剑必杀＋10",
    "Flamberge": "焰形剑",
    "Flickering Flower": "风花雪月",
    "Foudroyant": "轰雷",
    "Foudroyant Strike": "轰雷一击",
    "Foul Play": "诈术",
    "Freeze": "冰冻",
    "Grounder": "飞燕斩",
    "Heartseeker": "咒缚",
    "Heaven or Hell": "生死对决",
    "Heavy Draw": "强弓",
    "Hexblade": "魔法剑",
    "Hit +20": "命中＋20",
    "Hit and Run": "一击脱离",
    "Hunter's Volley": "猎箭齐发",
    "Invoke": "召唤魔兽",
    "Knightkneeler": "葬骑一击",
    "Lemegeton": "所罗门秘术",
    "Leonine Poise": "狮子之姿",
    "Lethality": "瞬杀",
    "Lifetaker": "生命吸收",
    "Locktouch": "开锁",
    "Longearche": "长弓射击",
    "Lucky Seven": "幸运七",
    "Lunar Flash": "月光闪",
    "Lunge": "切入",
    "Magic Bind": "魔法束缚",
    "Manifest Phantom": "召唤幻影",
    "Medusa": "美杜莎",
    "Miracle": "祈祷",
    "Mistdancer": "雾舞",
    "Monster Effect Null": "魔物特攻无效",
    "Ocular Beam": "魔眼射线",
    "Oculus": "凝视之眼",
    "Overrun": "碾压突击",
    "Paraselene": "幻月",
    "Pass": "穿越",
    "Penetrate": "贯穿突刺",
    "Plenitude": "盈满",
    "Pneuma Gale": "风神疾风",
    "Poison": "施毒",
    "Poison Strike": "蛇毒",
    "Pomp & Circumstance": "威风凛凛",
    "Quick Riposte": "切反",
    "Renewal": "回复",
    "Rewarp": "远距传送",
    "Roundhouse": "回旋踢",
    "Sacred Power": "圣者之力",
    "Scendscale": "霸天",
    "Seal Defense": "封闭防守",
    "Seal Magic": "封闭魔力",
    "Seal Resistance": "封闭魔防",
    "Seal Speed": "封闭速度",
    "Seal Strength": "封闭力量",
    "Shadow Gambit": "暗影计策",
    "Solar Thrust": "烈日突刺",
    "Solo Triangle Attack": "单人三角攻击",
    "Triangle Attack": "三角攻击",
    "Speculum": "镜反",
    "Steal": "盗窃",
    "Stealth": "忍足",
    "Subdue": "手下留情",
    "Sunder": "必杀一击",
    "Surging Light": "奔流圣光",
    "Tempest Lance": "旋风枪",
    "Terrain Resistance": "地形耐性",
    "Thunderclap": "雷鸣斩",
    "Tigerstance": "虎势",
    "Trance Shot": "恍惚射击",
    "Transmute": "魔力转换",
    "Twin Crests": "双纹章",
    "Unarmed Combat": "徒手格斗",
    "Vantage": "埋伏",
    "Vendetta": "复仇血战",
    "War Master's Strike": "战将一击",
    "Ward Arrow": "封魔箭",
    "Weight -3": "负重－3",
    "Weight -5": "负重－5",
    "Wind God": "风神",
    "Windsweep": "风薙",
    "Wrath": "愤怒",
    "Wrath Strike": "魔击",
    # Extra Combat Arts & Authority
    "Armored Strike": "重甲一击",
    "Bane of Monsters": "魔物斩",
    "Battalion Desperation": "骑士团攻立",
    "Battalion Renewal": "骑士团回复",
    "Battalion Vantage": "骑士团埋伏",
    "Battalion Wrath": "骑士团愤怒",
    "Bombard": "连打",
    "Break Shot": "碎甲箭",
    "Deadeye": "狙击",
    "Diamond Axe": "钻石斧",
    "Draining Blow": "吸魂拳",
    "Exhaustive Strike": "竭力一击",
    "Fading Blow": "退避拳",
    "Finesse Blade": "秘剑",
    "Focused Strike": "专注一击",
    "Glowing Ember": "炽热一击",
    "Haze Slice": "阳炎",
    "Healing Focus": "冥想",
    "Helm Splitter": "兜割",
    "Lance Jab": "迅枪",
    "Lightning Axe": "雷斧",
    "Mighty Blow": "强力拳",
    "Model Leader": "模范领队",
    "Monster Blast": "破魔一击",
    "Monster Breaker": "碎魔斧",
    "Monster Crusher": "碎魔拳",
    "Monster Piercer": "贯魔枪",
    "Mystic Blow": "魔拳",
    "Nimble Combo": "灵巧连击",
    "One-Two Punch": "左右连拳",
    "Point-Blank Volley": "近距离连射",
    "Rally Charm": "魅力应援",
    "Rally Defense": "防守应援",
    "Rally Dexterity": "技巧应援",
    "Rally Luck": "幸运应援",
    "Rally Magic": "魔力应援",
    "Rally Movement": "移动应援",
    "Rally Resistance": "魔防应援",
    "Rally Speed": "速度应援",
    "Rally Strength": "力量应援",
    "Shatter Slash": "碎甲突刺",
    "Smash": "击碎",
    "Soulblade": "魔刃",
    "Spike": "尖刺突刺",
    "Swift Strikes": "连击",
    "Vengeance": "复仇",
    "Waning Shot": "衰弱射击",
    "Wild Abandon": "舍身一击",
    # Magic (shared across skills & equipment)
    "Fire": "火焰",
    "Bolganone": "火山爆发",
    "Ragnarok": "诸神黄昏",
    "Ragnarok Ω": "诸神黄昏Ω",
    "Thunder": "雷电",
    "Thoron": "托尔之锤",
    "Bolting": "远雷",
    "Wind": "风刃",
    "Cutting Gale": "烈风",
    "Excalibur": "埃克斯卡利伯",
    "Blizzard": "暴雪",
    "Fimbulvetr": "芬布尔之冬",
    "Sagittae": "光箭",
    "Meteor": "陨石",
    "Agnea's Arrow": "阿格尼亚之箭",
    "Miasma": "瘴气",
    "Miasma Δ": "瘴气Δ",
    "Mire Β": "泥沼Β",
    "Mire": "泥沼",
    "Banshee Θ": "报丧女妖Θ",
    "Death Γ": "死神Γ",
    "Death": "死神",
    "Dark Spikes Τ": "暗黑冲击Τ",
    "Luna Λ": "月光Λ",
    "Hades Ω": "哈迪斯Ω",
    "Swarm Ζ": "虫群Ζ",
    "Bohr Χ": "玻尔Χ",
    "Quake Σ": "地震Σ",
    "Heal": "回复",
    "Nosferatu": "吸魂",
    "Recover": "痊愈",
    "Physic": "远程回复",
    "Seraphim": "炽天使",
    "Ward": "圣盾术",
    "Silence": "沉默",
    "Restore": "状态恢复",
    "Rescue": "救援",
    "Aura": "灵气",
    "Abraxas": "阿布拉克萨斯",
    "Fortify": "全体回复",
    "Warp": "传送",
}

TH_EQUIPMENT_EXACT: dict[str, str] = {
    "Unarmed": "空手",
    # Heroes' Relics & Sacred Weapons
    "Sword of the Creator": "天帝之剑",
    "Dark Creator Sword": "暗天帝之剑",
    "Sublime Creator Sword": "天帝之霸剑",
    "Areadbhar": "阿雷德巴尔",
    "Areadbhar Γ": "阿雷德巴尔Γ",
    "Aymr": "艾姆鲁",
    "Failnaught": "费尔诺特",
    "Failnaught Τ": "费尔诺特Τ",
    "Lance of Ruin": "破裂之枪",
    "Dark Lance of Ruin": "暗破裂之枪",
    "Crusher": "粉碎之锤",
    "Dark Crusher": "暗粉碎之锤",
    "Freikugel": "魔弹射手",
    "Freikugel Λ": "魔弹射手Λ",
    "Thunderbrand": "雷霆",
    "Dark Thunderbrand": "暗雷霆",
    "Blutgang": "布鲁特刚",
    "Dark Blutgang": "暗布鲁特刚",
    "Lúin": "卢因",
    "Lúin Δ": "卢因Δ",
    "Thyrsus": "提尔索斯之杖",
    "Dark Thyrsus": "暗提尔索斯之杖",
    "Rafail Gem": "拉斐尔之宝珠",
    "Dark Rafail Gem": "暗拉斐尔之宝珠",
    "Aegis Shield": "埃奎斯之盾",
    "Dark Aegis Shield": "暗埃奎斯之盾",
    "Fetters of Dromi": "德罗米之锁链",
    "Vajra-Mushti": "金刚杵",
    "Sword of Seiros": "赛罗司之剑",
    "Sword of Begalta": "贝加尔塔之剑",
    "Sword of Moralta": "莫拉尔塔之剑",
    "Spear of Assal": "阿萨尔之枪",
    "Axe of Ukonvasara": "乌孔瓦萨拉之斧",
    "The Inexhaustible": "无尽之弓",
    "Tathlum Bow": "塔特拉姆之弓",
    "Caduceus Staff": "卡杜凯乌斯之杖",
    "Ochain Shield": "欧钦之盾",
    "Seiros Shield": "赛罗司之盾",
    # Special & Zoltan / Regalia
    "Mercurius": "墨丘利",
    "Gradivus": "古拉迪乌斯",
    "Hauteclere": "奥特克莱尔",
    "Parthia": "帕提亚",
    "Sword of Zoltan": "索尔坦之剑",
    "Lance of Zoltan": "索尔坦之枪",
    "Axe of Zoltan": "索尔坦之斧",
    "Bow of Zoltan": "索尔坦之弓",
    "Rapier": "细剑",
    "Wo Dao": "倭刀",
    "Cursed Ashiya Sword": "妖刀阿修罗",
    "Ridill": "里迪尔",
    "Athame": "阿萨姆匕首",
    "Levin Sword": "闪电剑",
    "Killing Edge": "必杀剑",
    "Armorslayer": "破甲剑",
    "Arrow of Indra": "因陀罗之箭",
    "Horseslayer": "斩马枪",
    "Crescent Sickle": "新月镰",
    "Javelin": "投枪",
    "Short Spear": "短矛",
    "Spear": "长矛",
    "Hand Axe": "手斧",
    "Short Axe": "短斧",
    "Tomahawk": "战斧",
    "Hammer": "战锤",
    "Mace": "钉锤",
    "Bolt Axe": "雷电斧",
    "Mini Bow": "迷你弓",
    "Longbow": "长弓",
    "Magic Bow": "魔法弓",
    "Dragon Claws": "龙爪",
    "Aura Knuckles": "魔拳套",
    "Killer Knuckles": "必杀拳套",
    "Scythe of Sariel": "萨里埃尔之镰",
    "Lance of Light": "圣光之枪",
    "Piercing Light Lance": "贯光圣枪",
    "Throwing Light Lance": "投掷圣枪",
    "Chalice of Beginnings": "始祖之圣杯",
    "Chalice of Blood": "血之圣杯",
    "Healing Staff": "治愈之杖",
    "Magic Staff": "魔力之杖",
    "Circe Staff": "喀耳刻之杖",
    "Tomas's Staff": "托马斯之杖",
    "Asclepius": "阿斯克勒庇俄斯",
    "Ballista": "弩炮",
    "Fire Orb": "魔导炮台",
    "Onager": "投石机",
    "Viskam": "维斯卡姆",
    # Shields & Rings
    "Leather Shield": "皮盾",
    "Iron Shield": "铁盾",
    "Steel Shield": "钢盾",
    "Silver Shield": "银盾",
    "Hexlock Shield": "封魔盾",
    "Aurora Shield": "极光盾",
    "Emperor Shield": "皇帝盾",
    "Kadmos Shield": "卡德摩斯之盾",
    "Lampos Shield": "兰波斯之盾",
    "Flame Shield": "炎之盾",
    "Talisman Shield": "护符盾",
    "Armored Knight Shield": "重骑士之盾",
    "Armored Soldier Shield": "重步兵之盾",
    "Fortress Soldier Shield": "堡垒兵之盾",
    "Accuracy Ring": "命中戒指",
    "Critical Ring": "必杀戒指",
    "Evasion Ring": "回避戒指",
    "Goddess Ring": "女神戒指",
    "March Ring": "行军戒指",
    "Prayer Ring": "祈祷戒指",
    "Speed Ring": "速度戒指",
    "Black Eagle Pendant": "黑鹫吊坠",
    "Blue Lion Brooch": "青狮子胸针",
    "Golden Deer Bracelet": "金鹿手环",
    "White Dragon Scarf": "白龙围巾",
    "Experience Gem": "经验宝珠",
    "Knowledge Gem": "知识宝珠",
    # Seals & Items
    "Beginner Seal": "初级考试准考证",
    "Intermediate Seal": "中级考试准考证",
    "Advanced Seal": "高级考试准考证",
    "Master Seal": "最高级考试准考证",
    "Dark Seal": "暗魔法考试准考证",
    "Abyssian Exam Pass": "阿比斯考试准考证",
    "Combined Seal": "合用准考证",
    "Vulnerary": "伤药",
    "Concoction": "调合药",
    "Elixir": "特效药",
    "Antitoxin": "解毒药",
    "Pure Water": "圣水",
    "Torch": "火把",
    "Door Key": "门钥匙",
    "Chest Key": "宝箱钥匙",
    "Master Key": "万能钥匙",
    "Spellbreak Key": "破咒钥匙",
    "Bullion": "金块",
    "Large Bullion": "大金块",
    "Extra Large Bullion": "特大金块",
    # Stat Boosters
    "Seraph Robe": "天使之衣",
    "Energy Drop": "力量水滴",
    "Spirit Dust": "精灵之粉",
    "Secret Book": "秘传之书",
    "Speedwing": "疾风之羽",
    "Goddess Icon": "女神像",
    "Talisman": "魔防护符",
    "Shoes of the Wind": "风之靴",
    "Black Pearl": "黑珍珠",
    "Ambrosia": "神酿",
    "White Verona": "白维罗纳",
    "Ailell Pomegranate": "阿莱尔石榴",
    "Rocky Burdock": "岩石牛蒡",
    "Premium Magic Herbs": "高级魔草",
    "Golden Apple": "金苹果",
    "Fruit of Life": "生命果实",
    "Speed Carrot": "疾风胡萝卜",
    "Miracle Bean": "奇迹豆",
    "Sacred Floral Robe": "圣花之衣",
    "Sacred Galewind Shoes": "圣风之靴",
    "Sacred Moonstone": "圣月长石",
    "Sacred Snowmelt Drop": "圣雪融水滴",
    # Materials / Monster weapons / Powers
    "Smithing Stone": "锻造石",
    "Black-Sand Steel": "黑砂钢",
    "Arcane Crystal": "魔导结晶",
    "Giant Shell": "巨型甲壳",
    "Giant Katar": "巨型拳刃",
    "Sharp Wings": "锋利羽翼",
    "Trade Secret": "秘密商业书",
    "Crest Stone": "纹章石",
    "Crest Stone Shard": "纹章石碎片",
    "Blest Crest Stone Shard": "祝福纹章石碎片",
    "Cracked Crest Stone": "碎裂纹章石",
    "Lost Crest Stone": "失落纹章石",
    "Lost Crest Stone (Lg)": "失落纹章石（大）",
    "Artificial Crest Stone S": "人造纹章石（小）",
    "Artificial Crest Stone L": "人造纹章石（大）",
    "Giant Art. Crest Stone": "巨大人工纹章石",
    "Pointy Art. Crest Stone": "尖锐人工纹章石",
    "Real Seiros Crest Stone": "圣赛罗司真纹章石",
    "Crest Stone of Indech": "圣因迪哈纹章石",
    "Crest Stone of Macuil": "圣马库伊尔纹章石",
    "Crest Stone of Seiros": "圣赛罗司纹章石",
    "Crest Stone (Beast)": "纹章石（野兽）",
    "Crest Stone (Chevalier)": "纹章石（雪巴里埃）",
    "Crest Stone (Gautier)": "纹章石（戈迪耶）",
    "Dark Stone (Bird)": "暗黑石（巨鸟）",
    "Dark Stone (Crawler)": "暗黑石（爬虫）",
    "Dark Stone (Wolf)": "暗黑石（巨狼）",
    "Twin-Crest Power": "双纹章之力",
    "Crest of Flames Power (Normal)": "炎之纹章之力（普通）",
    "Crest of Flames Power (Hard)": "炎之纹章之力（困难）",
    "Crest of Flames Power (Maddening)": "炎之纹章之力（超难）",
    "Venin Edge": "剧毒剑",
}

TH_MATERIAL_PREFIXES: dict[str, str] = {
    "Training": "训练用",
    "Iron": "铁",
    "Steel": "钢",
    "Silver": "银",
    "Brave": "勇者",
    "Killer": "必杀",
    "Blessed": "祝福",
    "Devil": "恶魔",
    "Broken": "破损的",
    "Rusted": "生锈的",
    "Venin": "剧毒",
}

TH_WEAPON_NOUNS: dict[str, str] = {
    "Sword": "剑",
    "Lance": "枪",
    "Axe": "斧",
    "Bow": "弓",
    "Gauntlets": "拳套",
    "Gauntlet": "拳套",
    "Edge": "剑",
}

TH_DRAGON_SIGNS: dict[str, str] = {
    "Flame": "炎之龙印",
    "Aegis": "拉米奴之龙印",
    "Bloom": "达夫内尔之龙印",
    "Craft": "古罗斯塔尔之龙印",
    "Crusher": "多米尼克之龙印",
    "Dark": "卡戎之龙印",
    "Earth": "赛罗司之龙印",
    "Fissure": "戈迪耶之龙印",
    "Grim": "布雷达德之龙印",
    "Ice": "奇霍尔之龙印",
    "Kalpa": "雪巴里埃之龙印",
    "Light": "赛斯琳之龙印",
    "Lightning": "欧班之龙印",
    "Shield": "伏拉鲁达力乌斯之龙印",
    "Sky": "马库伊尔之龙印",
    "Snow": "哥纳利尔之龙印",
    "Star": "里刚之龙印",
    "Storm": "艾尔内斯特之龙印",
    "Thorn": "提莫特之龙印",
    "Water": "因迪哈之龙印",
    "Wind": "诺亚之龙印",
}

TH_SOCIAL_EXACT: dict[str, str] = {
    # Gifts
    "Ancient Coin": "古代硬币",
    "Anemone": "银莲花",
    "Arithmetic Textbook": "算术书",
    "Armored Bear Stuffy": "重甲熊布偶",
    "Baby's Breath": "满天星",
    "Blue Cheese": "蓝纹奶酪",
    "Board Game": "棋盘游戏",
    "Book of Crest Designs": "纹章图鉴",
    "Book of Sheet Music": "乐谱集",
    "Carnation": "康乃馨",
    "Ceremonial Sword": "仪式用剑",
    "Coffee Beans": "咖啡豆",
    "Daffodil": "黄水仙",
    "Dapper Handkerchief": "手帕",
    "Exotic Spices": "异国香料",
    "Fishing Float": "浮漂",
    "Floral Adornment": "花饰",
    "Forget-me-nots": "勿忘草",
    "Gemstone Beads": "宝石串珠",
    "Goddess Statuette": "女神小雕像",
    "Horse Manure": "马粪",
    "Hunting Dagger": "狩猎短剑",
    "Landscape Painting": "风景画",
    "Lavender": "薰衣草",
    "Legends of Chivalry": "骑士传",
    "Lily": "百合",
    "Lily of the Valley": "铃兰",
    "Monarch Studies Book": "帝王学书籍",
    "Owl Feather": "猫头鹰羽毛",
    "Pitcher Plant": "猪笼草",
    "Pretty Pebble": "漂亮的小石子",
    "Riding Boots": "骑马靴",
    "Rose": "玫瑰",
    "Smoked Meat": "熏肉",
    "Spirit Gem": "精灵宝石",
    "Stylish Hair Clip": "时尚发夹",
    "Sunflower": "向日葵",
    "Tasty Baked Treat": "美味点心",
    "Tea Leaves": "茶叶",
    "The History of Fódlan": "芙朵拉历史书",
    "Training Weight": "训练用负重",
    "Violet": "紫罗兰",
    "Watering Can": "浇水壶",
    "Whetstone": "磨刀石",
    # Meals & Dining Hall Events
    "Beast Meat Teppanyaki": "野兽肉铁板烧",
    "Bourgeois Pike": "贵族梭鱼",
    "Cabbage and Herring Stew": "卷心菜炖鲱鱼",
    "Cheesy Verona Stew": "芝士炖维罗纳",
    "Country-Style Red Turnip Plate": "乡村风味红芜菁拼盘",
    "Daphnel Stew": "达夫内尔风炖菜",
    "Derdriu-Style Fried Pheasant": "德尔德里风烤野鸡",
    "Fish Sandwich": "鲜鱼三明治",
    "Fish and Bean Soup": "鲜鱼豆汤",
    "Fried Crayfish": "炸小龙虾",
    "Fruit and Herring Tart": "水果鲱鱼塔",
    "Garreg Mach Meat Pie": "加尔古·玛库肉派",
    "Gautier Cheese Gratin": "戈迪耶芝士焗菜",
    "Grilled Herring": "香烤鲱鱼",
    "Gronder Meat Skewers": "古隆达兹烤肉串",
    "Onion Gratin Soup": "焗洋葱汤",
    "Peach Sorbet": "桃子雪葩",
    "Pheasant Roast with Berry Sauce": "莓果酱烤野鸡",
    "Pickled Rabbit Skewers": "腌兔肉串",
    "Pickled Seafood and Vegetables": "醋渍海鲜蔬菜",
    "Saghert and Cream": "萨赫特奶油甜点",
    "Sautéed Jerky": "香煎肉干",
    "Sautéed Pheasant and Eggs": "野鸡煎蛋",
    "Small Fish Skewers": "小鱼串烧",
    "Spicy Fish and Turnip Stew": "辣味鲜鱼芜菁炖锅",
    "Super-Spicy Fish Dango": "超辣鲜鱼丸子",
    "Sweet Bun Trio": "三色甜面包",
    "Sweet and Salty Whitefish Sauté": "甜咸煎白鱼",
    "Two-Fish Sauté": "双鱼香煎",
    "Vegetable Pasta Salad": "蔬菜意面沙拉",
    "Vegetable Stir-Fry": "清炒时蔬",
    "Bonding Bites": "羁绊美食祭",
    "Faculty & Knight Mixer": "教职员与骑士联谊餐会",
    "Fish Bounty Festival": "鱼类丰收祭",
    "Fisherman's Bounty": "渔夫丰收餐会",
    "Head Chef Challenge": "主厨挑战赛",
    "Hunting Festival": "狩猎祭",
    "Sweet-Tooth Week": "甜食周",
    "Tea party First conversation": "茶会第 1 轮对话话题",
    "Tea party Second conversation": "茶会第 2 轮对话话题",
    "Tea party Third conversation": "茶会第 3 轮对话话题",
    "Tea party Fourth conversation": "茶会第 4 轮（结语）对话话题",
}

TH_MONASTERY_EXTRA_ITEMS: dict[str, str] = {
    "Albinean Berry Blend": "阿尔比尼亚浆果茶",
    "Almond Blend": "杏仁茶",
    "Almyran Pine Needles": "帕迈拉松针茶",
    "Angelica": "白芷",
    "Angelica Tea": "白芷茶",
    "Bergamot": "佛手柑茶",
    "Blowfly": "青蝇鱼饵",
    "Blue Flower Seeds": "蓝花种子",
    "Boa Fruit": "博阿果",
    "Bullhead": "杜父鱼",
    "Chamomile": "洋甘菊",
    "Cinnamon Blend": "肉桂茶",
    "Crescent-Moon Tea": "新月茶",
    "Dagda Fruit Blend": "达古达水果茶",
    "Duscur Bear": "达斯卡熊肉",
    "Earthworm": "蚯蚓",
    "Eastern Fódlan Seeds": "东芙朵拉种子",
    "Four-Spice Blend": "四味香料茶",
    "Ginger Tea": "姜茶",
    "Green Flower Seeds": "绿花种子",
    "Herring Bait": "鲱鱼鱼饵",
    "Honeyed-Fruit Blend": "蜜果茶",
    "Hresvelg Blend": "弗雷斯贝尔古茶",
    "Insect Larva": "昆虫幼虫",
    "Lavender Blend": "薰衣草茶",
    "Leicester Cortania": "雷斯塔可塔尼亚",
    "Magdred Kirsch": "马格德雷德樱桃",
    "Mint Leaves": "薄荷叶",
    "Mixed Herb Seeds": "混合香草种子",
    "Morfis Plum": "莫尔菲斯李子",
    "Nordsalat": "诺尔德生菜",
    "Northern Fódlan Seeds": "北芙朵拉种子",
    "Oghma Wolverine": "奥格玛狼獾",
    "Pale-Blue Flower Seeds": "淡蓝花种子",
    "Pond Snail": "田螺",
    "Poultry": "禽肉",
    "Purple Flower Seeds": "紫花种子",
    "Queen Loach": "泥鳅女王",
    "Red Flower Seeds": "红花种子",
    "Root Vegetable Seeds": "根茎菜种子",
    "Rose Petal Blend": "玫瑰花瓣茶",
    "Seiros Tea": "赛罗司茶",
    "Southern Fruit Blend": "南方水果茶",
    "Southern Fódlan Seeds": "南芙朵拉种子",
    "Sweet-Apple Blend": "甜苹果茶",
    "Teutates Pike": "特乌塔特斯梭鱼",
    "Vegetable Seeds": "蔬菜种子",
    "Western Fódlan Seeds": "西芙朵拉种子",
    "White Flower Seeds": "白花种子",
    "Wild Game": "野味肉",
    "Yellow Flower Seeds": "黄花种子",
    "Essar Research Group": "艾萨尔研究团",
}

TH_BATTALIONS_EXACT: dict[str, str] = {
    "Immortal Corps": "不死队",
    "King of Lions Corps": "狮子王队",
    "Knights of Seiros": "赛罗司骑士团",
    "Holy Knights of Seiros": "赛罗司神圣骑士团",
    "Imperial Guard": "帝国亲卫队",
    "Royal Guard": "王国近卫队",
    "Jeralt's Mercenaries": "杰拉尔特佣兵团",
    "Enhanced Heavy Co.": "强化重装兵团",
    "West Church Corps": "西方教会兵团",
    "Opera Co. Volunteers": "歌剧团义勇兵",
    "Secret Transport Force": "秘密运输部队",
    "School of Sorcery Soldiers": "魔道学院兵",
    "Merchant Military": "商人自卫团",
    "Holst's Chosen": "霍尔斯特精锐队",
    "Einherjar": "英灵战团",
    "Bandits": "山贼团",
    "Pirates": "海盗团",
    "Rogues": "流寇团",
    "Thieves": "盗贼团",
    "Rampaging Villagers": "失控村民",
    "Indech Sword Fighters": "因迪哈剑士队",
    "Macuil Evil Repelling Co.": "马库伊尔破邪队",
    "Bergliez War Group": "贝尔谷里斯战团",
    "Hevring Prayer Troops": "海弗林格祈祷兵",
    "Varley Archers": "瓦立弓兵队",
    "Vestra Sorcery Engineers": "贝斯特拉魔道工兵",
    "Ordelia Sorcery Co.": "科迪利亚魔道兵",
    "Edmund Troops": "艾德蒙边境兵",
    "Daphnel Duelists": "达夫内尔决斗团",
    "Fraldarius Soldiers": "伏拉鲁达力乌斯兵",
    "Galatea Pegasus Co.": "贾拉提雅天马队",
    "Gautier Knights": "戈迪耶骑士团",
    "Gloucester Knights": "古罗斯塔尔骑士团",
    "Goneril Valkyries": "哥纳利尔战乙女",
    "Dominic Cavalry": "多米尼克骑兵队",
    "Duscur Heavy Soldiers": "达斯卡重装兵",
    "Duscur Cavalry": "达斯卡骑兵队",
    "Duscur Infantry": "达斯卡步兵队",
    "Aegir Astral Knights": "艾吉尔星辰骑士团",
    "Arundel Magic Corps": "阿伦戴尔魔道兵团",
    "Flame Emperor Co.": "炎帝亲兵队",
    "Kronya Subordinates": "克罗妮艾部下",
    "Solon Subordinates": "索龙部下",
    "Timotheos Magi Corps": "提莫特魔道团",
    "Nuvelle Attendants Co.": "努维尔随从队",
    "Nuvelle Chamberlain Co.": "努维尔家臣团",
    "Nuvelle Fliers Corps": "努维尔航空魔导队",
    "Nuvelle Stewards Co.": "努维尔侍从队",
    "Mockingbird's Thieves": "反舌鸟盗贼团",
    "Pallardó Bodyguards": "帕拉尔多护卫队",
    "Miklan Private Militia": "迈克朗私兵团",
    "Iron King's Thieves": "铁王盗贼团",
    "Remire Militia": "雷米尔自卫团",
    "Sauin Militia": "萨乌因自卫团",
    "Gaspard Archers": "加斯帕德弓兵",
    "Gaspard Knights": "加斯帕德骑士",
    "Gaspard Militia": "加斯帕德民兵",
    "Victor Private Military": "维克托私兵团",
    "House Rowe Archers": "罗维家弓兵",
    "Essar Research Group": "艾萨尔研究团",
    "Cethleann Monks": "赛斯琳修道士",
    "Cichol Wyvern Co.": "奇霍尔飞龙队",
    "Morfis Magic Corps": "莫尔菲斯魔道团",
    "Brigid Hunters": "布里基德猎手",
    "Brigid Mercenaries": "布里基德佣兵",
    "Leicester Dicers Corps": "雷斯塔义警队",
    "Leicester Mercenaries": "雷斯塔佣兵团",
    "Enhanced Heavy Co.": "强化重装兵团",
    "West Church Corps": "西方教会兵团",
    "Opera Co. Volunteers": "歌剧团义勇兵",
}

TH_BATTALION_PREFIXES: list[tuple[str, str]] = [
    ("Church of Seiros", "赛罗司圣教会"),
    ("West Church", "西方教会"),
    ("Black Eagle", "黑鹫"),
    ("Blue Lion", "青狮子"),
    ("Golden Deer", "金鹿"),
    ("Empire", "帝国"),
    ("Kingdom", "王国"),
    ("Alliance", "同盟"),
    ("Seiros", "赛罗司"),
    ("Church", "教会"),
    ("Almyra", "帕迈拉"),
    ("Ancient", "古代"),
    ("Enhanced", "强化"),
    ("Mysterious", "神秘"),
    ("Phantasmal", "幻影"),
    ("Reaper", "死神"),
    ("Dark", "暗"),
    ("Duscur", "达斯卡"),
    ("Rowe", "罗维"),
    ("Dahlman", "达尔曼"),
    ("Cliff Bandit", "悬崖盗贼"),
    ("Bandit", "山贼"),
    ("Pirate", "海盗"),
    ("Thief", "盗贼"),
]

TH_BATTALION_SUFFIXES: list[tuple[str, str]] = [
    ("Holy Magic Users", "白魔道兵团"),
    ("Evil Repelling Co.", "破邪队"),
    ("Brave Lance Co.", "勇者枪兵团"),
    ("Veteran Duelists", "老练决斗士"),
    ("Elite Wyvern Co.", "精锐飞龙队"),
    ("Master Archers", "精锐弓兵团"),
    ("Pegasus Corps", "天马队"),
    ("Pegasus Co.", "天马队"),
    ("Wyvern Co.", "飞龙队"),
    ("Magic Corps", "魔道兵团"),
    ("Magic Users", "魔道兵"),
    ("Magic Co.", "魔道队"),
    ("Heavy Knights", "重装骑士团"),
    ("Heavy Soldiers", "重装兵团"),
    ("Heavy Axes", "重斧兵团"),
    ("Holy Knights", "神圣骑士团"),
    ("Sacred Monks", "圣修道士"),
    ("Holy Monks", "神圣修道士"),
    ("Armored Group", "重甲战团"),
    ("Armored Co.", "重甲兵团"),
    ("Pavise Co.", "大盾重兵团"),
    ("Lance Co.", "枪兵团"),
    ("Sorcerers", "术士团"),
    ("Physicians", "医护兵团"),
    ("Mercenaries", "佣兵团"),
    ("Marksmen", "狙击兵"),
    ("Duelists", "决斗士"),
    ("Archers", "弓兵团"),
    ("Snipers", "狙击兵团"),
    ("Brawlers", "格斗兵团"),
    ("Cavalry", "骑兵团"),
    ("Infantry", "步兵团"),
    ("Knights", "骑士团"),
    ("Soldiers", "士兵团"),
    ("Warriors", "勇士团"),
    ("Priests", "司祭团"),
    ("Sages", "贤者团"),
    ("Raiders", "奇袭兵团"),
    ("Dancers", "舞者团"),
    ("Youths", "少年兵团"),
    ("Guard", "卫队"),
    ("PMC", "私兵团"),
]

TH_CHAPTERS: dict[str, str] = {
    "Prologue": "序章",
    "Chapter 1: Three Houses": "第1章 三个学级",
    "Chapter 1: The Fourth House": "煤暗之章 第1章 第四个学级",
    "Chapter 2: Familiar Scenery": "第2章 追怀的风景",
    "Chapter 2: What Lies Beneath": "煤暗之章 第2章 地下之物",
    "Chapter 3: Mutiny in the Mist": "第3章 雾中的叛乱",
    "Chapter 3: The Rite of Rising": "煤暗之章 第3章 降神之仪",
    "Chapter 4: The Goddess's Rite of Rebirth": "第4章 女神再诞之仪",
    "Chapter 4: Danger in the Dark": "煤暗之章 第4章 危机迫近",
    "Chapter 5: Tower of Black Winds": "第5章 黑风之塔",
    "Chapter 5: Betrayal": "煤暗之章 第5章 背叛",
    "Chapter 6: Rumors of a Reaper": "第6章 死神之谣",
    "Chapter 6: Return to Me": "煤暗之章 第6章 回到我身边",
    "Chapter 7: Field of the Eagle and Lion": "第7章 鹫与狮之战",
    "Chapter 7: Wolf Pack": "煤暗之章 第7章 苍狼之群",
    "Chapter 8: The Flame in the Darkness": "第8章 炎与暗之蠢动",
    "Chapter 9: The Cause of Sorrow": "第9章 泪之原因",
    "Chapter 10: Where the Goddess Dwells": "第10章 女神的去向",
    "Chapter 11: Throne of Knowledge": "第11章 圣墓",
    "Chapter 12: Outset of a Power Struggle": "第12章 战乱的开端（红花之章）",
    "Chapter 12: To War": "第12章 迈向战争",
    "Chapter 13: Beyond Escape": "第13章 超越逢魔之刻（红花）",
    "Chapter 13: Reunion at Dawn": "第13章 黎明的追怀战",
    "Chapter 14: A King Without a Kingdom": "第14章 无王之国（苍月）",
    "Chapter 14: The Alliance Leader's Ambitions": "第14章 盟主的野望（红花）",
    "Chapter 14: The Delusional Prince": "第14章 妄执之王子（苍月）",
    "Chapter 14: The Master Tactician": "第14章 卓绝军师（翠风/银雪）",
    "Chapter 15: Tempest of Swords and Shields": "第15章 剑与盾之螺旋",
    "Chapter 15: Valley of Torment": "第15章 炼狱之谷",
    "Chapter 16: Lady of Deceit": "第16章 虚伪的少女（红花）",
    "Chapter 16: The Rose-Colored River": "第16章 蔷薇色的大河",
    "Chapter 17: Blood of the Eagle and Lion": "第17章 血战（格隆达兹会战）",
    "Chapter 17: Field of Revenge": "第17章 复仇之野",
    "Chapter 17: The Impregnable Fortress": "第17章 铁壁之城",
    "Chapter 18: The Chaos of War": "第18章 战火之中",
    "Chapter 18: The Golden Scheme": "第18章 黄金的策略",
    "Chapter 18: The King's Triumphant Return": "第18章 王都夺还战",
    "Chapter 18: To the End of a Dream": "第18章 梦之终结（红花 终章）",
    "Chapter 19: Conclusion of the Crossing Roads": "第19章 交错之路的终结",
    "Chapter 19: The Chaos of War": "第19章 战火之中",
    "Chapter 19: The Golden Deer's Plea": "第19章 金鹿之请",
    "Chapter 20: Conclusion of the Crossing Roads": "第20章 交错之路的终结",
    "Chapter 20: The City Without Light": "第20章 无光之都香巴拉",
    "Chapter 20: The Impregnable Fortress": "第20章 铁壁之城",
    "Chapter 21: Following a Dream": "第21章 追寻之梦（银雪 终章）",
    "Chapter 21: Our Chosen Paths": "第21章 各自的道路",
    "Chapter 21: The City Without Light": "第21章 无光之都香巴拉",
    "Chapter 22: Fódlan's New Dawn": "第22章 芙朵拉的新黎明（翠风 终章）",
    "Chapter 22: Oath of the Dagger": "第22章 短剑之誓（苍月 终章）",
    # Paralogues
    "A Cursed Relic": "外传：被诅咒的遗产（尤里斯 / 康丝坦洁）",
    "An Inevitable Encounter": "外传：宿命的再会",
    "An Ocean View": "外传：海景的回忆（赛斯琳）",
    "Black Market Scheme": "外传：黑市的阴谋（巴鲁塔札尔 / 哈琵）",
    "Darkness Beneath the Earth": "外传：地下暗流（休伯特）",
    "Death Toll": "外传：死神的钟声（卡斯帕尔 / 梅尔赛德司）",
    "Dividing the World": "外传：隔绝的世界（希尔妲 / 锥里尔）",
    "Eternal Guardian": "外传：永远的守护者（蕾雅）",
    "Falling Short of Heaven": "外传：遥不可及的天空（阿罗伊斯 / 萨米亚）",
    "Foreign Land and Sky": "外传：异国的天空（佩托拉 / 贝尔娜提塔）",
    "Forgotten Hero": "外传：被遗忘的英雄（玛莉安奴）",
    "Insurmountable": "外传：无法跨越的障壁（艾黛尔贾特）",
    "Land of the Golden Deer": "外传：金鹿之地（洛廉兹）",
    "Legend of the Lake": "外传：湖之传说（林哈尔特 / 雷欧妮）",
    "Oil and Water": "外传：水火不容（汉尼曼 / 玛努艾拉）",
    "Retribution": "外传：报应（费尔迪南特 / 莉丝缇亚）",
    "Rumored Nuptials": "外传：传言中的婚约（多洛缇雅 / 英谷莉特）",
    "Sword and Shield of Seiros": "外传：赛罗司之剑与盾（西提司 / 芙莲）",
    "Tales of the Red Canyon": "外传：赤红山谷的传说（苏谛斯）",
    "The Face Beneath": "外传：假面之下（梅尔赛德司 / 伊艾里扎）",
    "The Forgotten": "外传：被遗忘之事（杜笃）",
    "The Secret Merchant": "外传：秘密商人（安娜）",
    "The Silver Maiden": "外传：白银少女（帝弥托利）",
    "The Sleeping Sand Legend": "外传：沉睡于沙海的传说（库罗德）",
    "True Chivalry": "外传：真正的骑士（菲力克斯）",
    "War for the Weak": "外传：为弱者而战（迪杜 / 亚修 / 吉尔伯特）",
    "Weathervanes of Fódlan": "外传：芙朵拉的风向标（雅妮特 / 吉尔伯特）",
}

ENGAGE_CHAPTERS: dict[str, tuple[str, str]] = {
    "The Emblems": ("序章 纹章士", "主线章节"),
    "Awake at Last": ("第1章 苏醒", "主线章节"),
    "Queen Lumera": ("第2章 女王琉弥艾尔", "主线章节"),
    "Hostilities": ("第3章 袭城", "主线章节"),
    "A Land in Bloom": ("第4章 花开之地", "主线章节"),
    "Retaking the Castle": ("第5章 夺回王城", "主线章节"),
    "The Stolen Ring": ("第6章 被夺走的戒指", "主线章节"),
    "Dark Emblem": ("第7章 暗黑纹章士", "主线章节"),
    "The Kingdom of Might": ("第8章 勇武之国", "主线章节"),
    "A Clash of Forces": ("第9章 激战", "主线章节"),
    "The Fell & the Divine": ("第10章 邪龙与神龙", "主线章节"),
    "Retreat": ("第11章 撤退", "主线章节"),
    "The Sentinels": ("第12章 守护团", "主线章节"),
    "Heroes of the Oasis": ("第13章 绿洲的英雄", "主线章节"),
    "The Battle for Solm": ("第14章 索尔姆攻防战", "主线章节"),
    "Dancer in the Ruins": ("第15章 遗迹中的舞者", "主线章节"),
    "Seashore Travels": ("第16章 海岸行", "主线章节"),
    "Serenity in Ruin": ("第17章 破灭的宁静", "主线章节"),
    "The Cold Voyage": ("第18章 冰冷航路", "主线章节"),
    "The Dead Town": ("第19章 死亡之城", "主线章节"),
    "The Kingless Castle": ("第20章 无王之城", "主线章节"),
    "The Return": ("第21章 归来", "主线章节"),
    "The Fell & the Divine (2)": ("第22章 神龙与邪龙", "主线章节"),
    "The Four Hounds": ("第23章 四狗", "主线章节"),
    "Recollections": ("第24章 追忆", "主线章节"),
    "The Final Guardian": ("第25章 最后的守护者", "主线章节"),
    "The Last Engage": ("第26章 最后的结合", "主线章节"),
    "The Fell Dragon Sombron": ("终章 邪龙尚卜尔", "主线章节"),
    # Paralogues
    "Budding Talent": ("外传 萌芽的才能（贾恩）", "外传"),
    "Mysterious Merchant": ("外传 神秘商人（安娜）", "外传"),
    "The Instructor": ("外传 引导者（贝雷特）", "纹章士外传"),
    "The Radiant Hero": ("外传 苍炎勇者（艾克）", "纹章士外传"),
    "The Sage Lord": ("外传 圣者（赛莉卡）", "纹章士外传"),
    "The Caring Princess": ("外传 慈悲公主（艾莉可）", "纹章士外传"),
    "The Lady of the Plains": ("外传 草原的少女（琳）", "纹章士外传"),
    "The Holy Knight": ("外传 圣骑士（西格尔特）", "纹章士外传"),
    "The Azure Twin": ("外传 苍蓝双子（琉尔 / 柯岚等·高难外传）", "纹章士外传"),
    "The Crux of Fate": ("外传 命运的交叉点（神威）", "纹章士外传"),
    "The Dawn Maiden": ("外传 晓之巫女（米卡雅）", "纹章士外传"),
    "The Connector": ("外传 连系者（露琪娜）", "纹章士外传"),
    "The Exalt": ("外传 圣王（马尔斯）", "纹章士外传"),
    "The Hero-King": ("外传 英雄王（马尔斯）", "纹章士外传"),
    "The Young Lion": ("外传 年轻的狮子（罗伊）", "纹章士外传"),
    "The Lonely Heir": ("外传 孤独的继承者（里弗）", "纹章士外传"),
    # Divine Paralogues
    "The Ancestor": ("神龙外传 祖先（琪姬）", "神龙之章外传"),
    "The Brash General": ("神龙外传 刚勇将军（海克托尔）", "神龙之章外传"),
    "The Doting Sister": ("神龙外传 温柔的姐姐（卡美拉）", "神龙之章外传"),
    "The Radiant Strategist": ("神龙外传 苍焰军师（塞涅里奥）", "神龙之章外传"),
    "The Shepherd Exalt": ("神龙外传 自警团圣王（库洛姆 / 鲁弗莱）", "神龙之章外传"),
    # Fell Xenologue
    "Seeds of Unrest": ("邪龙之章 第1章 动乱的萌芽", "邪龙之章 DLC"),
    "Clash of Queens": ("邪龙之章 第2章 双姬冲突", "邪龙之章 DLC"),
    "Seven Bracelets": ("邪龙之章 第3章 七枚手镯", "邪龙之章 DLC"),
    "Path to War": ("邪龙之章 第4章 通往战争之路", "邪龙之章 DLC"),
    "The Fell Heir": ("邪龙之章 第5章 邪龙的继承者", "邪龙之章 DLC"),
    "Dragons from Afar": ("邪龙之章 第6章 远方之龙", "邪龙之章 DLC"),
}

STAT_KEY_ZH: dict[str, str] = {
    "hp": "HP",
    "str": "力量",
    "strength": "力量",
    "mag": "魔力",
    "magic": "魔力",
    "dex": "技巧",
    "skill": "技巧",
    "spd": "速度",
    "speed": "速度",
    "lck": "幸运",
    "luck": "幸运",
    "def": "防守",
    "defense": "防守",
    "res": "魔防",
    "resistance": "魔防",
    "cha": "魅力",
    "charm": "魅力",
    "bld": "体格",
    "build": "体格",
    "mov": "移动力",
    "move": "移动力",
    "lv": "等级",
}

CLASS_QUALIFIER_MAP: dict[str, str] = {
    "Prologue": "序章",
    "Chapter 5": "第5章",
    "playable": "我方",
    "non-playable": "敌方",
    "enemy": "敌方",
    "duplicate": "复用版",
    "CF": "红花之章",
    "SS phase 1": "银雪之章 第1阶段",
    "SS phase 2": "银雪之章 第2阶段",
    "Judith": "茱迪特",
}


def translate_character_name(en_or_mixed: str) -> str:
    s = en_or_mixed.strip()
    if s in TH_CHAR_SHORT:
        return TH_CHAR_SHORT[s]
    if s in TH_CHARACTERS:
        return TH_CHARACTERS[s]
    return s


def translate_class_title(en: str) -> str:
    raw = en.strip()
    is_growth = raw.endswith(" growth rates")
    base = raw[: -len(" growth rates")].strip() if is_growth else raw

    suffix_note = ""
    m = re.search(r"\(([^)]+)\)\s*$", base)
    if m:
        q = m.group(1).strip()
        q_zh = CLASS_QUALIFIER_MAP.get(q, q)
        suffix_note = f"（{q_zh}）"
        base = base[: m.start()].strip()

    zh = TH_CLASSES.get(base, base) + suffix_note
    return f"{zh} · 职业成长" if is_growth else zh


def translate_skill_title(en: str) -> str:
    s = en.replace("&nbsp;", " ").strip()
    s = re.sub(r"\s*\((ability|staff)\)\s*$", "", s)
    if s in TH_SKILLS_AND_ARTS:
        return TH_SKILLS_AND_ARTS[s]
    # Patterns: "<Weapon> Prowess Lv <N>"
    m = re.match(r"^(Sword|Lance|Axe|Bow|Brawling|Reason|Faith|Authority)\s+Prowess\s+Lv\s*(\d+)$", s)
    if m:
        w = TH_WEAPON_CATEGORIES.get(m.group(1), m.group(1))
        return f"{w} Lv.{m.group(2)}"
    m = re.match(r"^([A-Za-z ]+)\s+Lv\s*(\d+)$", s)
    if m and m.group(1).strip() in TH_WEAPON_CATEGORIES:
        return f"{TH_WEAPON_CATEGORIES[m.group(1).strip()]} Lv.{m.group(2)}"
    m = re.match(r"^(Sword|Lance|Axe|Bow|Brawl)\s+Crit\s*\+(\d+)$", s)
    if m:
        w = {"Sword": "剑", "Lance": "枪", "Axe": "斧", "Bow": "弓", "Brawl": "格斗"}[m.group(1)]
        return f"{w}必杀＋{m.group(2)}"
    return s


def translate_equipment_title(en: str) -> str:
    s = en.strip()
    plus = s.endswith("+")
    base = s[:-1] if plus else s
    if base in TH_EQUIPMENT_EXACT:
        return TH_EQUIPMENT_EXACT[base] + ("+" if plus else "")
    if base in TH_SKILLS_AND_ARTS:
        return TH_SKILLS_AND_ARTS[base] + ("+" if plus else "")
    if base in TH_SOCIAL_EXACT:
        return TH_SOCIAL_EXACT[base]
    if base in TH_MONASTERY_EXTRA_ITEMS:
        return TH_MONASTERY_EXTRA_ITEMS[base]
    if base.endswith(" Dragon Sign"):
        prefix = base[: -len(" Dragon Sign")].strip()
        if prefix in TH_DRAGON_SIGNS:
            return TH_DRAGON_SIGNS[prefix]
    for mat, mat_zh in TH_MATERIAL_PREFIXES.items():
        if base.startswith(mat + " "):
            noun = base[len(mat) + 1 :].strip()
            if noun in TH_WEAPON_NOUNS:
                return f"{mat_zh}{TH_WEAPON_NOUNS[noun]}" + ("+" if plus else "")
    return s


def translate_battalion_title(en: str) -> str:
    s = en.strip()
    if s in TH_BATTALIONS_EXACT:
        return TH_BATTALIONS_EXACT[s]
    for pref, pref_zh in TH_BATTALION_PREFIXES:
        if s.startswith(pref + " "):
            rest = s[len(pref) + 1 :].strip()
            for suf, suf_zh in TH_BATTALION_SUFFIXES:
                if rest == suf:
                    return f"{pref_zh}{suf_zh}"
    for suf, suf_zh in TH_BATTALION_SUFFIXES:
        if s.endswith(" " + suf):
            prefix = s[: -len(suf) - 1].strip()
            prefix_zh = TH_CHAR_SHORT.get(prefix, TH_FACTIONS_AND_ROUTES.get(prefix, prefix))
            return f"{prefix_zh}{suf_zh}"
    return s


def translate_chapter_title(en: str) -> str:
    s = en.strip()
    is_battle = s.endswith(" battle summary")
    if is_battle:
        s = s[: -len(" battle summary")].strip()
    star = "（DLC）" if s.endswith(" *") else ""
    clean = s[:-2].strip() if s.endswith(" *") else s
    zh = TH_CHAPTERS.get(clean)
    if not zh and clean in ENGAGE_CHAPTERS:
        zh = ENGAGE_CHAPTERS[clean][0]
    if not zh:
        zh = clean
    zh = f"{zh}{star}"
    return f"{zh} · 战场摘要" if is_battle else zh


def translate_monastery_title(en: str) -> str:
    s = en.strip()
    if s == "10,000 renown":
        return "10,000 名声商店兑换奖励"
    if s.startswith("Amiibo song: "):
        hero = s[len("Amiibo song: ") :].strip()
        return f"Amiibo 乐曲：{TH_CHARACTERS.get(hero, hero)}"
    if s.startswith("Activity points at professor level "):
        lv = s[len("Activity points at professor level ") :].strip()
        return f"指导等级 {lv} 行动点数"
    if s.startswith("Saint Statues reward tier "):
        tier = s[len("Saint Statues reward tier ") :].strip()
        return f"圣人像声望奖励（{tier} Pt）"
    for shop_en, shop_zh in (
        (" — Traveling Merchants and Anna's Shop/Secret Shop", "（行商 / 安娜秘密商店）"),
        (" — Armory", "（武器店）"),
        (" — Item Shop", "（道具店）"),
    ):
        if s.endswith(shop_en):
            item_en = s[: -len(shop_en)].strip()
            return f"{translate_equipment_title(item_en)} {shop_zh}"
    return translate_equipment_title(s)


def translate_mechanics_title(en: str) -> str:
    m = re.match(r"^Share a Meal: (Liked|Neutral|Disliked) motivation$", en)
    if m:
        mood = {"Liked": "喜欢", "Neutral": "普通", "Disliked": "不喜欢"}[m.group(1)]
        return f"共进晚餐：角色{mood}料理的干劲加成"
    m = re.match(r"^Tea party result at (\d+) EP$", en)
    if m:
        return f"茶会评价结果（{m.group(1)} 点额外行动力）"
    fixed = {
        "Tea party extra EP: Selecting a preferred topic (Round 1)": "茶会加分：第 1 轮选对喜好话题",
        "Tea party extra EP: Selecting a preferred topic (Round 2)": "茶会加分：第 2 轮选对喜好话题",
        "Tea party extra EP: Selecting a preferred topic (Round 3)": "茶会加分：第 3 轮选对喜好话题",
        "Tea party extra EP: Selecting the correct response (Finale)": "茶会加分：结语选对回应",
        "Tea party extra EP: Serving a preferred tea": "茶会加分：端出喜好茶叶",
    }
    return fixed.get(en, en)


def translate_recruitment_title(en: str) -> str:
    if " — " in en and en.endswith(" join condition"):
        char_part, rest = en.split(" — ", 1)
        faction_part = rest[: -len(" join condition")].strip()
        char_zh = TH_CHARACTERS.get(char_part.strip(), char_part.strip())
        faction_zh = TH_FACTIONS_AND_ROUTES.get(faction_part, faction_part)
        return f"{char_zh} · {faction_zh}招募条件"
    return en


def translate_support_title(en: str) -> str:
    if " + " in en:
        a, b = en.split(" + ", 1)
        return f"{translate_character_name(a)} ＋ {translate_character_name(b)}"
    return en


def translate_th_subtitle(category_id: str, raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    if s.startswith("Source roster class:"):
        cls = s[len("Source roster class:") :].strip()
        return f"初始职业：{translate_class_title(cls)}"
    if s.startswith("Bearers:"):
        raw_bearers = s[len("Bearers:") :].strip()
        raw_bearers = re.sub(r"\[\s*\d+\s*\]", "", raw_bearers)
        names = [translate_character_name(p.strip()) for p in raw_bearers.split(",") if p.strip()]
        return "持有者：" + "、".join(names)

    exact = {
        "Support pair; rank and conditions not captured": "支援组合（具体对话与解锁条件待补充）",
        "Class growth modifiers": "职业成长率补正",
        "Weapon": "武器",
        "Item": "道具",
        "Equipment": "装备 / 饰品",
        "Class ability": "职业特技",
        "Basic combat arts": "基础战技",
        "Non-offensive combat arts": "辅助战技",
        "Effective combat arts": "克制特效战技",
        "Multi-hit combat arts": "多段连击战技",
        "Magic combat arts": "魔导战技",
        "Regalia-specific combat arts": "神圣武器 / 遗产专属战技",
        "Tea party": "茶会话题",
        "White magic in the Fire Emblem series": "白魔法",
        "Black magic in the Fire Emblem series": "黑魔法",
        "Dark magic in the Fire Emblem series": "暗魔法",
        "Gift preference": "礼物偏好",
        "Dining Hall event": "食堂特别活动",
        "Meat": "肉类料理",
        "Fish": "鱼类料理",
        "Vegetables": "蔬菜料理",
        "Sweets": "甜点",
        "Spicy": "辛辣料理",
        "Bitter": "苦味料理",
        "Armory": "武器店商品",
        "Item Shop": "道具店商品",
        "Saint Statues": "大圣堂圣人像",
        "Amiibo Gazebo": "Amiibo 凉亭",
        "Activity points": "学院指导行动点数",
        "Byleth's Room": "主角房间（特典）",
        "Share a Meal": "食堂共进晚餐",
        "Tea party scoring": "茶会评分规则",
        "Traveling Merchants and Anna's Shop/Secret Shop": "行商与安娜秘密商店",
        "Recon Training and Recon Missions": "侦察训练与联机任务",
    }
    if s in exact:
        return exact[s]
    if s in TH_FACTIONS_AND_ROUTES:
        return TH_FACTIONS_AND_ROUTES[s]
    if category_id == "classes" and " · " in s:
        tier_raw, move_raw = s.split(" · ", 1)
        tier_map = {
            "--": None,
            "Unique": "专属兵种",
            "Beginner": "初级兵种",
            "Intermediate": "中级兵种",
            "Advanced": "高级兵种",
            "Master": "最高级兵种",
            "Special": "特殊兵种",
        }
        move_map = {"infantry": "步兵", "armored": "重甲", "cavalry": "骑兵", "flying": "飞行", "monster": "魔物", "dragon": "龙族"}
        tier_zh = tier_map.get(tier_raw.strip(), tier_raw.strip())
        moves = " / ".join(move_map.get(tok.strip().lower(), tok.strip()) for tok in move_raw.split(","))
        return " · ".join(p for p in (tier_zh, moves) if p)
    if category_id == "recruitment":
        return translate_join_condition(s)
    return s


def translate_join_condition(raw: str) -> str:
    s = raw.strip()
    s = s.replace(" *", "（随路线条件变化）")
    s = s.replace("after completing Cindered Shadows", "完成「煤暗之章」")
    s = s.replace("(Wave 3 Paid DLC)", "（第 3 波付费 DLC）")
    s = s.replace("(Wave 4 Paid DLC)", "（第 4 波付费 DLC）")
    s = s.replace("onwards", "起")
    s = s.replace("onward", "起")
    s = s.replace("Prologue", "序章")
    s = re.sub(r"Chapter\s+(\d+)", r"第 \1 章", s)
    return re.sub(r"\s{2,}", " ", s).strip()


def translate_equipment_effect(raw: str) -> str:
    s = raw.strip()
    if not s:
        return s
    # Replace crest names inside sentences first
    for crest_en, crest_zh in TH_CRESTS.items():
        s = s.replace(f"Minor Crest of {crest_en}", f"{crest_zh}（小）")
        s = s.replace(f"Major Crest of {crest_en}", f"{crest_zh}（大）")
        s = s.replace(f"Crest of {crest_en}", crest_zh)
    for ds_en, ds_zh in TH_DRAGON_SIGNS.items():
        s = s.replace(f"{ds_en} Dragon Sign", ds_zh)
        s = s.replace(f"{ds_en} Dragon Sing", ds_zh)  # source typo in Cethleann staff

    s = s.replace("Cannot be sold.", "不可出售。")
    s = s.replace("Cannot be sold", "不可出售。")
    s = s.replace("Downloadable content .", "【DLC 道具】")
    s = s.replace("Cannot perform follow-up attacks .", "无法发动追击。")
    s = s.replace("Cannot perform follow-up attacks.", "无法发动追击。")
    s = s.replace("Target cannot counterattack.", "敌方无法反击。")
    s = s.replace("Targets all enemies. Cannot hit flying units.", "以全图所有敌人为目标（无法命中飞行单位）。")
    s = s.replace("Deals 50% of the damage to the target to adjacent enemies.", "对主目标周围相邻的敌人额外造成 50% 溅射伤害。")
    s = s.replace("Reduces the target to 1 HP ; defeats foes at 1 HP.", "将目标 HP 降至 1（若目标已为 1 HP 则直接击倒）。")
    s = s.replace("Ignores the foe's resistance .", "无视敌方魔防计算伤害。")
    s = s.replace("Ignores the foe's resistance.", "无视敌方魔防计算伤害。")
    s = s.replace("Inflicts −5 movement on hit after combat.", "命中后战斗结束时，目标移动力 −5（持续 1 回合）。")
    s = s.replace("Inflicts −5 speed on hit after combat.", "命中后战斗结束时，目标速度 −5（持续 1 回合）。")
    s = s.replace("Inflicts −5 defense on hit after combat.", "命中后战斗结束时，目标防守 −5（持续 1 回合）。")
    s = s.replace("Restores (damage dealt / 2) HP to the user.", "吸收伤害：回复自身相当于造成伤害一半的 HP。")
    s = re.sub(
        r"Restores \[(\d+) \+ \(\s*magic\s*/\s*(\d+)\)\] HP to all allies\.",
        r"回复射程内全体我方（\1 + 魔力/\2）点 HP。",
        s,
        flags=re.IGNORECASE,
    )
    s = re.sub(
        r"Restores \[(\d+) \+ \(\s*magic\s*/\s*(\d+)\)\] HP to one ally\.",
        r"回复 1 名我方单位（\1 + 魔力/\2）点 HP。",
        s,
        flags=re.IGNORECASE,
    )
    s = s.replace("Teleports an ally to be next to the user.", "将射程内的 1 名我方单位传送至施法者相邻格。")
    s = re.sub(
        r"Teleports an ally to a traversable tile; destination range:\s*1\s*[–-]\s*\(\s*magic\s*/\s*(\d+)\)\.",
        r"将相邻的 1 名我方单位传送至射程内可通行格（传送距离：1～魔力/\1 格）。",
        s,
        flags=re.IGNORECASE,
    )
    s = s.replace("Removes all status effects from all allies in range.", "解除范围内全体我方的异常状态与动摇状态。")
    s = s.replace("Inflicts silence on the target.", "使目标陷入沉默状态，1 回合内无法使用魔法。")
    s = s.replace("Unused. Increases target's vision .", "扩大目标在索敌地图中的视野（游戏中未实装）。")
    s = s.replace("Grants +7 resistance to the target; buff reduces by 1 point per turn.", "使目标魔防 ＋7（每回合递减 1 点）。")
    s = s.replace("When the user initiates combat, strikes twice consecutively.", "主动攻击时连续攻击两次（二连击）。")
    s = s.replace("Inflicts poison on hit.", "命中后使目标陷入中毒状态。")
    s = s.replace("Cures poison .", "解除中毒状态。")
    s = s.replace("Works as magic damage .", "按魔法伤害计算。")
    s = s.replace("Deals bonus damage to cavalry and armored units .", "对骑兵与重甲单位特效。")
    s = s.replace("Deals bonus damage to flying and monster units .", "对飞行与魔物单位特效。")
    s = s.replace("Deals bonus damage to armored units .", "对重甲单位特效。")
    s = s.replace("Deals bonus damage to cavalry units .", "对骑兵单位特效。")
    s = s.replace("Deals bonus damage to flying units .", "对飞行单位特效。")
    s = s.replace("Deals bonus damage to dragon units .", "对龙族单位特效。")
    s = s.replace("Deals bonus damage to monster units .", "对魔物单位特效。")
    s = re.sub(r"Grants \+(\d+) maximum HP when used\.", r"使用后最大 HP 永久＋\1。", s)
    s = re.sub(r"Grants \+(\d+) movement when used\.", r"使用后移动力永久＋\1。", s)
    for en_stat, zh_stat in (
        ("charm", "魅力"), ("defense", "防守"), ("dexterity", "技巧"), ("luck", "幸运"),
        ("magic", "魔力"), ("resistance", "魔防"), ("speed", "速度"), ("strength", "力量"),
    ):
        s = re.sub(rf"Grants \+(\d+) {en_stat} when used\.", rf"使用后{zh_stat}永久＋\1。", s)
        s = re.sub(rf"Grants \+(\d+) {en_stat} \.", rf"装备时{zh_stat}＋\1。", s)
    s = re.sub(r"Grants \+(\d+) movement \.", r"装备时移动力＋\1。", s)
    s = re.sub(r"Grants \+(\d+) range with offensive magic\.", r"攻击魔法射程＋\1。", s)
    s = re.sub(r"Grants \+(\d+) HP restored with white magic \.", r"白魔法回复量＋\1。", s)
    s = re.sub(r"Grants \+(\d+) avoidance \.", r"回避＋\1。", s)
    s = re.sub(r"Grants \+(\d+) hit rate \.", r"命中＋\1。", s)
    s = re.sub(r"Grants \+(\d+) critical rate \.", r"必杀＋\1。", s)
    s = re.sub(r"Grants \+(\d+) magical attack \.", r"魔法攻击＋\1。", s)
    s = s.replace("Restores 10% of the user's maximum HP at the start of their phase.", "回合开始时回复自身最大 HP 的 10%。")
    s = re.sub(
        r"Restores 11\.85% \(no ([^)]+)\) / \?\? \(([^)]+)\) / 20% \(([^)]+)\) / 33% \(([^)]+)\) of the user's maximum HP at the start of their phase\.",
        r"回合开始时回复 HP：无\1 11.85% / \2 未知 / \3 20% / \4 33%。",
        s,
    )
    s = re.sub(r"Grants a ([^\s.]+(?:[^\n.]*)) when in a unit's inventory\.", r"携带时视为持有「\1」。", s)
    s = s.replace("Deals 10 damage to the user after combat.", "战斗后自身受到 10 点伤害。")
    s = s.replace("Inflicts 10 damage after combat to units without a crest .", "无对应纹章单位战斗后受到 10 点伤害。")
    s = s.replace("Grants Pavise and Aegis to units with the ", "持有「")
    if "持有「" in s and " . " in s and "大盾" not in s:
        s = s.replace(" . ", "」时赋予大盾与圣盾效果。", 1)
    s = s.replace("Grants Canto .", "赋予再移动效果。")
    s = s.replace("Grants Miracle .", "赋予祈祷效果。")
    s = s.replace("Grants Counterattack .", "赋予全距离反击效果。")
    s = s.replace("Negates all bonus damage targeting the user.", "无效化所有针对自身的特攻伤害。")
    s = s.replace("Allows a unit to attempt an exam for a beginner class.", "可用于参加初级兵种资格考试。")
    s = s.replace("Allows a unit to attempt an exam for an intermediate class (except Dark Mage ).", "可用于参加中级兵种资格考试（暗魔道士除外）。")
    s = s.replace("Allows a unit to attempt an exam for an advanced class (except Dark Bishop ).", "可用于参加高级兵种资格考试（暗主教除外）。")
    s = s.replace("Allows a unit to attempt an exam for a master class.", "可用于参加最高级兵种资格考试。")
    s = s.replace("Allows a unit to attempt an exam for the Dark Mage or Dark Bishop classes.", "可用于参加暗魔道士或暗主教资格考试。")
    s = s.replace("Allows a unit to attempt an exam for a special class.", "可用于参加特殊兵种资格考试。")
    return re.sub(r"\s{2,}", " ", s).strip()


def translate_class_skill(raw: str) -> str:
    s = raw.strip()
    if not s:
        return s
    parts: list[str] = []
    for chunk in s.split(";"):
        chunk = chunk.strip()
        if chunk.startswith("Innate:"):
            skills = [translate_skill_title(k.strip().rstrip("†*")) for k in chunk[7:].split(",") if k.strip()]
            parts.append("兵种自带特技：" + "、".join(skills))
        elif chunk.startswith("Mastery:"):
            body = chunk[8:].strip()
            exp_note = ""
            m = re.search(r"\(mastery EXP (\d+)\)", body)
            if m:
                exp_note = f"（精通所需经验 {m.group(1)}）"
                body = body[: m.start()].strip()
            skills = [translate_skill_title(k.strip().rstrip("†*")) for k in body.split(",") if k.strip()]
            parts.append("兵种精通特技：" + "、".join(skills) + exp_note)
        else:
            parts.append(chunk)
    return "；".join(parts)


def translate_description_text(category_id: str, title_zh: str, desc: str) -> str:
    s = (desc or "").strip()
    if not s:
        return ""
    if s.startswith("{"):
        return s  # structured JSON handled separately
    # Suppress raw English scraper-audit placeholder blurbs; typed detail tables carry the real game data.
    if category_id in (
        "recruitment", "routes", "classes", "growth", "crests", "battalions",
        "skills", "equipment", "supports", "social", "monastery", "chapters",
        "battles", "mechanics", "dlc",
    ):
        return ""
    return s


def localize_stat_dict(raw_json: str | None, *, is_growth: bool = False, signed: bool = False) -> str | None:
    if not raw_json or raw_json.strip() in ("", "{}", "[]"):
        return raw_json
    try:
        data = json.loads(raw_json)
    except Exception:
        return raw_json
    if not isinstance(data, dict):
        return raw_json
    ordered_keys = ["初始等级", "移动力", "HP", "力量", "魔力", "技巧", "速度", "幸运", "防守", "魔防", "魅力", "体格", "成长合计"]
    out: dict[str, Any] = {}
    for k, v in data.items():
        if k == "source_text":
            out["武器资质"] = v
            continue
        k_zh = STAT_KEY_ZH.get(k.lower(), k)
        if isinstance(v, float) and v.is_integer():
            v = int(v)
        if isinstance(v, (int, float)):
            if is_growth and k_zh not in ("初始等级", "移动力"):
                out[k_zh] = f"{v}%"
            elif signed and v > 0:
                out[k_zh] = f"+{v}"
            else:
                out[k_zh] = v
        else:
            out[k_zh] = v
    # sort nicely keeping known stats first
    reordered: dict[str, Any] = {}
    for ok in ordered_keys:
        if ok in out:
            reordered[ok] = out.pop(ok)
    reordered.update(out)
    return json.dumps(reordered, ensure_ascii=False)


def _translate_th_skill_effect_text(raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    exact = {
        "No special effect": "无特殊附加效果",
        "+1 to Range": "射程＋1",
        "+1 to Range , minimum 2": "射程＋1（最小射程为 2）",
        "+2 to Range": "射程＋2",
        "+3 to Range": "射程＋3",
        "+10 to Avoid -10 to damage taken against magical strikes": "回避＋10；受到魔法攻击时所受伤害－10",
        "+10 to Avoid Converts attack to a brave strike": "回避＋10；转为连续两次攻击（勇者效果）",
        "+10 to Avoid Moves forward 1 tile after combat": "回避＋10；战斗后向前移动 1 格",
        "+10 to Avoid Pushes target forward 1 square after combat": "回避＋10；战斗后将目标击退 1 格",
        "+10 to critical rate": "必杀＋10",
        "+10 to critical rate Converts attack to a brave strike": "必杀＋10；转为连续两次攻击（勇者效果）",
        "+10 to critical rate Ignores terrain effects on target": "必杀＋10；无视目标的地形加成效果",
        "+10 to critical rate Negates counterattacks": "必杀＋10；使敌方无法反击",
        "+10 to critical rate Switches places with target after combat": "必杀＋10；战斗后与目标互换位置",
        "+20 each to Avoid and critical rate Ignores half of target's Defense and Resistance during strike": "回避与必杀各＋20；攻击时无视目标一半防守与魔防",
        "+30 to Avoid": "回避＋30",
        "+30 to Avoid +20 to critical rate +4% to damage output": "回避＋30、必杀＋20、造成伤害＋4%",
        "+30 to critical rate": "必杀＋30",
        "+5 each to Avoid and critical rate Grants provisions upon defeating target": "回避与必杀各＋5；击倒目标时可获得补给品",
        "-1 to Range +15 to Avoid Doubles Attack": "射程－1、回避＋15、攻击力翻倍",
        "-30 to Avoid +50 to critical rate User strikes last": "回避－30、必杀＋50；自身后手攻击",
        "-5 to damage taken against physical strikes": "受到物理攻击时所受伤害－5",
        "Adds user's Skill to the Might calculation": "威力附加自身技巧值",
        "Adds user's Speed to the Might calculation": "威力附加自身速度值",
        "Adds user's accumulated damage to the Might calculation": "威力附加自身已损失的 HP 数值",
        "Converts attack to a brave magical strike": "转为连续两次魔法攻击（按目标魔防结算）",
        "Converts attack to a magical strike , targeting foe's Resistance": "转为魔法攻击（按目标魔防结算）",
        "Deals bonus damage to Terror targets": "对魔物目标特效",
        "Deals bonus damage to armored targets": "对重甲目标特效",
        "Deals bonus damage to flying targets": "对飞行目标特效",
        "Deals bonus damage to mounted targets": "对骑兵目标特效",
        "Doubles terrain effects": "地形效果翻倍",
        "Fatal strikes always hit": "致死一击必定命中",
        "Fixes Range to 2": "射程固定为 2",
        "Halts target's movement for one turn after combat": "战斗后使目标 1 回合内无法移动",
        "Halves damage dealt by magical strikes": "受到魔法攻击时伤害减半",
        "Halves damage dealt by physical strikes": "受到物理攻击时伤害减半",
        "Halves damage dealt by ranged strikes": "受到远程攻击时伤害减半",
        "Heals user equal to damage dealt to target": "回复等同于造成伤害的 HP",
        "Must surround foe with three flying units with this skill to use; +40 critical rate when in use.": "需 3 名持有此特技的飞行单位包围敌军方可使用；发动时必杀＋40",
        "Otherwise- fatal hits leave target with 1 HP": "若此次攻击本应致死，目标将保留 1 点 HP（手下留情）",
        "Pushes adjacent ally forward 1 tile": "将相邻我方单位向前推 1 格",
        "Reflects magical damage back at attacker": "将魔法伤害反弹给攻击方",
        "Seals target's magic for one turn after combat": "战斗后 1 回合内封印目标的魔法",
        "Strike deals five consecutive blows with halved Might": "以半额威力连续发动 5 次攻击（流星）",
        "Switches places with adjacent ally": "与相邻我方单位互换位置",
    }
    if s in exact:
        return exact[s]
    out = s
    out = out.replace("to Avoid", "回避").replace("to critical rate", "必杀").replace("to Range", "射程")
    out = out.replace("Deals bonus damage to", "特效对象：").replace("after combat", "（战斗后）")
    return out


def _translate_th_crest_rule(raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    s = s.replace(" .", ".").replace(" ,", ",")
    if "restore HP equal to 30% of damage dealt. 30% chance to either raise Mt by 5 or stop counterattacks" in s:
        return "使用武器或战技攻击时，有 20%（大）/ 10%（小）/ 5%（纹章道具）概率回复造成伤害 30% 的 HP；触发回复后另有 30% 概率追加「威力＋5」或「阻止敌方反击」（两者几率均等）。"
    if "double Atk and weapon uses for combat arts" in s:
        return "发动战技时，有 20%（大）/ 10%（小）/ 5%（纹章道具）概率使攻击力与武器耐久消耗翻倍（多段战技后续命中触发时不额外耗耐久，仍享受攻击翻倍）。"
    if "strike twice when using a weapon" in s:
        return "使用武器普通攻击时，有 20%（大）/ 10%（小）/ 5%（纹章道具）概率触发二连击（勇者类武器不触发）。"
    m = re.match(r"^(\d+% / \d+% / \d+%) chance to (.+?)\.?$", s)
    if m:
        rates, eff = m.group(1), m.group(2).strip()
        eff_map = {
            "prevent enemy counterattacks when using a weapon": "使用武器攻击时阻止敌方反击",
            "prevent enemy counterattacks when using combat arts": "发动战技时阻止敌方反击",
            "raise Mt by 5 during magic attacks": "魔法攻击时威力＋5",
            "raise Mt by 5 when using combat arts and Staggering Blows": "发动战技与猛击时威力＋5",
            "raise Mt by 5 when using combat arts": "发动战技时威力＋5",
            "raise Mt by 5 when using a weapon": "使用武器攻击时威力＋5",
            "raise Mt by 5 when using recovery magic": "使用回复魔法时回复威力＋5",
            "conserve uses of attack magic": "使用攻击魔法时不消耗使用次数",
            "conserve uses of recovery magic": "使用回复魔法时不消耗使用次数",
            "restore HP equal to 30% of damage dealt when using combat arts": "发动战技时回复造成伤害 30% 的 HP",
        }
        return f"触发概率（大 / 小 / 道具）：{rates}；效果：{eff_map.get(eff, eff)}。"
    return s


def _translate_th_battle_objective(raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    exact = {
        "Rout the enemy": "全灭敌军",
        "Rout all enemies except the Death Knight": "击败除死神骑士外的全部敌军",
        "Rout the enemy or survive 10 turns": "全灭敌军或坚守 10 回合",
        "Rout the enemy or survive for 10 turns": "全灭敌军或坚守 10 回合",
        "Rout the enemy or have Ingrid reach the target destination": "全灭敌军，或令英谷莉特抵达目标地点",
        "Rout the enemy or have Petra reach the target destination": "全灭敌军，或令佩托拉抵达目标地点",
        "Defeat Acheron or survive for 12 turns": "击败阿凯隆或坚守 12 回合",
        "Defeat the Immovable": "击败不动者",
        "Defeat the Phantom Generals": "击败幻影将领",
        "Defeat the Wandering Beast": "击败流浪魔兽",
        "Defeat the Wind Caller": "击败呼风者",
        "Defeat the boss Assassin": "击败敌将（刺客）",
        "Defeat the boss Brigand": "击败敌将（土匪）",
        "Defeat the boss Paladin": "击败敌将（圣骑士）",
        "Defeat the boss Warrior": "击败敌将（勇士）",
        "Defeat the boss Wild Demonic Beast": "击败敌将（狂暴魔兽）",
        "Battle of the Eagle and Lion ( Black Eagles , Blue Lions , Golden Deer )": "鹫狮子之战（黑鹫 / 青狮子 / 金鹿）",
    }
    if s in exact:
        return exact[s]
    if s.startswith("Defeat "):
        who = s[len("Defeat ") :].strip().rstrip(".")
        return f"击败 {translate_character_name(who)}"
    # Fallback to chapter titles table (strip trailing house tags)
    clean = re.sub(r"\s*\([^)]*\)\s*$", "", s).strip()
    try:
        from th_skill_zh import TH_BATTLE_TITLE_ZH
        if s in TH_BATTLE_TITLE_ZH:
            return TH_BATTLE_TITLE_ZH[s]
        if clean in TH_BATTLE_TITLE_ZH:
            return TH_BATTLE_TITLE_ZH[clean]
    except Exception:
        pass
    zh = TH_CHAPTERS.get(s) or TH_CHAPTERS.get(clean)
    if zh:
        return zh
    return translate_chapter_title(s)


def _translate_th_gambit(en: str) -> str:
    gambits = {
        "Disturbance": "扰乱",
        "Onslaught": "一齐突击",
        "Assassinate": "暗杀",
        "Assault Troop": "突击阵",
        "Blaze": "火计",
        "Poisoned Arrows": "毒箭",
        "Fusillade": "一齐射击",
        "Lure": "诱敌",
        "Strider": "神速态势",
        "Stride": "神速态势",
        "Impregnable Wall": "铁壁态势",
        "Blessing": "祝福赋予",
        "Retribution": "应击态势",
        "Sacred Shield": "圣盾态势",
        "Dance of the Goddess": "女神之舞",
        "Resonant Flames": "共鸣火焰",
        "Resonant Ice": "共鸣寒冰",
        "Resonant Lightning": "共鸣雷电",
        "Resonant White Magic": "共鸣白魔法",
        "Group Flames": "广域火计",
        "Group Ice": "广域冰计",
        "Group Lightning": "广域雷计",
        "Assembly": "引诱之计",
        "Flash-Fire Arrows": "火矢齐射",
        "Absorption": "吸收之计",
        "Wave Attack": "波状攻击",
        "Line of Lances": "枪阵突击",
        "Mad Melee": "混战突击",
        "Ashes and Dust": "灰尘齐射",
        "Raging Flames": "烈火之计",
        "Raging Chaos": "魔兽吐息",
        "Counter Attack": "反击态势",
        "Healing Focus": "恢复之计",
        "Recovery Roar": "治愈咆哮",
    }
    return gambits.get(en.strip(), en.strip())


def _translate_th_battalion_group(raw: str) -> str:
    s = raw.strip()
    m = {
        "Infantry battalions/Melee": "步兵骑士团 · 近战",
        "Infantry battalions/Ranged": "步兵骑士团 · 远程",
        "Infantry battalions/Magic": "步兵骑士团 · 魔法",
        "Infantry battalions/Support": "步兵骑士团 · 支援",
        "Cavalry battalions": "骑兵骑士团",
        "Armored battalions": "重甲骑士团",
        "Flying battalions": "飞行骑士团",
        "Enemy-only battalions": "敌方限定骑士团",
    }
    return m.get(s, s.replace("Infantry battalions/", "步兵骑士团 · ").replace("battalions", "骑士团"))


def _translate_th_statue_reward(raw: str) -> str:
    s = (raw or "").strip()
    m = re.match(r"^(Sword|Lance|Axe|Bow|Brawling|Reason|Faith|Authority|Heavy Armor|Riding|Flying)\s*\+(\d+)$", s)
    if m:
        return f"{TH_WEAPON_CATEGORIES.get(m.group(1), m.group(1))}经验＋{m.group(2)}"
    s = s.replace("Experience +", "获得经验值＋")
    s = s.replace("Class Mastery gain +", "兵种精通经验＋")
    s = s.replace("Divine Pulse uses +", "天刻之拍动次数＋")
    s = s.replace("Max ", "上限 ").replace(" +", "＋")
    for k_en, k_zh in STAT_KEY_ZH.items():
        s = re.sub(rf"\b{k_en}\b", k_zh, s, flags=re.IGNORECASE)
    return s


def _translate_th_dining_desc(raw: str) -> str:
    s = (raw or "").strip()
    exact = {
        "A special menu of mature flavors for professors and knights. Effects are enhanced when dining with professors and knights.": "面向教师与骑士团成员的成熟风味特别菜单，与教师/骑士团共餐时效果提升。",
        "A special menu to bring professors and students together. Effects are enhanced when dining with students from your own house.": "增进师生感情的特别菜单，与本学级学生共餐时效果提升。",
        "During this joyous week, dine on scrumptious sweets imported from all across Fódlan. Effects are enhanced for those who love sweets.": "甜点庆典周：全芙朵拉精选甜品，与喜爱甜点的角色共餐时效果提升。",
        "A festival in the dining hall with plenty of fresh fish for all to enjoy. Effects are enhanced when dining a fish enthusiast.": "鲜鱼庆典周：供应丰富鲜鱼料理，与喜爱鱼类的角色共餐时效果提升。",
        "A hearty feast featuring plenty of meat dishes. Effects are enhanced for those who love meat.": "肉食盛宴周：主打丰盛肉类料理，与喜爱肉食的角色共餐时效果提升。",
        "A special week featuring fresh vegetables. Effects are enhanced for those who love vegetables.": "鲜蔬庆典周：精选新鲜蔬菜料理，与喜爱蔬菜的角色共餐时效果提升。",
        "A special week featuring fiery dishes. Effects are enhanced for those who love spicy foods.": "辛辣庆典周：主打重口辛香菜品，与喜爱辣味的角色共餐时效果提升。",
    }
    if s in exact:
        return exact[s]
    if "professors and knights" in s.lower():
        return "面向教师与骑士团的特别活动菜单，同阵营共餐效果增强。"
    if "sweet" in s.lower():
        return "食堂甜点主题周，与喜爱甜点的角色共餐效果增强。"
    if "fish" in s.lower():
        return "食堂鲜鱼主题周，与喜爱鱼类的角色共餐效果增强。"
    if "meat" in s.lower():
        return "食堂肉类主题周，与喜爱肉类的角色共餐效果增强。"
    if "vegetable" in s.lower():
        return "食堂鲜蔬主题周，与喜爱蔬菜的角色共餐效果增强。"
    if "own house" in s.lower() or "students" in s.lower():
        return "食堂师生交流主题周，与本学级学生共餐效果增强。"
    return s


def _translate_th_room_effect(raw: str) -> str:
    s = (raw or "").strip()
    m = re.match(r"^Permanently increases (maximum HP|strength|magic|dexterity|speed|luck|defense|resistance|charm|movement) by (\d+)\.?$", s, re.IGNORECASE)
    if m:
        stat_map = {
            "maximum hp": "最大 HP", "strength": "力量", "magic": "魔力",
            "dexterity": "技巧", "speed": "速度", "luck": "幸运",
            "defense": "防守", "resistance": "魔防", "charm": "魅力", "movement": "移动力",
        }
        return f"使用后永久提升{stat_map.get(m.group(1).lower(), m.group(1))}＋{m.group(2)}。"
    if "renown" in s.lower():
        return s.replace("Grants", "获得").replace("renown", "点名声")
    return translate_equipment_effect(s)


def _format_stat_bonus_compact(d: dict[str, Any], *, is_pct: bool = False) -> str:
    parts: list[str] = []
    for k, v in d.items():
        if v is None:
            continue
        try:
            num = float(v)
        except Exception:
            continue
        if abs(num) < 1e-6:
            continue
        iv = int(num) if num.is_integer() else num
        sign = "+" if iv > 0 else ""
        k_zh = STAT_KEY_ZH.get(k.lower(), k)
        parts.append(f"{k_zh} {sign}{iv}{'%' if is_pct else ''}")
    return " · ".join(parts) if parts else "无补正变化"


def _sanitize_three_houses_details(conn: sqlite3.Connection) -> None:
    # 1. skill_details: flatten effect_json (eliminate raw arrays & source_row/source_table dicts!)
    if _table_exists(conn, "skill_details"):
        section_zh = {
            "Basic combat arts": "基础战技",
            "Non-offensive combat arts": "辅助战技",
            "Effective combat arts": "克制特效战技",
            "Multi-hit combat arts": "多段连击战技",
            "Magic combat arts": "魔导战技",
            "Regalia-specific combat arts": "神圣武器 / 遗产专属战技",
            "Class ability": "职业特技",
            "Black magic": "黑魔法",
            "White magic": "白魔法",
            "Dark magic": "暗魔法",
        }
        for entry_id, stype, wtype, req, eff_j in conn.execute(
            "SELECT entry_id, skill_type, weapon_type, requirement, effect_json FROM skill_details"
        ).fetchall():
            new_wtype = TH_WEAPON_CATEGORIES.get((wtype or "").strip(), wtype)
            new_eff: str | None = None
            if eff_j and eff_j.strip().startswith("{"):
                try:
                    obj = json.loads(eff_j)
                except Exception:
                    obj = None
                if isinstance(obj, dict):
                    clean: dict[str, str] = {}
                    # Source sections
                    secs = obj.get("source_sections")
                    if isinstance(secs, list) and secs:
                        sec_labels = [section_zh.get(str(x).strip(), str(x).strip()) for x in secs if str(x).strip()]
                        if sec_labels:
                            clean["技能分类"] = " / ".join(dict.fromkeys(sec_labels))
                    # Special effects
                    se_list = obj.get("special_effects")
                    if isinstance(se_list, list) and se_list:
                        eff_texts: list[str] = []
                        for item in se_list:
                            raw_t = item.get("text") if isinstance(item, dict) else str(item)
                            if raw_t:
                                eff_texts.append(_translate_th_skill_effect_text(str(raw_t)))
                        uniq_eff = list(dict.fromkeys(t for t in eff_texts if t))
                        if uniq_eff:
                            clean["特殊效果"] = "；".join(uniq_eff)
                    # Source variants (class innate / mastery)
                    sv_list = obj.get("source_variants")
                    if isinstance(sv_list, list) and sv_list:
                        var_parts: list[str] = []
                        for v in sv_list:
                            if not isinstance(v, dict):
                                continue
                            cls_zh = translate_class_title(str(v.get("class_name") or ""))
                            role = str(v.get("role") or "")
                            role_zh = {"mastery": "精通", "innate": "自带"}.get(role, role)
                            exp = v.get("mastery_experience")
                            if cls_zh:
                                if exp:
                                    var_parts.append(f"{cls_zh}（{role_zh}，{exp} EXP）")
                                elif role_zh:
                                    var_parts.append(f"{cls_zh}（{role_zh}）")
                                else:
                                    var_parts.append(cls_zh)
                        if var_parts:
                            clean["习得职业"] = "、".join(dict.fromkeys(var_parts))
                    new_eff = json.dumps(clean, ensure_ascii=False) if clean else None
            conn.execute(
                "UPDATE skill_details SET weapon_type=?, effect_json=? WHERE entry_id=?",
                (new_wtype, new_eff, entry_id),
            )

    # 2. activity_details: translate activity_type, availability_condition, options_json, effects_json
    if _table_exists(conn, "activity_details"):
        act_type_zh = {
            "amiibo_gazebo_song": "Amiibo 凉亭乐曲",
            "dining_hall_event": "食堂特别活动",
            "gift_preference": "礼物偏好",
            "meal_preference": "食堂料理",
            "professor_level_activity_points": "指导等级行动点",
            "recon_mission_battalion": "联机侦察骑士团",
            "room_reward": "主角房间特典",
            "saint_statue_reward_tier": "大圣堂圣人像奖励",
            "shop_listing": "修道院商店商品",
            "tea_party_conversation_stage": "茶会话题阶段",
        }
        shop_zh_map = {
            "Armory": "武器店",
            "Item Shop": "道具店",
            "Traveling Merchants and Anna's Shop/Secret Shop": "行商 / 安娜秘密商店",
        }
        statue_zh_map = {
            "Cethleann statue": "圣赛斯琳像",
            "Cichol statue": "圣奇霍尔像",
            "Indech statue": "圣因迪哈像",
            "Macuil statue": "圣马库伊尔像",
        }
        meal_cat_zh = {
            "Meat": "肉类料理", "Fish": "鱼类料理", "Vegetables": "蔬菜料理",
            "Sweets": "甜点", "Spicy": "辛辣料理", "Bitter": "苦味料理",
        }
        stage_zh = {"First": "第 1 轮", "Second": "第 2 轮", "Third": "第 3 轮", "Fourth": "第 4 轮（终轮）"}
        for entry_id, atype, avail, opt_j, eff_j in conn.execute(
            "SELECT entry_id, activity_type, availability_condition, options_json, effects_json FROM activity_details"
        ).fetchall():
            new_type = act_type_zh.get(atype or "", atype)
            new_avail = avail
            if avail and "Only the cited shop table is known" in avail:
                new_avail = "随章节进度于修道院商店开放购买"
            try:
                opt = json.loads(opt_j) if opt_j else {}
            except Exception:
                opt = {}
            try:
                eff = json.loads(eff_j) if eff_j else {}
            except Exception:
                eff = {}

            clean_opt: dict[str, Any] = {}
            clean_eff: dict[str, Any] = {}

            if atype == "shop_listing":
                if opt.get("shop"):
                    clean_opt["售卖商店"] = shop_zh_map.get(opt["shop"], opt["shop"])
                if opt.get("item_name"):
                    clean_opt["商品名称"] = translate_equipment_title(str(opt["item_name"]))
                if opt.get("stock_source_text"):
                    clean_opt["库存"] = str(opt["stock_source_text"])
                if eff.get("price") is not None:
                    clean_eff["售价"] = f"{eff['price']} G"
            elif atype == "gift_preference":
                liked = eff.get("liked_by") or []
                disliked = eff.get("disliked_by") or []
                if liked:
                    clean_eff["喜欢的角色"] = "、".join(translate_character_name(str(c)) for c in liked)
                if disliked:
                    clean_eff["不喜欢的角色"] = "、".join(translate_character_name(str(c)) for c in disliked)
            elif atype == "meal_preference":
                if opt.get("category"):
                    clean_opt["料理分类"] = meal_cat_zh.get(opt["category"], opt["category"])
                liked = eff.get("liked_by") or []
                disliked = eff.get("disliked_by") or []
                if liked:
                    clean_eff["喜欢的角色"] = "、".join(translate_character_name(str(c)) for c in liked)
                if disliked:
                    clean_eff["不喜欢的角色"] = "、".join(translate_character_name(str(c)) for c in disliked)
            elif atype == "saint_statue_reward_tier":
                if opt.get("source_cost_text"):
                    clean_opt["所需名声点数"] = f"{opt['source_cost_text']} Pt"
                cells = opt.get("source_reward_cells")
                if isinstance(cells, dict):
                    for st_name, rw in cells.items():
                        clean_eff[statue_zh_map.get(st_name, st_name)] = _translate_th_statue_reward(str(rw))
            elif atype == "dining_hall_event":
                if opt.get("served_meal"):
                    clean_opt["供应料理"] = TH_SOCIAL_EXACT.get(opt["served_meal"], opt["served_meal"])
                if opt.get("boosted_characters_source_text"):
                    raw_bc = str(opt["boosted_characters_source_text"])
                    if "Byleth's house" in raw_bc:
                        clean_opt["加成对象"] = "主角所属学级全体学生"
                    else:
                        prefix = ""
                        body = raw_bc
                        if ":" in raw_bc:
                            p, body = raw_bc.split(":", 1)
                            prefix = "教师与骑士团：" if "Professors" in p else p.strip() + "："
                        names = [translate_character_name(n.strip()) for n in re.split(r"[•,]", body) if n.strip()]
                        clean_opt["加成对象"] = prefix + "、".join(names)
                if eff.get("description"):
                    clean_eff["活动说明"] = _translate_th_dining_desc(str(eff["description"]))
            elif atype == "professor_level_activity_points":
                if opt.get("professor_level"):
                    clean_opt["指导等级"] = str(opt["professor_level"])
                if eff.get("activity_points") is not None:
                    clean_eff["行动点数"] = f"{eff['activity_points']} 点"
            elif atype == "amiibo_gazebo_song":
                if opt.get("amiibo_character"):
                    clean_opt["联动 Amiibo"] = TH_CHARACTERS.get(opt["amiibo_character"], opt["amiibo_character"])
                if eff.get("song"):
                    clean_eff["解锁乐曲"] = str(eff["song"])
            elif atype == "recon_mission_battalion":
                if opt.get("battalion_name"):
                    clean_opt["奖励骑士团"] = translate_battalion_title(str(opt["battalion_name"]))
            elif atype == "room_reward":
                if opt.get("item_name"):
                    clean_opt["奖励道具"] = translate_equipment_title(str(opt["item_name"]))
                if eff.get("effect"):
                    clean_eff["道具效果"] = _translate_th_room_effect(str(eff["effect"]))
            elif atype == "tea_party_conversation_stage":
                if opt.get("good_tea_choice_source_text"):
                    clean_opt["话题轮次"] = stage_zh.get(opt["good_tea_choice_source_text"], opt["good_tea_choice_source_text"])
                if opt.get("additional_source_text"):
                    clean_eff["顺延条件"] = str(opt["additional_source_text"]).replace("conversations failed", "轮话题答错")
                if opt.get("other_tea_choice_source_text"):
                    clean_eff["其他触发"] = str(opt["other_tea_choice_source_text"]).replace("conversations failed", "轮话题答错")

            conn.execute(
                "UPDATE activity_details SET activity_type=?, availability_condition=?, options_json=?, effects_json=? WHERE entry_id=?",
                (
                    new_type,
                    new_avail,
                    json.dumps(clean_opt, ensure_ascii=False) if clean_opt else None,
                    json.dumps(clean_eff, ensure_ascii=False) if clean_eff else None,
                    entry_id,
                ),
            )

    # 3. mechanic_details: localize mechanic_type, rule_description, drop English boilerplate, format parameters_json cleanly
    if _table_exists(conn, "mechanic_details"):
        mtype_zh = {
            "battalion": "骑士团资料",
            "campaign_route": "主线故事路线",
            "class_growth_rates": "兵种成长与下马补正",
            "crest": "纹章效果与血脉",
            "dlc_campaign": "DLC 剧情篇章",
            "meal_motivation": "共进晚餐干劲规则",
            "recruitment_condition": "角色招募条件",
            "tea_party_extra_ep": "茶会加分规则",
            "tea_party_result": "茶会结算评级",
        }
        for entry_id, mtype, gver, rule_desc, formula_txt, params_j in conn.execute(
            "SELECT entry_id, mechanic_type, game_version, rule_description, formula_text, parameters_json FROM mechanic_details"
        ).fetchall():
            new_mtype = mtype_zh.get(mtype or "", mtype)
            new_gver = None  # Drop repetitive English "Fire Emblem: Three Houses; exact patch not recorded"
            try:
                params = json.loads(params_j) if params_j else {}
            except Exception:
                params = {}

            new_rule = rule_desc
            clean_params: dict[str, Any] = {}

            if mtype == "crest":
                new_rule = _translate_th_crest_rule(rule_desc or "")
                if params.get("bearers_source_text"):
                    raw_b = re.sub(r"\[\s*\d+\s*\]", "", str(params["bearers_source_text"]))
                    clean_params["纹章持有者"] = "、".join(translate_character_name(n.strip()) for n in raw_b.split(",") if n.strip())
                if params.get("hero_relic"):
                    clean_params["对应英雄遗产"] = translate_equipment_title(str(params["hero_relic"]))
                if params.get("sacred_weapons"):
                    sw = params["sacred_weapons"]
                    if isinstance(sw, list):
                        clean_params["对应神圣武器"] = "、".join(translate_equipment_title(str(x)) for x in sw)
                    else:
                        clean_params["对应神圣武器"] = translate_equipment_title(str(sw))
                if params.get("crest_item"):
                    clean_params["对应纹章道具"] = translate_equipment_title(str(params["crest_item"]))
            elif mtype == "battalion":
                avail_parts: list[str] = []
                if params.get("player_available"):
                    avail_parts.append("我方可雇佣")
                if params.get("enemy_only"):
                    avail_parts.append("仅限敌方")
                elif params.get("enemy_listed"):
                    avail_parts.append("敌军亦有配置")
                new_rule = " · ".join(avail_parts) if avail_parts else "骑士团编队"
                groups = params.get("unit_types") or params.get("source_groups") or []
                if isinstance(groups, list) and groups:
                    clean_params["兵种类别"] = " / ".join(dict.fromkeys(_translate_th_battalion_group(str(g)) for g in groups))
                shops = params.get("shop_listings")
                if isinstance(shops, list) and shops:
                    first = shops[0] if isinstance(shops[0], dict) else {}
                    if first.get("level"):
                        clean_params["所需指挥等级"] = str(first["level"])
                    if first.get("gambit"):
                        clean_params["骑士团计策"] = _translate_th_gambit(str(first["gambit"]))
                    if first.get("price"):
                        clean_params["雇佣费用"] = f"{first['price']} G"
            elif mtype == "class_growth_rates":
                new_rule = "升级时职业对角色成长率的百分比补正（正数为提升，负数为降低）。"
                if isinstance(params.get("player_growth_rates"), dict):
                    clean_params["我方成长补正"] = _format_stat_bonus_compact(params["player_growth_rates"], is_pct=True)
                if isinstance(params.get("class_stat_bonuses"), dict):
                    clean_params["转职能力补正"] = _format_stat_bonus_compact(params["class_stat_bonuses"], is_pct=False)
                if isinstance(params.get("dismount_modifiers"), dict):
                    dm = _format_stat_bonus_compact(params["dismount_modifiers"], is_pct=False)
                    if dm != "无补正变化":
                        clean_params["下马能力变化"] = dm
            elif mtype == "campaign_route":
                new_rule = TH_FACTIONS_AND_ROUTES.get((rule_desc or "").strip(), rule_desc)
            elif mtype == "dlc_campaign":
                cnt = params.get("chapter_count_in_source_table")
                new_rule = f"《煤暗之章》为独立付费 DLC 剧情篇章，共包含 {cnt or 7} 个核心章节。"
            elif mtype == "meal_motivation":
                pref_zh = {"Liked": "喜欢该料理", "Neutral": "普通评价", "Disliked": "不喜欢该料理"}.get(str(params.get("preference") or ""), str(params.get("preference") or ""))
                mot = params.get("motivation_gained")
                new_rule = f"共进晚餐时角色{pref_zh}的干劲回复量。" if pref_zh else "共进晚餐的干劲回复规则。"
                if pref_zh:
                    clean_params["喜好程度"] = pref_zh
                if mot is not None:
                    clean_params["干劲提升"] = f"+{mot} 格"
            elif mtype == "recruitment_condition":
                new_rule = translate_join_condition(rule_desc or "")
                if params.get("character_name"):
                    clean_params["角色"] = translate_character_name(str(params["character_name"]))
                if params.get("roster_section"):
                    clean_params["归属分类"] = TH_FACTIONS_AND_ROUTES.get(str(params["roster_section"]), str(params["roster_section"]))
                if params.get("first_appears_in"):
                    clean_params["首次登场"] = translate_join_condition(str(params["first_appears_in"]))
            elif mtype == "tea_party_extra_ep":
                act_map = {
                    "Serving a preferred tea": "端出角色喜爱的茶叶",
                    "Selecting a preferred topic (Round 1)": "第 1 轮选对喜好话题",
                    "Selecting a preferred topic (Round 2)": "第 2 轮选对喜好话题",
                    "Selecting a preferred topic (Round 3)": "第 3 轮选对喜好话题",
                    "Selecting the correct response (Finale)": "终轮结语选对回应",
                }
                act_zh = act_map.get(str(params.get("action") or ""), str(params.get("action") or ""))
                new_rule = f"茶会中「{act_zh}」时的额外行动力加分。" if act_zh else "茶会行动点加分规则。"
                if act_zh:
                    clean_params["触发动作"] = act_zh
                if params.get("ep_gained") is not None:
                    clean_params["额外得分"] = f"{params['ep_gained']} EP"
            elif mtype == "tea_party_result":
                rating_zh = {
                    "Perfect": "完美茶会", "Great": "绝佳茶会", "Good": "不错",
                    "Okay": "一般", "Poor": "冷场", "Fail": "失败",
                }.get(str(params.get("in_game_rating") or ""), str(params.get("in_game_rating") or ""))
                new_rule = f"茶会累计达成对应评价时的好感与魅力结算。"
                if params.get("total_ep") is not None:
                    clean_params["累计评分"] = f"{params['total_ep']} EP"
                if rating_zh:
                    clean_params["茶会评价"] = rating_zh
                if params.get("support_points") is not None:
                    clean_params["支援点数"] = f"+{params['support_points']}"
                if params.get("charm_increase_chance_percent") is not None:
                    clean_params["魅力提升几率"] = f"{params['charm_increase_chance_percent']}%"

            conn.execute(
                "UPDATE mechanic_details SET mechanic_type=?, game_version=?, rule_description=?, parameters_json=? WHERE entry_id=?",
                (
                    new_mtype,
                    new_gver,
                    new_rule,
                    json.dumps(clean_params, ensure_ascii=False) if clean_params else None,
                    entry_id,
                ),
            )


def _localize_three_houses_db(conn: sqlite3.Connection) -> None:
    entries = conn.execute(
        "SELECT id, category_id, title, title_en, subtitle, description, search_text FROM content_entries"
    ).fetchall()

    for entry_id, cat, title, title_en, subtitle, desc, search_text in entries:
        src_en = title_en or title
        if cat == "characters":
            new_title = TH_CHARACTERS.get(title, TH_CHARACTERS.get(src_en, title))
        elif cat == "recruitment":
            new_title = translate_recruitment_title(src_en)
        elif cat == "routes":
            new_title = TH_FACTIONS_AND_ROUTES.get(title, title)
        elif cat in ("classes", "growth"):
            new_title = translate_class_title(src_en)
        elif cat == "crests":
            new_title = TH_CRESTS.get(src_en, title)
        elif cat == "battalions":
            new_title = translate_battalion_title(src_en)
        elif cat == "skills":
            new_title = translate_skill_title(src_en)
        elif cat == "equipment":
            new_title = translate_equipment_title(src_en)
        elif cat == "supports":
            new_title = translate_support_title(src_en)
        elif cat == "social":
            new_title = TH_SOCIAL_EXACT.get(src_en, title)
        elif cat == "monastery":
            new_title = translate_monastery_title(src_en)
        elif cat in ("chapters", "battles"):
            new_title = translate_chapter_title(src_en)
        elif cat == "mechanics":
            new_title = translate_mechanics_title(src_en)
        else:
            new_title = title

        new_sub = subtitle
        if cat != "characters":  # character injection sets subtitle directly
            new_sub = translate_th_subtitle(cat, subtitle or "")
        new_desc = desc if (cat == "characters" and (desc or "").startswith("{")) else translate_description_text(cat, new_title, desc)
        merged_search = " ".join(p for p in (new_title, new_sub, src_en, search_text) if p)

        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (new_title, new_sub, new_desc, merged_search, entry_id),
        )

    # Localize typed detail tables for Three Houses
    # 1. class_details
    if _table_exists(conn, "class_details"):
        for entry_id, tier, mv_type, base_j, growth_j, cskill in conn.execute(
            "SELECT entry_id, tier, movement_type, base_stats_json, growths_json, class_skill FROM class_details"
        ).fetchall():
            tier_zh = {
                "Unique": "专属", "Beginner": "初级", "Intermediate": "中级",
                "Advanced": "高级", "Master": "最高级", "Special": "特殊", "--": "特殊 / 魔物",
            }.get(tier or "", tier)
            mv_zh = ", ".join(
                {"infantry": "步兵", "armored": "重甲", "cavalry": "骑兵", "flying": "飞行", "monster": "魔物", "dragon": "龙族"}.get(t.strip().lower(), t.strip())
                for t in (mv_type or "").split(",") if t.strip()
            ) or mv_type
            conn.execute(
                "UPDATE class_details SET tier=?, movement_type=?, base_stats_json=?, growths_json=?, class_skill=? WHERE entry_id=?",
                (
                    tier_zh,
                    mv_zh,
                    localize_stat_dict(base_j),
                    localize_stat_dict(growth_j, is_growth=True),
                    translate_class_skill(cskill) if cskill else cskill,
                    entry_id,
                ),
            )

    # 2. equipment_details
    if _table_exists(conn, "equipment_details"):
        for entry_id, eq_type, sub_type, eff in conn.execute(
            "SELECT entry_id, equipment_type, subtype, effect FROM equipment_details"
        ).fetchall():
            eq_zh = {"weapon": "武器", "equipment": "装备 / 饰品", "item": "消耗品 / 道具"}.get((eq_type or "").lower(), eq_type)
            sub_zh = TH_WEAPON_CATEGORIES.get(sub_type or "", {"Equipment": "装备", "Item": "道具"}.get(sub_type or "", sub_type))
            eff_zh = translate_equipment_effect(eff) if eff else eff
            conn.execute(
                "UPDATE equipment_details SET equipment_type=?, subtype=?, effect=? WHERE entry_id=?",
                (eq_zh, sub_zh, eff_zh, entry_id),
            )

    # 3. chapter_details
    if _table_exists(conn, "chapter_details"):
        for entry_id, route, ch_num, obj, def_cond, dep_j, en_j in conn.execute(
            "SELECT entry_id, route, chapter_number, objective, defeat_condition, deployment_json, enemies_json FROM chapter_details"
        ).fetchall():
            route_zh = TH_FACTIONS_AND_ROUTES.get(route or "", route)
            if ch_num == "Prologue":
                ch_zh = "序章"
            elif ch_num and ch_num.startswith("Chapter "):
                ch_zh = f"第 {ch_num[8:].strip()} 章"
            else:
                ch_zh = ch_num
            obj_zh = _translate_th_battle_objective(obj) if obj else obj
            def_zh = def_cond
            if def_zh:
                def_zh = def_zh.replace(" *", "")
                def_zh = def_zh.replace("the player's army is routed", "我方全军覆没")
                def_zh = def_zh.replace("Any player unit dies", "任意我方单位阵亡")
                def_zh = def_zh.replace("an enemy seizes a defended location", "敌军占领防卫据点")
                def_zh = def_zh.replace("a defended location is captured", "防卫据点失守")
                def_zh = def_zh.replace("dies", "战败")
                def_zh = def_zh.replace(" or ", " 或 ")
                for en_n, zh_n in TH_CHAR_SHORT.items():
                    def_zh = re.sub(rf"\b{re.escape(en_n)}\b", zh_n, def_zh)
            dep_zh = dep_j
            if dep_j:
                try:
                    d = json.loads(dep_j)
                    if "source_new_units" in d:
                        raw_u = str(d["source_new_units"] or "").strip()
                        if not raw_u or raw_u.lower() in ("none", "-", "--"):
                            dep_zh = None
                        else:
                            units = [translate_character_name(u.strip()) for u in raw_u.split(",") if u.strip() and u.strip().lower() != "none"]
                            dep_zh = json.dumps({"登场新单位": "、".join(units)}, ensure_ascii=False) if units else None
                except Exception:
                    pass
            en_zh = en_j
            if en_j:
                try:
                    e = json.loads(en_j)
                    if "source_bosses" in e:
                        bosses = [translate_character_name(b.strip()) for b in e["source_bosses"].split(",") if b.strip()]
                        en_zh = json.dumps({"敌方首领": "、".join(bosses)}, ensure_ascii=False)
                except Exception:
                    pass
            conn.execute(
                "UPDATE chapter_details SET route=?, chapter_number=?, objective=?, defeat_condition=?, deployment_json=?, enemies_json=? WHERE entry_id=?",
                (route_zh, ch_zh, obj_zh, def_zh, dep_zh, en_zh, entry_id),
            )

    # 4. support_details (populated with full Chinese conversations in prepare_android_pack.py)
    if _table_exists(conn, "support_details"):
        conn.execute("UPDATE support_details SET dialogue_summary=NULL WHERE dialogue_summary LIKE '%尚待补充%' OR dialogue_summary LIKE '%not captured%'")

    # 5. skill_details
    if _table_exists(conn, "skill_details"):
        skill_type_zh = {
            "combat_art": "战技",
            "class_ability": "职业特技",
            "black_magic": "黑魔法",
            "white_magic": "白魔法",
            "dark_magic": "暗魔法",
        }
        for entry_id, stype in conn.execute("SELECT entry_id, skill_type FROM skill_details").fetchall():
            conn.execute(
                "UPDATE skill_details SET skill_type=? WHERE entry_id=?",
                (skill_type_zh.get(stype or "", stype), entry_id),
            )

    _sanitize_three_houses_details(conn)


def _table_exists(conn: sqlite3.Connection, name: str) -> bool:
    return conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None


def _localize_engage_db(conn: sqlite3.Connection) -> None:
    # 1. Translate English chapter entries & subtitles
    for entry_id, title, title_en, subtitle, desc, search_text in conn.execute(
        "SELECT id, title, title_en, subtitle, description, search_text FROM content_entries WHERE category_id IN ('chapters', 'paralogues', 'dlc-chapters')"
    ).fetchall():
        src_en = title_en or title
        mapped = ENGAGE_CHAPTERS.get(title) or ENGAGE_CHAPTERS.get(src_en)
        new_title = mapped[0] if mapped else title
        new_sub = mapped[1] if mapped else translate_th_subtitle("chapters", subtitle or "")
        if not mapped:
            sub_map = {
                "Main story": "主线章节",
                "Paralogues": "外传",
                "Divine Paralogues": "神龙之章外传",
                "Fell Xenologue": "邪龙之章 DLC",
            }
            new_sub = sub_map.get(subtitle or "", subtitle)
        new_desc = desc
        if desc and desc.startswith("English chapter-index entry"):
            new_desc = ""
        merged_search = " ".join(p for p in (new_title, new_sub, src_en, search_text) if p)
        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (new_title, new_sub, new_desc, merged_search, entry_id),
        )

    # 2. Localize Engage stat JSON dicts in class_details & bond_ring_details
    if _table_exists(conn, "class_details"):
        for entry_id, base_j, growth_j, cap_j in conn.execute(
            "SELECT entry_id, base_stats_json, growths_json, caps_json FROM class_details"
        ).fetchall():
            conn.execute(
                "UPDATE class_details SET base_stats_json=?, growths_json=?, caps_json=? WHERE entry_id=?",
                (
                    localize_stat_dict(base_j),
                    localize_stat_dict(growth_j, is_growth=True),
                    localize_stat_dict(cap_j),
                    entry_id,
                ),
            )
    if _table_exists(conn, "bond_ring_details"):
        for entry_id, bonuses_j in conn.execute("SELECT entry_id, stat_bonuses_json FROM bond_ring_details").fetchall():
            conn.execute(
                "UPDATE bond_ring_details SET stat_bonuses_json=? WHERE entry_id=?",
                (localize_stat_dict(bonuses_j, signed=True), entry_id),
            )

    # 3. Localize game_version note in mechanic_details (remove raw workbook revision boilerplate)
    if _table_exists(conn, "mechanic_details"):
        conn.execute(
            "UPDATE mechanic_details SET game_version=NULL WHERE game_version LIKE 'Engage; workbook%'"
        )


def localize_pack_db(conn: sqlite3.Connection, game_id: str) -> None:
    if game_id == "three-houses":
        _localize_three_houses_db(conn)
    elif game_id == "engage":
        _localize_engage_db(conn)
