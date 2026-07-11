const admin = require('firebase-admin');

// Singleton pattern — avoid re-initializing on every cold start
if (!admin.apps.length) {
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
  });
}

const db = admin.firestore();
const auth = admin.auth();
const appCheck = admin.appCheck();

/**
 * Verify the Firebase App Check token sent by the app.
 *
 * This is the SERVER-SIDE enforcement of App Check. Even if an attacker
 * decompiles the APK and removes the client-side AntiDebug checks, they
 * CANNOT forge a valid App Check token — it's issued by Google Play
 * Integrity after verifying the app's signature and device integrity.
 *
 * @param {object} req - Request object (reads X-Firebase-AppCheck header)
 * @returns {Promise<{valid: boolean, compromised: boolean}>}
 */
async function verifyAppCheck(req) {
  const appCheckToken = req.headers['x-firebase-appcheck'];
  if (!appCheckToken) {
    // No App Check token — treat as potentially compromised.
    // In "Log" mode: allow but flag. In "Enforce" mode: reject.
    return { valid: false, compromised: true, reason: 'no_app_check_token' };
  }
  try {
    await appCheck.verifyToken(appCheckToken);
    return { valid: true, compromised: false };
  } catch (e) {
    return { valid: false, compromised: true, reason: 'invalid_token' };
  }
}

module.exports = { admin, db, auth, appCheck, verifyAppCheck };
