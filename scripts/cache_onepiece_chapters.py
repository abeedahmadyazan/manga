#!/usr/bin/env python3
"""
Cache the latest N chapters of One Piece (Arabic) to gh-pages.

Strategy:
1. Find latest chapter on 3asq (via proxy)
2. For each of the last 10 chapters, fetch page URLs from 3asq
3. Download each page, convert to WebP (q80), save to cache/chapters/{num}/page-{i}.webp
4. Generate cache/chapters/manifest.json with chapter → pages mapping

The app reads the manifest first, then loads pages from CDN.
This way the app NEVER needs to hit 3asq at runtime for the latest chapters.
"""
import json
import os
import re
import sys
import io
import urllib.request
import urllib.error
import urllib.parse
from datetime import datetime, timezone

try:
    from PIL import Image
except ImportError:
    print('PIL not available, installing...', file=sys.stderr)
    import subprocess
    subprocess.check_call([sys.executable, '-m', 'pip', 'install', 'Pillow'])
    from PIL import Image

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
MANGA_SLUG = 'one-piece'
ASQ_BASE = 'https://3asq.pro'
PROXIES = [
    'https://proxy.cors.sh/',
    'https://api.allorigins.win/raw?url=',
    'https://corsproxy.io/?url=',
]
LATEST_CHAPTERS_TO_CACHE = 10  # cache only the last 10 chapters
WEBP_QUALITY = 80
TIMEOUT = 30


def http_get(url, timeout=TIMEOUT):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def fetch_via_proxy(target_url):
    """Try to fetch a URL through multiple CORS proxies."""
    last_err = None
    for proxy in PROXIES:
        try:
            if 'cors.sh' in proxy:
                url = proxy + target_url
            elif 'allorigins' in proxy:
                url = proxy + urllib.parse.quote(target_url, safe='')
            elif 'corsproxy' in proxy:
                url = proxy + urllib.parse.quote(target_url, safe='')
            else:
                url = proxy + target_url
            data = http_get(url, timeout=30)
            if data and len(data) > 500:
                return data
        except Exception as e:
            last_err = e
            continue
    raise Exception('all proxies failed: %s' % last_err)


def get_latest_chapter_num():
    """Find the latest chapter number on 3asq One Piece page."""
    target = '%s/manga/%s/' % (ASQ_BASE, MANGA_SLUG)
    html = fetch_via_proxy(target).decode('utf-8', errors='ignore')
    # btn-read-first link contains latest chapter number
    m = re.search(r'href="[^"]*?/(\d+(?:\.\d+)?)/?"[^>]*id="btn-read-first"', html, re.I)
    if m:
        return int(m.group(1))
    # Fallback: find all chapter links and take max
    chs = re.findall(r'href="https?://3asq\.pro/manga/%s/(\d+(?:\.\d+)?)/?"' % MANGA_SLUG, html, re.I)
    if chs:
        return max(int(float(c)) for c in chs)
    raise Exception('could not find latest chapter')


def get_chapter_pages(chapter_num):
    """Get list of image URLs for a chapter from 3asq."""
    target = '%s/manga/%s/%d/' % (ASQ_BASE, MANGA_SLUG, chapter_num)
    html = fetch_via_proxy(target).decode('utf-8', errors='ignore')
    # Extract wp-manga-chapter-img URLs
    img_re = re.compile(r'<img[^>]+src="([^"]+)"[^>]+class="[^"]*wp-manga-chapter-img', re.I)
    imgs = img_re.findall(html)
    if not imgs:
        img_re = re.compile(r'class="[^"]*wp-manga-chapter-img[^"]*"[^>]+src="([^"]+)"', re.I)
        imgs = img_re.findall(html)
    # Clean URLs (some have leading whitespace)
    return [u.strip() for u in imgs if u.strip()]


def download_and_convert(image_url, out_path):
    """Download an image and convert to WebP."""
    try:
        data = http_get(image_url, timeout=20)
        im = Image.open(io.BytesIO(data)).convert('RGB')
        im.save(out_path, 'WebP', quality=WEBP_QUALITY, method=6)
        return os.path.getsize(out_path)
    except Exception as e:
        print('    page download failed: %s' % e, file=sys.stderr)
        return 0


def main():
    print('=== Cache One Piece latest chapters ===')
    
    # 1. Find latest chapter
    try:
        latest = get_latest_chapter_num()
        print('Latest chapter on 3asq: %d' % latest)
    except Exception as e:
        print('FATAL: could not find latest chapter: %s' % e, file=sys.stderr)
        # Write empty manifest so app falls back to live sources
        os.makedirs('cache/chapters', exist_ok=True)
        with open('cache/chapters/manifest.json', 'w', encoding='utf-8') as f:
            json.dump({'updated_at': datetime.now(timezone.utc).isoformat(),
                       'manga': 'one-piece', 'latest_chapter': None,
                       'chapters': {}, 'note': 'cache update failed'}, f, ensure_ascii=False, indent=2)
        sys.exit(0)  # Don't fail the workflow
    
    # 2. Determine which chapters to cache (last N)
    chapters_to_cache = list(range(latest, latest - LATEST_CHAPTERS_TO_CACHE, -1))
    print('Caching chapters: %s' % chapters_to_cache)
    
    # 3. Load existing manifest (so we skip already-cached chapters)
    manifest_path = 'cache/chapters/manifest.json'
    existing = {}
    if os.path.exists(manifest_path):
        try:
            with open(manifest_path, 'r', encoding='utf-8') as f:
                existing = json.load(f).get('chapters', {})
        except:
            pass
    
    # 4. Cache each chapter
    chapters_manifest = {}
    total_size = 0
    for num in chapters_to_cache:
        print('\n[Chapter %d]' % num)
        # Skip if already cached (and pages still exist on disk)
        ch_dir = 'cache/chapters/%d' % num
        if str(num) in existing and os.path.isdir(ch_dir):
            existing_pages = sorted(os.listdir(ch_dir))
            if existing_pages and existing_pages[0].endswith('.webp'):
                print('  already cached (%d pages)' % len(existing_pages))
                chapters_manifest[str(num)] = {
                    'pages': ['cache/chapters/%d/%s' % (num, p) for p in existing_pages],
                    'source': '3asq-cdn'
                }
                total_size += sum(os.path.getsize(os.path.join(ch_dir, p)) for p in existing_pages)
                continue
        
        # Fetch pages from 3asq
        try:
            page_urls = get_chapter_pages(num)
            print('  found %d pages on 3asq' % len(page_urls))
        except Exception as e:
            print('  SKIP: %s' % e, file=sys.stderr)
            continue
        
        if not page_urls:
            print('  SKIP: no pages found')
            continue
        
        # Download and convert each page
        os.makedirs(ch_dir, exist_ok=True)
        page_paths = []
        for i, page_url in enumerate(page_urls):
            out_file = '%s/page-%03d.webp' % (ch_dir, i + 1)
            size = download_and_convert(page_url, out_file)
            if size > 0:
                page_paths.append(out_file)
                total_size += size
                print('  page %d/%d: %d KB' % (i + 1, len(page_urls), size // 1024))
            else:
                # Remove broken file
                if os.path.exists(out_file):
                    os.remove(out_file)
        
        if page_paths:
            chapters_manifest[str(num)] = {
                'pages': page_paths,
                'source': '3asq-cdn',
                'cached_at': datetime.now(timezone.utc).isoformat()
            }
    
    # 5. Write manifest
    os.makedirs('cache/chapters', exist_ok=True)
    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump({
            'updated_at': datetime.now(timezone.utc).isoformat(),
            'manga': 'one-piece',
            'latest_chapter': latest,
            'chapters': chapters_manifest,
            'note': 'Latest %d chapters cached. Older chapters via MangaDex/3asq live.' % LATEST_CHAPTERS_TO_CACHE
        }, f, ensure_ascii=False, indent=2)
    
    print('\n[OK] Manifest written: %d chapters, total %d KB (%.1f MB)' % (
        len(chapters_manifest), total_size // 1024, total_size / 1024 / 1024))
    print('[OK] Latest chapter: %d' % latest)


if __name__ == '__main__':
    main()
