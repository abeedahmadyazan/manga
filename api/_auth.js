const { auth, db } = require('./_lib');
const { securityCheck } = require('./_security');

const MIN_APP_VERSION = 13;

async function authenticate(req) {
  // ---- Security gate: reject browser/curl traffic ----
  // The Android app never sends Origin/Referer, and uses a custom User-Agent.
  const headers = req.headers || {};
  if (headers.origin || headers.referer) {
    return { error: { status: 403, message: 'Forbidden' } };
  }
  const userAgent = headers['user-agent'] || '';
  if (!userAgent.startsWith('YZ-Manga/')) {
    return { error: { status: 403, message: 'Unauthorized client' } };
  }

  const clientVersion = parseInt(req.headers['x-app-version'] || '0', 10);
  if (clientVersion < MIN_APP_VERSION) {
    return {
      error: {
        status: 426,
        message: 'يرجى تحديث التطبيق إلى أحدث إصدار',
        updateUrl: 'https://yzmanga.netlify.app'
      }
    };
  }

  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return { error: { status: 401, message: 'مطلوب تسجيل الدخول' } };
  }
  const idToken = authHeader.split('Bearer ')[1];
  try {
    const decoded = await auth.verifyIdToken(idToken);
    const uid = decoded.uid;
    let email = decoded.email || '';
    
    // SECURITY FIX: NEVER trust X-User-Email header — it can be spoofed.
    // Email must come from the verified Firebase ID token only.
    // If the token doesn't have an email, try Admin SDK (server-side lookup).
    if (!email && uid) {
      try {
        const userRecord = await auth.getUser(uid);
        email = userRecord.email || '';
      } catch (e) {}
    }
    
    // If still no email, check user_uids index (server-side Firestore lookup)
    if (!email && uid) {
      try {
        const indexDoc = await db.collection('user_uids').doc(uid).get();
        if (indexDoc.exists) {
          email = indexDoc.data().email || '';
        }
      } catch (e) {}
    }
    
    // SECURITY: check if device is compromised (Frida/debugger/emulator)
    const deviceStatus = req.headers['x-device-status'] || '';
    if (deviceStatus === 'compromised') {
      // Shadow ban: allow read but block write operations
      return { user: { uid, email, compromised: true } };
    }
    
    return { user: { uid, email } };
  } catch (e) {
    return { error: { status: 401, message: 'رمز غير صالح' } };
  }
}

async function isAdmin(uid) {
  if (!uid || uid.length === 0) return false;
  try {
    const doc = await db.collection('admins').doc(uid).get();
    return doc.exists;
  } catch (e) { return false; }
}

async function isBanned(email) {
  if (!email || email.length === 0) return false;
  try {
    const doc = await db.collection('banned_users').doc(email).get();
    if (!doc.exists) return false;
    const data = doc.data();
    if (data.permanent) return true;
    if (data.until && data.until > Date.now()) return true;
    return false;
  } catch (e) { return false; }
}

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
  if (trimmed.length > 500) return 'التعليق طويل جداً';
  const lower = trimmed.toLowerCase();
  for (const w of BANNED_WORDS) {
    if (lower.includes(w)) return 'كلمات غير مسموح بها';
  }
  const letters = trimmed.replace(/[^a-zA-Z\u0600-\u06FF]/g, '');
  if (letters.length >= 8) {
    const upperCount = (letters.match(/[A-Z]/g) || []).length;
    if (upperCount / letters.length > 0.7) return 'أحرف كبيرة بكثرة';
  }
  return null;
}

module.exports = { authenticate, isAdmin, isBanned, validateText, MIN_APP_VERSION, securityCheck };
