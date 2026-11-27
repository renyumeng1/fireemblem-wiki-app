#!/usr/bin/env python3
"""Fetch official Simplified Chinese (zh-cn) Three Houses support conversations from houses.fedatamine.com."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
import urllib.request

BASE = "https://houses.fedatamine.com"
UA = "Mozilla/5.0 (FECompanionDataFetcher/1.0)"
OUT = Path("data/games/three-houses/sources/extracts/three-houses-supports-zh.json")

RANK_ORDER = {"C": 0, "C+": 1, "B": 2, "B+": 3, "A": 4, "A+": 5, "S": 6}


def http_get(url: str, retries: int = 3) -> str:
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            last_err = e
            time.sleep(0.5 * (attempt + 1))
    raise RuntimeError(f"Failed to fetch {url}: {last_err}")


class SupportDialogueParser(HTMLParser):
    """Parse fedatamine zh-cn support page `<main>` cleanly into ordered dialogue lines & choice branches."""

    def __init__(self) -> None:
        super().__init__()
        self.in_main = False
        self.main_depth = 0
        self.lines: list[dict[str, str]] = []

        # Track dialogue block `<div class="flex-grow-1 px-3">`
        self.in_speech = False
        self.speech_depth = 0
        self.in_speaker_a = False
        self.in_listen = False
        self.listen_depth = 0
        self.cur_speaker: list[str] = []
        self.cur_text: list[str] = []

        # Track choice tab labels `<a role="tab" id="tab-group-...">`
        self.tab_labels: dict[str, str] = {}
        self.in_tab_a = False
        self.cur_tab_id = ""
        self.cur_tab_text: list[str] = []

        # Track active tab-pane `<div class="tab-pane ..." aria-labelledby="...">`
        self.in_tab_pane = False
        self.pane_depth = 0
        self.pane_label_id = ""
        self.pane_emitted_header = False

        # Track header rank nav links
        self.available_ranks: list[str] = []
        self.in_nav_rank = False
        self.cur_nav_href = ""
        self.cur_nav_text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr_map = dict(attrs)
        cls = (attr_map.get("class") or "").strip()

        if tag == "main":
            self.in_main = True
            self.main_depth = 1
            return
        if not self.in_main:
            return

        if tag in ("div", "main"):
            self.main_depth += 1

        # Header rank navigation pills
        if tag == "a" and "nav-link" in cls and attr_map.get("role") != "tab":
            href = attr_map.get("href") or ""
            if "/zh-cn/supports/" in href:
                self.in_nav_rank = True
                self.cur_nav_href = href
                self.cur_nav_text = []

        # Dialogue choice option tabs
        if tag == "a" and attr_map.get("role") == "tab":
            tid = attr_map.get("id") or ""
            if tid:
                self.in_tab_a = True
                self.cur_tab_id = tid
                self.cur_tab_text = []

        # Choice tab-pane start -> emit choice header when first speech appears inside it
        if tag == "div" and "tab-pane" in cls:
            self.in_tab_pane = True
            self.pane_depth = 1
            self.pane_label_id = attr_map.get("aria-labelledby") or ""
            self.pane_emitted_header = False
        elif self.in_tab_pane and tag == "div":
            self.pane_depth += 1

        # Speech container `<div class="flex-grow-1 px-3">`
        if tag == "div" and "flex-grow-1" in cls and "px-3" in cls:
            self.in_speech = True
            self.speech_depth = 1
            self.cur_speaker = []
            self.cur_text = []
            return
        if self.in_speech:
            if tag == "div":
                self.speech_depth += 1
                if "listen" in cls:
                    self.in_listen = True
                    self.listen_depth = 1
                    return
            if self.in_listen and tag == "div":
                self.listen_depth += 1
            if tag == "a" and "font-weight-bold" in cls:
                self.in_speaker_a = True
            if tag == "br" and self.cur_text:
                self.cur_text.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if not self.in_main:
            return

        if self.in_nav_rank and tag == "a":
            self.in_nav_rank = False
            rk = "".join(self.cur_nav_text).strip()
            if rk in RANK_ORDER and rk not in self.available_ranks:
                self.available_ranks.append(rk)

        if self.in_tab_a and tag == "a":
            self.in_tab_a = False
            lbl = re.sub(r"\s+", " ", "".join(self.cur_tab_text)).strip()
            if self.cur_tab_id and lbl:
                self.tab_labels[self.cur_tab_id] = lbl

        if self.in_speech:
            if self.in_speaker_a and tag == "a":
                self.in_speaker_a = False
            elif self.in_listen and tag == "div":
                self.listen_depth -= 1
                if self.listen_depth <= 0:
                    self.in_listen = False
            elif tag == "div":
                self.speech_depth -= 1
                if self.speech_depth <= 0:
                    self.in_speech = False
                    speaker = re.sub(r"\s+", " ", "".join(self.cur_speaker)).strip()
                    raw_txt = "".join(self.cur_text)
                    clean_lines = [ln.strip() for ln in raw_txt.splitlines() if ln.strip()]
                    text = "".join(clean_lines)
                    if text:
                        if self.in_tab_pane and not self.pane_emitted_header:
                            choice_lbl = self.tab_labels.get(self.pane_label_id, "")
                            if choice_lbl:
                                self.lines.append({"speaker": "选项", "text": choice_lbl})
                            self.pane_emitted_header = True
                        self.lines.append({"speaker": speaker or "旁白", "text": text})

        if self.in_tab_pane and tag == "div":
            self.pane_depth -= 1
            if self.pane_depth <= 0:
                self.in_tab_pane = False

        if tag == "main":
            self.in_main = False

    def handle_data(self, data: str) -> None:
        if not self.in_main:
            return
        txt = html.unescape(data)
        if self.in_nav_rank:
            self.cur_nav_text.append(txt)
        if self.in_tab_a:
            self.cur_tab_text.append(txt)
        if self.in_speech:
            if self.in_speaker_a:
                self.cur_speaker.append(txt)
            elif not self.in_listen:
                self.cur_text.append(txt)


def main() -> int:
    # 1. Discover all character support pages
    idx_html = http_get(f"{BASE}/zh-cn/supports/")
    char_paths = sorted(set(re.findall(r'href="(/zh-cn/supports/[A-Za-z_]+)"', idx_html)))
    print(f"Found {len(char_paths)} character support index pages on fedatamine zh-cn", flush=True)

    # 2. Fetch all character index pages concurrently to gather every unique conversation URL
    conv_urls: dict[tuple[str, str, str], str] = {}

    def fetch_char_index(path: str) -> list[str]:
        h = http_get(BASE + path)
        return re.findall(r'href="(/zh-cn/supports/[A-Za-z_]+/[A-Za-z_]+/[CBAS][+]?)"', h)

    with ThreadPoolExecutor(max_workers=12) as ex:
        for links in ex.map(fetch_char_index, char_paths):
            for lk in links:
                parts = lk.strip("/").split("/")
                if len(parts) != 5:
                    continue
                _, _, c1, c2, rk = parts
                if rk not in RANK_ORDER:
                    continue
                pair_key = tuple(sorted((c1, c2)))
                conv_urls.setdefault((pair_key[0], pair_key[1], rk), lk)

    print(f"Discovered {len(conv_urls)} unique support conversations across {len({(a,b) for a,b,_ in conv_urls})} pairs", flush=True)

    # 3. Download & parse every support conversation concurrently
    pairs: dict[str, dict] = {}
    errors: list[str] = []

    def fetch_one(item: tuple[tuple[str, str, str], str]) -> tuple[str, str, str, list[dict[str, str]]]:
        (c1, c2, rk), lk = item
        page_html = http_get(BASE + lk)
        parser = SupportDialogueParser()
        parser.feed(page_html)
        return (c1, c2, rk, parser.lines)

    done = 0
    with ThreadPoolExecutor(max_workers=16) as ex:
        futs = {ex.submit(fetch_one, it): it for it in conv_urls.items()}
        for fut in as_completed(futs):
            it = futs[fut]
            try:
                c1, c2, rk, lines = fut.result()
                key = f"{c1} + {c2}"
                entry = pairs.setdefault(key, {"char1": c1, "char2": c2, "ranks": {}, "url_sample": BASE + it[1]})
                entry["ranks"][rk] = lines
            except Exception as e:
                errors.append(f"{it[1]}: {e}")
            done += 1
            if done % 150 == 0 or done == len(conv_urls):
                print(f"  Progress: {done}/{len(conv_urls)} (errors={len(errors)})", flush=True)

    # Sort ranks inside each pair cleanly
    for entry in pairs.values():
        entry["ranks"] = dict(sorted(entry["ranks"].items(), key=lambda kv: RANK_ORDER.get(kv[0], 99)))
        entry["rank_list"] = list(entry["ranks"].keys())

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps({
            "source": "https://houses.fedatamine.com/zh-cn/supports/",
            "retrieved_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "pair_count": len(pairs),
            "conversation_count": sum(len(p["ranks"]) for p in pairs.values()),
            "errors": errors,
            "pairs": dict(sorted(pairs.items())),
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Saved {len(pairs)} pairs ({sum(len(p['ranks']) for p in pairs.values())} conversations) -> {OUT}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
