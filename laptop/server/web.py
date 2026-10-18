"""Web lookups so Nova can answer current questions. Only the search query and the
pages it reads leave the laptop, and both pass through the leak guard first."""
import html
import re
import urllib.parse
from html.parser import HTMLParser

import httpx

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"}
SKIP_HOSTS = ("youtube.com", "facebook.com", "instagram.com", "tiktok.com", "x.com", "twitter.com")


class _Text(HTMLParser):
    """Visible text of a page, minus scripts, styles and navigation chrome."""
    SKIP = {"script", "style", "noscript", "svg", "nav", "footer", "header", "form", "aside"}

    def __init__(self):
        super().__init__()
        self.parts, self.depth = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.depth += 1

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.depth:
            self.depth -= 1

    def handle_data(self, data):
        if not self.depth and data.strip():
            self.parts.append(data.strip())


def page_text(raw_html: str, limit: int = 2500) -> str:
    p = _Text()
    try:
        p.feed(raw_html)
    except Exception:
        pass
    text = re.sub(r"\s+", " ", " ".join(p.parts))
    return text[:limit]


def parse_ddg(raw: str, n: int = 5):
    """[(title, url, snippet)] from DuckDuckGo's HTML results page."""
    results = []
    blocks = re.split(r'<div class="result[ "]', raw)[1:]
    for b in blocks:
        if "result--ad" in b[:200]:
            continue
        a = re.search(r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', b, re.S)
        if not a:
            continue
        href = html.unescape(a.group(1))
        if "duckduckgo.com/y.js" in href:
            continue
        q = urllib.parse.urlparse(href if "://" in href else "https:" + href)
        uddg = urllib.parse.parse_qs(q.query).get("uddg")
        url = uddg[0] if uddg else href
        sn = re.search(r'class="result__snippet"[^>]*>(.*?)</a>', b, re.S)
        strip = lambda s: html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()
        results.append((strip(a.group(2)), url, strip(sn.group(1) if sn else "")))
        if len(results) >= n:
            break
    return results


async def lookup(query: str, pages: int = 2) -> tuple[str, list[str]]:
    """Search and read the top pages. Returns (digest for the model, list of URLs contacted)."""
    contacted = ["duckduckgo.com"]
    async with httpx.AsyncClient(timeout=10, headers=UA, follow_redirects=True) as c:
        r = await c.post("https://html.duckduckgo.com/html/", data={"q": query})
        results = parse_ddg(r.text)
        if not results:
            return f"No web results found for: {query}", contacted
        out = ["Search results:"]
        for i, (title, url, snip) in enumerate(results, 1):
            out.append(f"{i}. {title} ({urllib.parse.urlparse(url).netloc}) - {snip}")
        read = 0
        for title, url, _ in results:
            if read >= pages or any(h in url for h in SKIP_HOSTS):
                continue
            try:
                pr = await c.get(url)
                if "text/html" not in pr.headers.get("content-type", ""):
                    continue
                contacted.append(urllib.parse.urlparse(url).netloc)
                out.append(f"\nFrom {urllib.parse.urlparse(url).netloc}: {page_text(pr.text)}")
                read += 1
            except Exception:
                continue
    return "\n".join(out), contacted
