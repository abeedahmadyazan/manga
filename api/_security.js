/**
 * Security layer for all API endpoints.
 *
 * This module enforces that requests come ONLY from the official Android app,
 * not from browsers, curl, or third-party websites. It checks:
 *
 * 1. User-Agent starts with "YZ-Manga/" (the app's UA prefix).
 * 2. No Origin/Referer header (browsers always send one; the app doesn't).
 * 3. Required headers present: x-app-version, x-device-status.
 *
 * If the device is marked "compromised" (debugger/root/Frida detected),
 * we return a fake success response (shadow ban) so the attacker doesn't
 * know they're blocked.
 */

const APP_USER_AGENT_PREFIX = 'YZ-Manga/';
const REQUIRED_HEADERS = ['x-app-version', 'x-device-status'];

/**
 * Run security checks on an incoming request.
 *
 * @param {object} req - Vercel request object
 * @param {object} res - Vercel response object
 * @returns {boolean} true if the request was REJECTED (caller should return),
 *                    false if the request passed (caller should continue).
 */
function securityCheck(req, res) {
  const headers = req.headers || {};

  // ---- 1) Block browser requests (Origin/Referer present) ----
  // The Android app never sends Origin or Referer; browsers always do.
  if (headers.origin || headers.referer) {
    reject(res, 403, 'Forbidden');
    return true;
  }

  // ---- 2) Check User-Agent ----
  const userAgent = headers['user-agent'] || '';
  if (!userAgent.startsWith(APP_USER_AGENT_PREFIX)) {
    reject(res, 403, 'Unauthorized client');
    return true;
  }

  // ---- 3) Required headers ----
  for (const h of REQUIRED_HEADERS) {
    if (!headers[h]) {
      reject(res, 403, 'Missing required headers');
      return true;
    }
  }

  // ---- 4) Shadow ban compromised devices ----
  // Return a fake success so the attacker thinks it worked.
  const deviceStatus = headers['x-device-status'];
  if (deviceStatus === 'compromised' || deviceStatus === 'debug' || deviceStatus === 'rooted') {
    res.status(200).json({ success: true, shadow: true });
    return true;
  }

  return false;
}

function reject(res, status, message) {
  res.status(status).json({ error: message });
}

module.exports = { securityCheck };
