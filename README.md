# YZ MANGA

تطبيق أندرويد لقراءة المانجا بالعربية.

## البنية التحتية

| المنصة | الدور |
|--------|------|
| **Cloudflare Workers** | API (profile, comments, auth, device linking) + 3asq proxy |
| **Firebase** | Auth + Firestore + Storage + App Check |
| **GitHub** | الكود + CI/CD |
| **Netlify** | صفحة تحميل APK |

## المصادر

1. MangaDex (عربي)
2. 3asq.online (عربي) — عبر Cloudflare Workers proxy
3. manhwahentai.net (إسباني)

## المزايا الأمنية

- App Check Enforce على Firestore
- Server-side device linking (one account per device)
- Shadow ban للأجهزة المخترقة
- Server-side JWT verification
- Firestore rules (login_attempts لا يصفر، login_blocks لا يحذف)
- TTL cleanup cron يومي

## الإصدار الحالي

v1.0.41 (versionCode 97)
