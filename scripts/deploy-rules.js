// Deploy Firestore rules via the Firebase Rules REST API using a service account.
// Usage: node deploy-rules.js <service-account.json> <rules-file>
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');

const saPath = process.argv[2];
const rulesPath = process.argv[3];
if (!saPath || !rulesPath) {
  console.error('Usage: node deploy-rules.js <service-account.json> <rules-file>');
  process.exit(1);
}

const sa = JSON.parse(fs.readFileSync(saPath, 'utf8'));
const rulesSource = fs.readFileSync(rulesPath, 'utf8');
const project = sa.project_id;

function base64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt() {
  const header = { alg: 'RS256', typ: 'JWT', kid: sa.private_key_id };
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase https://www.googleapis.com/auth/cloud-platform',
    aud: sa.token_uri,
    iat: now,
    exp: now + 3600,
  };
  const enc = base64url(JSON.stringify(header)) + '.' + base64url(JSON.stringify(payload));
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(enc);
  signer.end();
  const sig = signer.sign(sa.private_key);
  return enc + '.' + base64url(sig);
}

function httpsRequest(method, host, path, headers, body) {
  return new Promise((resolve, reject) => {
    const opts = { method, host, path, headers: headers || {} };
    const req = https.request(opts, (res) => {
      let data = '';
      res.on('data', (c) => data += c);
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

(async () => {
  // 1. Get OAuth2 access token
  const jwt = makeJwt();
  const tokenBody = `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${encodeURIComponent(jwt)}`;
  const tokenRes = await httpsRequest('POST', 'oauth2.googleapis.com', '/token',
    { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(tokenBody) },
    tokenBody);
  if (tokenRes.status !== 200) {
    console.error('Token exchange failed:', tokenRes.status, tokenRes.body);
    process.exit(1);
  }
  const accessToken = JSON.parse(tokenRes.body).access_token;
  console.log('✅ OAuth2 access token obtained');

  // 2. Create a ruleset
  const rulesetBody = JSON.stringify({
    source: { files: [{ name: 'firestore.rules', content: rulesSource }] },
  });
  const rsRes = await httpsRequest('POST', 'firebaserules.googleapis.com',
    `/v1/projects/${project}/rulesets`,
    { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(rulesetBody) },
    rulesetBody);
  if (rsRes.status !== 200) {
    console.error('Create ruleset failed:', rsRes.status, rsRes.body);
    process.exit(1);
  }
  const rulesetName = JSON.parse(rsRes.body).name;
  console.log('✅ Ruleset created:', rulesetName);

  // 3. Release the ruleset for cloud.firestore via projects.releases.patch
  //    Body = UpdateReleaseRequest { release: Release{name, rulesetName}, updateMask }
  const fullReleaseName = `projects/${project}/releases/cloud.firestore`;
  const releaseBody = JSON.stringify({
    release: { name: fullReleaseName, rulesetName },
    updateMask: 'rulesetName'
  });
  let relRes = await httpsRequest('PATCH', 'firebaserules.googleapis.com',
    `/v1/${fullReleaseName}`,
    { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(releaseBody) },
    releaseBody);
  if (relRes.status !== 200) {
    // Fallback: CREATE (POST) if the release doesn't exist yet
    const cb = JSON.stringify({ name: fullReleaseName, rulesetName });
    relRes = await httpsRequest('POST', 'firebaserules.googleapis.com',
      `/v1/projects/${project}/releases`,
      { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(cb) },
      cb);
  }
  if (relRes.status !== 200) {
    console.error('Release failed:', relRes.status, relRes.body);
    process.exit(1);
  }
  console.log('✅ Released ruleset for cloud.firestore');
  console.log(JSON.parse(relRes.body).name);
})().catch((e) => { console.error('ERROR:', e); process.exit(1); });
