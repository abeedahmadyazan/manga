/**
 * YZ MANGA API - Server-side protection layer
 * 
 * This API sits between the Android app and Firestore, enforcing:
 * - Firebase Auth token verification (every request must be authenticated)
 * - Per-user rate limiting (prevents spam)
 * - Content validation (length, banned words, repetition)
 * - Admin operations (ban users, resolve reports, delete any comment)
 * 
 * The app sends: Authorization: Bearer <Firebase ID token>
 * The API verifies the token with Firebase Admin SDK, then performs
 * the operation using Admin SDK (bypassing Firestore rules).
 */

const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const admin = require('firebase-admin');

// =============================================================
// Firebase Admin SDK initialization
// =============================================================
const serviceAccount = {
  type: 'service_account',
  project_id: process.env.FIREBASE_PROJECT_ID,
  private_key_id: process.env.FIREBASE_PRIVATE_KEY_ID,
  private_key: process.env.FIREBASE_PRIVATE_KEY ? process.env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, '\n') : '',
  client_email: process.env.FIREBASE_CLIENT_EMAIL,
  client_id: process.env.FIREBASE_CLIENT_ID,
  auth_uri: 'https://accounts.google.com/o/oauth2/auth',
  token_uri: 'https://oauth2.googleapis.com/token',
  auth_provider_x509_cert_url: 'https://www.googleapis.com/oauth2/v1/certs',
  client_x509_cert_url: process.env.FIREBASE_CLIENT_X509_CERT_URL,
};

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: `https://${process.env.FIREBASE_PROJECT_ID}.firebaseio.com`,
});

const db = admin.firestore();
const auth = admin.auth();

// =============================================================
// Express setup
// =============================================================
const app = express();
app.use(cors({ origin: '*' }));
app.use(express.json({ limit: '10kb' })); // reject large payloads

// Trust proxy (Render runs behind a proxy, we need real client IP)
app.set('trust proxy', 1);

// =============================================================
// Global rate limit (per IP)
// =============================================================
const globalLimiter = rateLimit({
  windowMs: 60 * 1000, // 1 minute
  max: 60, // 60 requests per minute per IP
  message: { error: 'تم تجاوز الحد المسموح. حاول لاحقاً.' },
  standardHeaders: true,
  legacyHeaders: false,
});
app.use(globalLimiter);

// =============================================================
// Auth middleware - verifies Firebase ID token
// =============================================================
async function authenticate(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'مطلوب تسجيل الدخول' });
  }
  const idToken = authHeader.split('Bearer ')[1];
  try {
    const decodedToken = await auth.verifyIdToken(idToken);
    req.user = {
      uid: decodedToken.uid,
      email: decodedToken.email,
      email_verified: decodedToken.email_verified,
    };
    next();
  } catch (error) {
    console.error('Auth error:', error.message);
    return res.status(401).json({ error: 'رمز المصادقة غير صالح' });
  }
}

// =============================================================
// Helper: check if user is admin
// =============================================================
async function isAdmin(uid) {
  const doc = await db.collection('admins').doc(uid).get();
  return doc.exists;
}

// =============================================================
// Helper: check if user is banned
// =============================================================
async function isBanned(email) {
  const doc = await db.collection('banned_users').doc(email).get();
  if (!doc.exists) return false;
  const data = doc.data();
  if (data.permanent) return true;
  if (data.until && data.until > Date.now()) return true;
  return false;
}

// =============================================================
// Helper: spam filter
// =============================================================
const BANNED_WORDS = [
  'كلب', 'حمار', 'خنزير', 'ابن', 'قحبة', 'عاهرة', 'شاذ',
  'fuck', 'shit', 'bitch', 'asshole', 'dick', 'pussy',
  'http://', 'https://', 'www.', '.com', 'telegram.me', 't.me/',
  'whatsapp.com', 'bit.ly', 'tinyurl'
];

function validateText(text) {
  if (!text || typeof text !== 'string') return 'النص غير صالح';
  const trimmed = text.trim();
  if (trimmed.length < 1) return 'التعليق فارغ';
  if (trimmed.length > 500) return 'التعليق طويل جداً (الحد 500 حرف)';
  
  const lower = trimmed.toLowerCase();
  for (const word of BANNED_WORDS) {
    if (lower.includes(word)) return 'التعليق يحتوي على كلمات غير مسموح بها';
  }
  
  // Excessive caps check
  const letters = trimmed.replace(/[^a-zA-Z\u0600-\u06FF]/g, '');
  if (letters.length >= 8) {
    const upperCount = (letters.match(/[A-Z]/g) || []).length;
    if (upperCount / letters.length > 0.7) return 'الرجاء عدم استخدام الأحرف الكبيرة بكثرة';
  }
  
  return null; // valid
}

// =============================================================
// Rate limiter for posting comments (per user)
// =============================================================
const commentLimiter = rateLimit({
  windowMs: 60 * 1000, // 1 minute
  max: 1, // 1 comment per minute per user
  keyGenerator: (req) => req.user.uid,
  message: { error: 'مهلاً، انتظر دقيقة قبل التعليق مرة أخرى' },
});

// =============================================================
// API Routes
// =============================================================

// Health check (no auth required)
app.get('/health', (req, res) => {
  res.json({ status: 'ok', timestamp: Date.now() });
});

// =============================================================
// Comments
// =============================================================

// Get comments for a context (manga/chapter)
app.get('/comments/:contextId', authenticate, async (req, res) => {
  try {
    const { contextId } = req.params;
    if (!contextId || contextId.length > 200) {
      return res.status(400).json({ error: 'سياق غير صالح' });
    }
    
    const snapshot = await db.collection('comments')
      .where('contextId', '==', contextId)
      .orderBy('createdAt', 'desc')
      .limit(500)
      .get();
    
    const comments = [];
    snapshot.forEach(doc => {
      comments.push({ id: doc.id, ...doc.data() });
    });
    
    res.json({ comments });
  } catch (error) {
    console.error('Get comments error:', error.message);
    res.status(500).json({ error: 'تعذّر تحميل التعليقات' });
  }
});

// Add a comment
app.post('/comments', authenticate, commentLimiter, async (req, res) => {
  try {
    const { contextId, contextType, text, parentId } = req.body;
    
    // Validate text
    const textError = validateText(text);
    if (textError) return res.status(400).json({ error: textError });
    
    // Validate contextId
    if (!contextId || contextId.length > 200) {
      return res.status(400).json({ error: 'سياق غير صالح' });
    }
    
    // Check if banned
    if (await isBanned(req.user.email)) {
      return res.status(403).json({ error: 'تم حظر حسابك' });
    }
    
    // Check cooldown
    const cooldownDoc = await db.collection('comment_cooldowns').doc(req.user.email).get();
    if (cooldownDoc.exists) {
      const lastComment = cooldownDoc.data().lastCommentAt || 0;
      if (Date.now() - lastComment < 60000) {
        const wait = Math.ceil((60000 - (Date.now() - lastComment)) / 1000);
        return res.status(429).json({ error: `انتظر ${wait} ثانية` });
      }
    }
    
    // Check max comments per chapter (2 per context)
    const userCommentsSnapshot = await db.collection('comments')
      .where('contextId', '==', contextId)
      .where('authorEmail', '==', req.user.email)
      .get();
    
    const isReply = parentId && parentId.length > 0;
    if (!isReply && userCommentsSnapshot.size >= 2) {
      return res.status(400).json({ error: 'وصلت للحد الأقصى (تعليقان لكل فصل)' });
    }
    
    // Get user profile
    const userDoc = await db.collection('users').doc(req.user.email).get();
    const userData = userDoc.exists ? userDoc.data() : {};
    const isAdminUser = await isAdmin(req.user.uid);
    
    const comment = {
      contextId,
      contextType: contextType || 'manga',
      text: text.trim(),
      authorEmail: req.user.email,
      authorName: userData.name || req.user.email.split('@')[0],
      authorAvatar: userData.avatarBase64 || '',
      isAdmin: isAdminUser,
      parentId: parentId || null,
      createdAt: Date.now(),
      likes: [],
      dislikes: [],
      editedAt: null,
    };
    
    const docRef = await db.collection('comments').add(comment);
    
    // Update cooldown
    await db.collection('comment_cooldowns').doc(req.user.email).set({
      lastCommentAt: Date.now(),
    });
    
    res.status(201).json({ id: docRef.id, ...comment });
  } catch (error) {
    console.error('Add comment error:', error.message);
    res.status(500).json({ error: 'تعذّر إضافة التعليق' });
  }
});

// Update comment (edit)
app.put('/comments/:id', authenticate, async (req, res) => {
  try {
    const { id } = req.params;
    const { text } = req.body;
    
    const textError = validateText(text);
    if (textError) return res.status(400).json({ error: textError });
    
    const docRef = db.collection('comments').doc(id);
    const doc = await docRef.get();
    
    if (!doc.exists) {
      return res.status(404).json({ error: 'التعليق غير موجود' });
    }
    
    const data = doc.data();
    if (data.authorEmail !== req.user.email) {
      return res.status(403).json({ error: 'لا يمكنك تعديل تعليق شخص آخر' });
    }
    
    await docRef.update({
      text: text.trim(),
      editedAt: Date.now(),
    });
    
    res.json({ id, text: text.trim(), editedAt: Date.now() });
  } catch (error) {
    console.error('Update comment error:', error.message);
    res.status(500).json({ error: 'تعذّر تعديل التعليق' });
  }
});

// Delete comment
app.delete('/comments/:id', authenticate, async (req, res) => {
  try {
    const { id } = req.params;
    const docRef = db.collection('comments').doc(id);
    const doc = await docRef.get();
    
    if (!doc.exists) {
      return res.status(404).json({ error: 'التعليق غير موجود' });
    }
    
    const data = doc.data();
    const isAdminUser = await isAdmin(req.user.uid);
    
    if (data.authorEmail !== req.user.email && !isAdminUser) {
      return res.status(403).json({ error: 'لا يمكنك حذف تعليق شخص آخر' });
    }
    
    await docRef.delete();
    
    // Also delete replies
    const repliesSnapshot = await db.collection('comments')
      .where('parentId', '==', id)
      .get();
    const batch = db.batch();
    repliesSnapshot.forEach(doc => batch.delete(doc.ref));
    await batch.commit();
    
    res.json({ success: true });
  } catch (error) {
    console.error('Delete comment error:', error.message);
    res.status(500).json({ error: 'تعذّر حذف التعليق' });
  }
});

// Like/Dislike
app.post('/comments/:id/react', authenticate, async (req, res) => {
  try {
    const { id } = req.params;
    const { type } = req.body; // 'like' or 'dislike'
    
    if (type !== 'like' && type !== 'dislike') {
      return res.status(400).json({ error: 'نوع غير صالح' });
    }
    
    const docRef = db.collection('comments').doc(id);
    const doc = await docRef.get();
    
    if (!doc.exists) {
      return res.status(404).json({ error: 'التعليق غير موجود' });
    }
    
    const data = doc.data();
    const likes = data.likes || [];
    const dislikes = data.dislikes || [];
    
    if (type === 'like') {
      if (likes.includes(req.user.email)) {
        // Unlike
        await docRef.update({ likes: admin.firestore.FieldValue.arrayRemove(req.user.email) });
      } else {
        // Like (and remove from dislikes)
        await docRef.update({
          likes: admin.firestore.FieldValue.arrayUnion(req.user.email),
          dislikes: admin.firestore.FieldValue.arrayRemove(req.user.email),
        });
      }
    } else {
      if (dislikes.includes(req.user.email)) {
        // Undislike
        await docRef.update({ dislikes: admin.firestore.FieldValue.arrayRemove(req.user.email) });
      } else {
        // Dislike (and remove from likes)
        await docRef.update({
          dislikes: admin.firestore.FieldValue.arrayUnion(req.user.email),
          likes: admin.firestore.FieldValue.arrayRemove(req.user.email),
        });
      }
    }
    
    // Return updated counts
    const updated = await docRef.get();
    const updatedData = updated.data();
    res.json({
      likes: updatedData.likes || [],
      dislikes: updatedData.dislikes || [],
    });
  } catch (error) {
    console.error('React error:', error.message);
    res.status(500).json({ error: 'تعذّر التفاعل' });
  }
});

// =============================================================
// Reports
// =============================================================

// Submit a report
app.post('/reports', authenticate, async (req, res) => {
  try {
    const { commentId, commentText, reason, reportedEmail, reportedName } = req.body;
    
    if (!reason || reason.length > 200) {
      return res.status(400).json({ error: 'سبب غير صالح' });
    }
    
    if (!commentId) {
      return res.status(400).json({ error: 'معرّف التعليق مطلوب' });
    }
    
    // Check if already reported by this user
    const existing = await db.collection('reports')
      .where('commentId', '==', commentId)
      .where('reportedByEmail', '==', req.user.email)
      .get();
    
    if (!existing.empty) {
      return res.status(400).json({ error: 'لقد بلّغت عن هذا التعليق مسبقاً' });
    }
    
    // Get user name
    const userDoc = await db.collection('users').doc(req.user.email).get();
    const userData = userDoc.exists ? userDoc.data() : {};
    
    const report = {
      commentId,
      commentText: commentText || '',
      reason: reason.trim(),
      reportedEmail: reportedEmail || '',
      reportedName: reportedName || '',
      reportedByEmail: req.user.email,
      reportedByName: userData.name || req.user.email.split('@')[0],
      createdAt: Date.now(),
      resolved: false,
      resolvedBy: null,
      resolvedAt: null,
    };
    
    const docRef = await db.collection('reports').add(report);
    res.status(201).json({ id: docRef.id, ...report });
  } catch (error) {
    console.error('Report error:', error.message);
    res.status(500).json({ error: 'تعذّر إرسال البلاغ' });
  }
});

// =============================================================
// Admin endpoints
// =============================================================

// Get all reports (admin only)
app.get('/admin/reports', authenticate, async (req, res) => {
  try {
    if (!await isAdmin(req.user.uid)) {
      return res.status(403).json({ error: 'غير مصرح' });
    }
    
    const snapshot = await db.collection('reports')
      .where('resolved', '==', false)
      .orderBy('createdAt', 'desc')
      .limit(100)
      .get();
    
    const reports = [];
    snapshot.forEach(doc => reports.push({ id: doc.id, ...doc.data() }));
    
    res.json({ reports });
  } catch (error) {
    console.error('Admin reports error:', error.message);
    res.status(500).json({ error: 'تعذّر تحميل البلاغات' });
  }
});

// Resolve report (admin only)
app.put('/admin/reports/:id/resolve', authenticate, async (req, res) => {
  try {
    if (!await isAdmin(req.user.uid)) {
      return res.status(403).json({ error: 'غير مصرح' });
    }
    
    const { id } = req.params;
    await db.collection('reports').doc(id).update({
      resolved: true,
      resolvedBy: req.user.email,
      resolvedAt: Date.now(),
    });
    
    res.json({ success: true });
  } catch (error) {
    console.error('Resolve report error:', error.message);
    res.status(500).json({ error: 'تعذّر حل البلاغ' });
  }
});

// Ban user (admin only)
app.post('/admin/ban', authenticate, async (req, res) => {
  try {
    if (!await isAdmin(req.user.uid)) {
      return res.status(403).json({ error: 'غير مصرح' });
    }
    
    const { email, reason, duration } = req.body;
    if (!email) return res.status(400).json({ error: 'الإيميل مطلوب' });
    
    const banData = {
      email,
      reason: reason || 'مخالفة',
      bannedAt: Date.now(),
      bannedBy: req.user.email,
      permanent: duration === 0,
      until: duration > 0 ? Date.now() + duration : null,
    };
    
    await db.collection('banned_users').doc(email).set(banData);
    res.json({ success: true, ...banData });
  } catch (error) {
    console.error('Ban error:', error.message);
    res.status(500).json({ error: 'تعذّر حظر المستخدم' });
  }
});

// Unban user (admin only)
app.delete('/admin/ban/:email', authenticate, async (req, res) => {
  try {
    if (!await isAdmin(req.user.uid)) {
      return res.status(403).json({ error: 'غير مصرح' });
    }
    
    await db.collection('banned_users').doc(req.params.email).delete();
    res.json({ success: true });
  } catch (error) {
    console.error('Unban error:', error.message);
    res.status(500).json({ error: 'تعذّر رفع الحظر' });
  }
});

// =============================================================
// Start server
// =============================================================
const PORT = process.env.PORT || 10000;
app.listen(PORT, '0.0.0.0', () => {
  console.log(`YZ MANGA API running on port ${PORT}`);
});
