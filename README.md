# 📱 مانجا - Native Android App (Kotlin)

تطبيق أندرويد native حقيقي (مو WebView) مكتوب بـ Kotlin + XML.

## ✅ المميزات

- ✅ **Native UI كامل** (RecyclerView, CardView, Material Design)
- ✅ **3 شاشات native**: الرئيسية، التفاصيل، القارئ
- ✅ **Retrofit API client** يتصل بموقعك
- ✅ **Glide** لتحميل الصور
- ✅ **Swipe to refresh**
- ✅ **Pagination** (تحميل تلقائي عند التمرير)
- ✅ **بحث** عن المانجا
- ✅ **قارئ vertical scroll** للفصول

## 🚀 طريقة البناء عبر GitHub Actions

### 1️⃣ اعمل repo جديد على GitHub
- روح على https://github.com/new
- الاسم: `manga-app`
- **Private** أو **Public** (مو مهم)
- ✅ ما تضيف README ولا .gitignore
- اضغط **Create repository**

### 2️⃣ ارفع الملفات
**الطريقة الأسهل (بدون Git):**
1. نزّل GitHub Desktop: https://desktop.github.com
2. افتحه → Add → Add existing repository → اختار مجلد المشروع
3. اكتب commit message → **Commit to main**
4. **Push origin**

**أو من المتصفح:**
1. افتح repo على GitHub
2. **Add file → Upload files**
3. اسحب كل ملفات المشروع
4. **Commit changes**

### 3️⃣ شغل GitHub Actions
1. في repo على GitHub → تبويب **Actions**
2. رح تشوف workflow اسمه **"Build APK"**
3. لو ما اشتغل تلقائياً → اضغط **"I understand my workflows, go ahead and enable them"**
4. اضغط على **"Build APK"** → **"Run workflow"** → **"Run workflow"** (الزر الأخضر)

### 4️⃣ نزّل الـ APK
1. انتظر 5-10 دقايق (البناء)
2. لما يخلص → اضغط على الـ run الأخضر
3. انزل تحت → قسم **Artifacts**
4. اضغط على **manga-app-debug** → بينزل ZIP فيه الـ APK
5. فك الضغط → لقيت `app-debug.apk`

### 5️⃣ ثبّته على موبايلك
1. انقل `app-debug.apk` لموبايلك (USB / Google Drive)
2. اضغط عليه في File Manager
3. فعّل "مصادر غير معروفة" لو طُلب
4. اضغط "تثبيت"
5. افتح التطبيق من قائمة التطبيقات 🎉

## 📁 هيكل المشروع

```
manga-native-app/
├── .github/workflows/
│   └── build.yml              ← GitHub Actions workflow
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/yazan/manga/
│       │   ├── MainActivity.kt           ← شاشة الرئيسية (قائمة المانجا)
│       │   ├── MangaDetailsActivity.kt   ← تفاصيل المانجا + الفصول
│       │   ├── ReaderActivity.kt         ← قارئ الصفحات
│       │   ├── data/
│       │   │   ├── MangaModels.kt        ← data models
│       │   │   ├── MangaApiService.kt    ← Retrofit interface
│       │   │   ├── MangaApiClient.kt     ← Retrofit client
│       │   │   └── MangaRepository.kt    ← repository
│       │   └── ui/
│       │       ├── MangaAdapter.kt       ← adapter للقائمة
│       │       ├── ChapterAdapter.kt     ← adapter للفصول
│       │       └── ReaderAdapter.kt      ← adapter للصفحات
│       └── res/
│           ├── layout/                   ← XML layouts
│           ├── values/                   ← colors, strings, themes
│           ├── drawable/                 ← icons, shapes
│           └── mipmap-*/                 ← app icons
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/
    └── gradle-wrapper.properties
```

## 🔧 التعديلات المستقبلية

### تغيير رابط الموقع
افتح `app/src/main/java/com/yazan/manga/data/MangaApiClient.kt`:
```kotlin
private const val BASE_URL = "https://manga-app-yazan.netlify.app/"
```

### تغيير اسم التطبيق
افتح `app/src/main/res/values/strings.xml`:
```xml
<string name="app_name">مانجا</string>
```

### تغيير الألوان
افتح `app/src/main/res/values/colors.xml`

## ❓ استكشاف الأخطاء

### Build failed على GitHub Actions?
- افتح الـ run → شوف الـ log
- ابعثلي آخر 20 سطر من الـ log

### ما في Artifacts؟
- تأكد إن البناء خلص بنجاح (✅)
- Artifacts تظهر بس لو البناء نجح

### APK ما يثبت؟
- تأكد إنك فعّلت "مصادر غير معروفة"
- على Android 8+ → Settings → Apps → Special access → Install unknown apps

---

صنع بواسطة: yazan
