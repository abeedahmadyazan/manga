const { db, auth } = require('../_lib');
const { securityCheck } = require('../_security');

/**
 * Server-side device linking.
 *
 * This endpoint is called AFTER successful Firebase Auth (Google Sign-In).
 * It enforces ONE account per device:
 *
 * 1. Verifies the Firebase ID token (server-side, can't be spoofed).
 * 2. Extracts the email from the token (NOT from the request body).
 * 3. Checks device_links/{deviceId} in Firestore:
 *    - If doesn't exist → CREATE link (first account on this device).
 *    - If exists and email matches → ALLOW (same user logging in again).
 *    - If exists and email differs → REJECT (multi-account attempt).
 *
 * The deviceId comes from the X-Device-Id header. It's a SHA-256 hash
 * of ANDROID_ID + Build info, so it survives app data clears (deterministic).
 *
 * SECURITY: This is the ONLY place where multi-account prevention lives.
 * The app does NOT check this client-side — the server is the source of truth.
 * Even if an attacker decompiles the APK and removes all client checks,
 * they CANNOT bypass this server-side enforcement.
 *
 * Additionally, ALL API calls (via authenticate() in _auth.js) check the
 * device link too — so even if this endpoint is bypassed, subsequent calls
 * will be rejected.
 */

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', 'null');
  res.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-App-Version,X-Device-Status,X-Device-Id,User-Agent');
  if (req.method === 'OPTIONS') return res.status(200).end();

  // Security gate: reject browser/curl traffic
  if (securityCheck(req, res)) return;

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  // 1) Verify Firebase token (server-side)
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'مطلوب تسجيل الدخول' });
  }
  const idToken = authHeader.split('Bearer ')[1];

  let decoded;
  try {
    decoded = await auth.verifyIdToken(idToken);
  } catch (e) {
    return res.status(401).json({ error: 'رمز غير صالح' });
  }

  const uid = decoded.uid;
  let email = decoded.email || '';

  // If no email in token, try Admin SDK lookup
  if (!email && uid) {
    try {
      const userRecord = await auth.getUser(uid);
      email = userRecord.email || '';
    } catch (e) {}
  }

  if (!email || email.length === 0) {
    return res.status(400).json({ error: 'يجب تسجيل الدخول بحساب Google' });
  }

  // 2) Get deviceId from header (NOT from body — header is harder to spoof)
  const deviceId = req.headers['x-device-id'];
  if (!deviceId || !deviceId.startsWith('dev_') || deviceId.length > 100) {
    return res.status(400).json({ error: 'معرّف الجهاز غير صالح' });
  }

  // 3) Check device_links collection (server-side, can't be bypassed)
  try {
    const linkRef = db.collection('device_links').doc(deviceId);
    const linkDoc = await linkRef.get();

    if (linkDoc.exists) {
      const linkedEmail = linkDoc.data().email || '';

      if (linkedEmail && linkedEmail !== email) {
        // 🔴 MULTI-ACCOUNT ATTEMPT — device already linked to a different email
        console.warn(`[device_link] REJECTED: device=${deviceId} tried email=${email} but linked to=${linkedEmail}`);

        return res.status(403).json({
          error: `هذا الجهاز مرتبط بحساب آخر (${linkedEmail}). لا يمكن إنشاء حساب جديد.`,
          linkedEmail: linkedEmail,
          code: 'DEVICE_LINKED_TO_ANOTHER_ACCOUNT'
        });
      }
      // ✅ Same email — allow (re-login)
    } else {
      // First login on this device → CREATE link
      // Firestore rules enforce: once created, can't be changed or deleted
      const now = Date.now();
      await linkRef.set({
        deviceId: deviceId,
        email: email,
        uid: uid,
        linkedAt: now,
        // TTL: auto-cleanup via cleanup endpoint after 1 year of inactivity
        expiresAt: now + 365 * 24 * 60 * 60 * 1000
      });
      console.log(`[device_link] CREATED: device=${deviceId} → email=${email}`);
    }

    return res.status(200).json({
      success: true,
      email: email,
      deviceId: deviceId
    });
  } catch (e) {
    console.error('[device_link] Error:', e.message);
    return res.status(500).json({ error: 'تعذّر التحقق من الجهاز' });
  }
};
