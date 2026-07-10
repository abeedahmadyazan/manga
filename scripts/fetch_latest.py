#!/usr/bin/env python3
"""
Fetch latest + popular Arabic manga from multiple sources.
Outputs: cache/latest.json, cache/popular.json

Used by GitHub Actions (every 2h) to keep a cached list on gh-pages branch.
App reads from jsDelivr CDN:
  https://cdn.jsdelivr.net/gh/abeedahmadyazan/mangaapp@gh-pages/cache/latest.json
"""
import json
import os
import sys
import re
import urllib.request
import urllib.error
import urllib.parse
from datetime import datetime, timezone

UA = 'MangaApp/1.0 (cache-updater)'  # MangaDex rejects browser UAs
TIMEOUT = 20


def http_get(url, headers=None, timeout=TIMEOUT):
    h = {'User-Agent': UA, 'Accept': 'application/json'}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def pick_title(attrs):
    title_obj = attrs.get('title', {}) or {}
    if title_obj.get('ar'):
        return title_obj['ar']
    if title_obj.get('en'):
        return title_obj['en']
    for alt in attrs.get('altTitles', []) or []:
        if alt.get('ar'):
            return alt['ar']
    for alt in attrs.get('altTitles', []) or []:
        if alt.get('en'):
            return alt['en']
    if title_obj.get('ja-ro'):
        return title_obj['ja-ro']
    for v in title_obj.values():
        if v:
            return v
    for alt in attrs.get('altTitles', []) or []:
        for v in alt.values():
            if v:
                return v
    return 'بدون عنوان'


def get_cover(m):
    mid = m['id']
    for rel in m.get('relationships', []) or []:
        if rel.get('type') == 'cover_art':
            fn = (rel.get('attributes') or {}).get('fileName')
            if fn:
                return 'https://uploads.mangadex.org/covers/%s/%s.256.jpg' % (mid, fn)
    return ''


def fetch_mangadex_latest():
    params = urllib.parse.urlencode([
        ('limit', '50'), ('offset', '0'),
        ('order[latestUploadedChapter]', 'desc'),
        ('availableTranslatedLanguage[]', 'ar'),
        ('includes[]', 'cover_art'),
        ('contentRating[]', 'safe'),
        ('contentRating[]', 'suggestive'),
    ])
    url = 'https://api.mangadex.org/manga?' + params
    try:
        data = json.loads(http_get(url, {'Accept': 'application/json'}))
        items = []
        for m in data.get('data', []):
            try:
                attrs = m.get('attributes', {}) or {}
                items.append({
                    'id': m['id'],
                    'title': pick_title(attrs),
                    'cover': get_cover(m),
                    'source': 'mangadex',
                    'status': attrs.get('status', 'ongoing')
                })
            except Exception as e:
                print('  skip: %s' % e, file=sys.stderr)
        return items
    except Exception as e:
        print('MangaDex latest error: %s' % e, file=sys.stderr)
        return []


def fetch_mangadex_popular():
    params = urllib.parse.urlencode([
        ('limit', '50'), ('offset', '0'),
        ('order[followedCount]', 'desc'),
        ('availableTranslatedLanguage[]', 'ar'),
        ('includes[]', 'cover_art'),
        ('contentRating[]', 'safe'),
        ('contentRating[]', 'suggestive'),
    ])
    url = 'https://api.mangadex.org/manga?' + params
    try:
        data = json.loads(http_get(url, {'Accept': 'application/json'}))
        items = []
        for m in data.get('data', []):
            try:
                attrs = m.get('attributes', {}) or {}
                items.append({
                    'id': m['id'],
                    'title': pick_title(attrs),
                    'cover': get_cover(m),
                    'source': 'mangadex',
                    'status': attrs.get('status', 'ongoing')
                })
            except:
                pass
        return items
    except Exception as e:
        print('MangaDex popular error: %s' % e, file=sys.stderr)
        return []


def fetch_3asq_latest():
    target = 'https://3asq.pro/'
    proxies = [
        'https://proxy.cors.sh/' + target,
        'https://api.allorigins.win/raw?url=' + urllib.parse.quote(target),
        'https://corsproxy.io/?url=' + urllib.parse.quote(target),
    ]
    for url in proxies:
        try:
            html = http_get(url, timeout=25).decode('utf-8', errors='ignore')
            if '<html' not in html.lower() or len(html) < 1000:
                continue
            items = parse_3asq_listing(html)
            if items:
                print('  3asq OK via %s: %d items' % (url.split('/')[2], len(items)))
                return items
        except Exception as e:
            print('  3asq via %s: %s' % (url.split('/')[2], e), file=sys.stderr)
            continue
    print('  3asq: all proxies failed')
    return []


def parse_3asq_listing(html):
    items = []
    card_re = re.compile(
        r'<div\s+class="page-item-detail[\s\S]*?</div>\s*<!--\s*\.page-item-detail\s*-->',
        re.IGNORECASE
    )
    for m in card_re.finditer(html):
        block = m.group()
        slug_m = re.search(r'href="https?://3asq\.pro/manga/([\w-]+)/?"', block, re.I)
        if not slug_m:
            continue
        slug = slug_m.group(1)
        title_m = re.search(r'<div\s+class="post-title[\s\S]*?<a[^>]*>([^<]+)</a>', block, re.I)
        title = title_m.group(1).strip() if title_m else slug
        cover_m = re.search(r'<img[^>]+src="([^"]+)"', block, re.I)
        cover = cover_m.group(1) if cover_m else ''
        items.append({'id': slug, 'title': title, 'cover': cover, 'source': '3asq'})
    seen = set()
    out = []
    for it in items:
        if it['id'] not in seen:
            seen.add(it['id'])
            out.append(it)
    return out


def main():
    print('=== MangaDex latest (Arabic) ===')
    md_latest = fetch_mangadex_latest()
    print('  -> %d items' % len(md_latest))

    print('\n=== 3asq latest ===')
    asq_latest = fetch_3asq_latest()
    print('  -> %d items' % len(asq_latest))

    print('\n=== MangaDex popular (Arabic) ===')
    md_popular = fetch_mangadex_popular()
    print('  -> %d items' % len(md_popular))

    seen = set()
    merged = []
    for it in md_latest + asq_latest:
        if it['id'] not in seen:
            seen.add(it['id'])
            merged.append(it)

    now = datetime.now(timezone.utc).isoformat()
    os.makedirs('cache', exist_ok=True)

    with open('cache/latest.json', 'w', encoding='utf-8') as f:
        json.dump({'updated_at': now, 'count': len(merged), 'items': merged},
                  f, ensure_ascii=False, indent=2)
    with open('cache/popular.json', 'w', encoding='utf-8') as f:
        json.dump({'updated_at': now, 'count': len(md_popular), 'items': md_popular},
                  f, ensure_ascii=False, indent=2)

    print('\n[OK] latest.json: %d items (MangaDex: %d, 3asq: %d)' % (
        len(merged), len(md_latest), len(asq_latest)))
    print('[OK] popular.json: %d items' % len(md_popular))
    if merged:
        print('\nTop 10 latest:')
        for it in merged[:10]:
            print('  [%s] %s' % (it['source'], it['title'][:60]))


if __name__ == '__main__':
    main()
