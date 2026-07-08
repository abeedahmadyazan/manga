# 📱 مانجا - Native Android App (Kotlin + XML)

تطبيق أندرويد native حقيقي (مو WebView) مكتوب بـ Kotlin + XML.
يتصل مباشرة بموقع **3asq.pro** من الهاتف بدون وسيط.

## ✅ المميزات

- ✅ **Native UI كامل** (RecyclerView, MaterialCardView, NavigationView, DrawerLayout)
- ✅ **اتصال مباشر بـ 3asq.pro** (OkHttp + regex — بدون Jsoup)
- ✅ **6 شاشات native**: الرئيسية، التفاصيل، القارئ، التعليقات، الملف الشخصي، الإعدادات
- ✅ **DrawerLayout + hamburger menu** مع رأس قابل للنقر
- ✅ **3 تبويبات**: الأحدث / الأكثر شهرة / البحث
- ✅ **Glide** لتحميل الصور
- ✅ **Swipe to refresh**
- ✅ **Pagination** (تحميل تلقائي عند التمرير)
- ✅ **بحث** عن المانجا من 3asq
- ✅ **قارئ vertical scroll** للفصول مع تكبير/تصغير
- ✅ **نظام تعليقات كامل** (إعجاب/عدم إعجاب/رد/تعديل/حظر)
- ✅ **حسابات Google Sign-In** (Firebase Auth)
- ✅ **نظام حظر و إيقاف حسابات** (للمشرف)
- ✅ **ملف مستخدم قابل للنقر** من أي تعليق
- ✅ **Dark theme** (#0a0a0f + emerald #10b981)

## 🏗️ البنية

- **المصدر**: 3asq.pro فقط (Arabic)
- **HTTP**: OkHttp 4.12 (مع User-Agent للمتصفح)
- **HTML parsing**: regex (لا Jsoup)
- **JSON**: Gson
- **الصور**: Glide 4.16
- **Auth**: Firebase Auth + Google Sign-In
- **State**: SharedPreferences (CommentsManager, AuthManager, ReportsManager)
- **الخيوط**: coroutines + Dispatchers.IO

## 📁 هيكل المشروع

```
manga-native-app/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/yazan/manga/
│   │   ├── MainActivity.kt              ← DrawerLayout + 3 tabs
│   │   ├── MangaDetailsActivity.kt      ← تفاصيل + فصول
│   │   ├── ReaderActivity.kt            ← قارئ الصفحات
│   │   ├── CommentsActivity.kt          ← تعليقات
│   │   ├── ProfileActivity.kt           ← ملفي الشخصي
│   │   ├── UserProfileActivity.kt       ← ملف مستخدم آخر
│   │   ├── SettingsActivity.kt          ← الإعدادات
│   │   ├── AdminPanelActivity.kt        ← لوحة المشرف
│   │   ├── data/
│   │   │   ├── AsqClient.kt             ← مباشرةً 3asq.pro scraper
│   │   │   ├── MangaRepository.kt       ← repository (3asq only)
│   │   │   ├── MangaModels.kt           ← data models + MangaSource
│   │   │   ├── AuthManager.kt           ← Google Auth + bans
│   │   │   ├── CommentsManager.kt       ← comments + cooldown
│   │   │   └── ReportsManager.kt        ← reports
│   │   └── ui/
│   │       ├── MangaAdapter.kt          ← grid adapter
│   │       ├── ChapterAdapter.kt        ← chapters adapter
│   │       ├── ReaderAdapter.kt         ← reader pages adapter
│   │       └── CommentsAdapter.kt       ← full comments adapter
│   └── res/
│       ├── layout/                      ← 12 XML layouts
│       ├── values/                      ← colors, themes, strings, styles
│       ├── drawable/                    ← 30+ icons + shapes
│       ├── menu/drawer_menu.xml
│       └── xml/network_security_config.xml
└── ...
```

## 🚀 طريقة البناء

افتح المشروع في Android Studio → Build APK → ثبت على الجوال.

أو استخدم GitHub Actions (workflow في `.github/workflows/build.yml`).

## 🔒 القيود

- **مصدر واحد**: 3asq.pro (عربي فقط)
- **60 ثانية** بين كل تعليقين (cooldown)
- **2 تعليقات كحد أقصى** لكل فصل
- **30 يوم** بين تغيير اسم المستخدم
- **جهاز واحد = حساب واحد** (anti-multi-account)

---
صنع بواسطة: yazan · مصدر البيانات: 3asq.pro
