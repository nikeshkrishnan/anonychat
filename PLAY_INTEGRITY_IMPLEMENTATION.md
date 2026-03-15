# Google Play Integrity API Implementation Guide

## Overview

This document describes the implementation of Google Play Integrity API for the register user endpoint and provides comprehensive guidance for backend validation.

**Key Security Features:**
- ✅ Nonce-based replay attack prevention
- ✅ JWT signature verification with Google's public keys
- ✅ App integrity validation
- ✅ Device integrity validation
- ✅ Timestamp freshness checks
- ✅ Debug mode support for development

## Client-Side Implementation

### 1. Dependencies Added

```kotlin
// app/build.gradle.kts
implementation("com.google.android.play:integrity:1.3.0")
```

### 2. IntegrityManager Utility

Location: [`app/src/main/java/com/example/anonychat/utils/IntegrityManager.kt`](app/src/main/java/com/example/anonychat/utils/IntegrityManager.kt)

Key features:
- Generates integrity tokens using Google Play Integrity API
- Supports debug builds with special handling
- Creates unique request hashes for each registration
- Includes timeout handling (10 seconds)

### 3. Registration Flow

When a user registers:
1. Generate a unique **nonce** (cryptographically secure random string)
2. Request integrity token from Google Play Integrity API with the nonce
3. Send both `integrityToken` and `nonce` to backend
4. Backend validates the token signature, nonce, and all security checks before creating the account

**Why Nonce?**
- Prevents replay attacks (token can only be used once)
- Binds the token to a specific registration attempt
- Backend can verify the nonce matches what was sent

### 4. Debug Mode Support

The implementation includes a flag `allowDebugApps` in [`IntegrityManager.requestIntegrityToken()`](app/src/main/java/com/example/anonychat/utils/IntegrityManager.kt:44):
- When `true`: Debug builds can proceed with special debug tokens
- When `false`: Debug builds skip integrity checks
- Production builds always require valid tokens

## Backend Validation Requirements

### 1. Setup Requirements

#### A. Google Cloud Project Setup

1. **Create/Link Google Cloud Project**
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Create a new project or link existing one
   - Note your **Cloud Project Number** (numeric ID)

2. **Enable Play Integrity API**
   - Navigate to: APIs & Services → Library
   - Search for "Play Integrity API"
   - Click "Enable"

3. **Link to Google Play Console**
   - Go to [Google Play Console](https://play.google.com/console/)
   - Select your app
   - Navigate to: Release → Setup → App Integrity
   - Link your Google Cloud project

4. **Create Service Account**
   - Go to Google Cloud Console → IAM & Admin → Service Accounts
   - Create new service account
   - Grant role: "Service Account Token Creator"
   - Create and download JSON key file
   - **Keep this key secure** - it's used for backend validation

#### B. Update Client Configuration

Update [`IntegrityManager.kt`](app/src/main/java/com/example/anonychat/utils/IntegrityManager.kt:23):
```kotlin
private const val CLOUD_PROJECT_NUMBER = YOUR_PROJECT_NUMBER_HERE
```

### 2. Backend Validation Flow

#### Step 1: Receive Registration Request

The backend receives:
```json
{
  "username": "user123",
  "password": "hashed_password",
  "userId": "device_id:app_set_id",
  "email": "device_id:app_set_id@email.com",
  "googleId": "",
  "integrityToken": "eyJhbGc...", // JWT token from Play Integrity API
  "nonce": "base64_encoded_random_string" // Unique nonce for this request
}
```

#### Step 2: Verify JWT Signature (CRITICAL)

**Before decoding the token, you MUST verify its signature using Google's public keys.**

```javascript
// Node.js Example with JWT verification
const jwt = require('jsonwebtoken');
const jwksClient = require('jwks-rsa');

// Create JWKS client to fetch Google's public keys
const client = jwksClient({
  jwksUri: 'https://www.googleapis.com/oauth2/v3/certs',
  cache: true,
  cacheMaxAge: 86400000, // 24 hours
  rateLimit: true
});

function getKey(header, callback) {
  client.getSigningKey(header.kid, (err, key) => {
    if (err) {
      callback(err);
      return;
    }
    const signingKey = key.getPublicKey();
    callback(null, signingKey);
  });
}

async function verifyIntegrityTokenSignature(integrityToken) {
  return new Promise((resolve, reject) => {
    jwt.verify(integrityToken, getKey, {
      algorithms: ['RS256', 'ES256'],
      // Don't verify audience/issuer yet - we'll check those in payload validation
      ignoreExpiration: false
    }, (err, decoded) => {
      if (err) {
        reject(new Error(`JWT signature verification failed: ${err.message}`));
        return;
      }
      resolve(decoded);
    });
  });
}
```

```python
# Python Example with JWT verification
from jose import jwt, jwk
from jose.utils import base64url_decode
import requests
import json

# Cache for Google's public keys
_google_keys_cache = None
_cache_timestamp = 0

def get_google_public_keys():
    """Fetch and cache Google's public keys"""
    global _google_keys_cache, _cache_timestamp
    
    # Cache for 24 hours
    if _google_keys_cache and (time.time() - _cache_timestamp) < 86400:
        return _google_keys_cache
    
    response = requests.get('https://www.googleapis.com/oauth2/v3/certs')
    _google_keys_cache = response.json()
    _cache_timestamp = time.time()
    return _google_keys_cache

def verify_integrity_token_signature(integrity_token):
    """Verify JWT signature using Google's public keys"""
    try:
        # Get the key ID from token header
        header = jwt.get_unverified_header(integrity_token)
        kid = header.get('kid')
        
        # Fetch Google's public keys
        keys = get_google_public_keys()
        
        # Find the matching key
        key_data = None
        for key in keys.get('keys', []):
            if key.get('kid') == kid:
                key_data = key
                break
        
        if not key_data:
            raise ValueError(f'Public key not found for kid: {kid}')
        
        # Verify signature and decode
        decoded = jwt.decode(
            integrity_token,
            key_data,
            algorithms=['RS256', 'ES256'],
            options={'verify_exp': True}
        )
        
        return decoded
        
    except Exception as e:
        raise ValueError(f'JWT signature verification failed: {str(e)}')
```

#### Step 3: Decode Integrity Token

**After signature verification, decode the token using Google's API:**

```javascript
// Node.js Example
const { google } = require('googleapis');

async function verifyIntegrityToken(integrityToken, requestHash) {
  try {
    // 1. Initialize Google Auth with service account
    const auth = new google.auth.GoogleAuth({
      keyFile: 'path/to/service-account-key.json',
      scopes: ['https://www.googleapis.com/auth/playintegrity']
    });
    
    const client = await auth.getClient();
    const playintegrity = google.playintegrity({ version: 'v1', auth: client });
    
    // 2. Decode the integrity token
    const response = await playintegrity.v1.decodeIntegrityToken({
      packageName: 'com.example.anonychat', // Your app package name
      requestBody: {
        integrityToken: integrityToken
      }
    });
    
    const tokenPayload = response.data.tokenPayloadExternal;
    
    // 3. VALIDATE ALL SECURITY CHECKS (see Step 4)
    return validateTokenPayload(tokenPayload, nonce);
    
  } catch (error) {
    console.error('Integrity token validation failed:', error);
    return { valid: false, reason: 'Token validation error' };
  }
}
```

```python
# Python Example
from google.oauth2 import service_account
from googleapiclient.discovery import build

def verify_integrity_token(integrity_token, request_hash):
    try:
        # 1. Initialize Google Auth
        credentials = service_account.Credentials.from_service_account_file(
            'path/to/service-account-key.json',
            scopes=['https://www.googleapis.com/auth/playintegrity']
        )
        
        service = build('playintegrity', 'v1', credentials=credentials)
        
        # 2. Decode the integrity token
        request_body = {'integrityToken': integrity_token}
        response = service.v1().decodeIntegrityToken(
            packageName='com.example.anonychat',
            body=request_body
        ).execute()
        
        token_payload = response.get('tokenPayloadExternal', {})
        
        # 3. VALIDATE ALL SECURITY CHECKS (see Step 4)
        return validate_token_payload(token_payload, nonce)
        
    except Exception as e:
        print(f'Integrity token validation failed: {e}')
        return {'valid': False, 'reason': 'Token validation error'}
```

#### Step 4: Validate Token Payload

**MAXIMUM SECURITY VALIDATION CHECKLIST:**

```javascript
function validateTokenPayload(tokenPayload, expectedNonce) {
  const validationResults = {
    valid: true,
    checks: {},
    reason: null
  };
  
  // ============================================
  // 1. REQUEST DETAILS VALIDATION (CRITICAL)
  // ============================================
  
  // 1.1 Verify nonce matches (prevents replay attacks)
  const requestDetails = tokenPayload.requestDetails;
  if (!requestDetails) {
    return { valid: false, reason: 'Missing request details' };
  }
  
  const receivedNonce = requestDetails.nonce;
  if (!receivedNonce) {
    return { valid: false, reason: 'Missing nonce in token' };
  }
  
  // Decode nonce from base64 if needed
  const decodedNonce = Buffer.from(receivedNonce, 'base64').toString('utf-8');
  const expectedNonceDecoded = Buffer.from(expectedNonce, 'base64').toString('utf-8');
  
  if (decodedNonce !== expectedNonceDecoded && receivedNonce !== expectedNonce) {
    return { valid: false, reason: 'Nonce mismatch - possible replay attack' };
  }
  validationResults.checks.nonce = 'PASS';
  
  // 1.2 Check if nonce has been used before (replay attack prevention)
  const nonceUsed = await checkNonceUsed(receivedNonce);
  if (nonceUsed) {
    return { valid: false, reason: 'Nonce already used - replay attack detected' };
  }
  validationResults.checks.nonceReuse = 'PASS';
  
  // 1.3 Verify timestamp freshness (additional replay attack prevention)
  const requestTime = parseInt(requestDetails.timestampMillis);
  const currentTime = Date.now();
  const timeDiff = currentTime - requestTime;
  
  // Token should be used within 5 minutes
  if (timeDiff > 5 * 60 * 1000) {
    return { valid: false, reason: 'Token expired (>5 minutes old)' };
  }
  if (timeDiff < -60 * 1000) {
    return { valid: false, reason: 'Token timestamp in future' };
  }
  validationResults.checks.timestamp = 'PASS';
  
  // 1.4 Verify package name
  if (requestDetails.requestPackageName !== 'com.example.anonychat') {
    return { valid: false, reason: 'Invalid package name' };
  }
  validationResults.checks.packageName = 'PASS';
  
  // ============================================
  // 2. APP INTEGRITY VALIDATION (CRITICAL)
  // ============================================
  
  const appIntegrity = tokenPayload.appIntegrity;
  if (!appIntegrity) {
    return { valid: false, reason: 'Missing app integrity data' };
  }
  
  // 2.1 Verify app recognition verdict
  // PLAY_RECOGNIZED: App is genuine and unmodified
  // UNRECOGNIZED_VERSION: App version not recognized (could be sideloaded)
  // UNEVALUATED: Integrity check couldn't be performed
  const appRecognition = appIntegrity.appRecognitionVerdict;
  if (appRecognition !== 'PLAY_RECOGNIZED') {
    // For production, reject non-recognized apps
    // For development, you might allow UNEVALUATED
    return { 
      valid: false, 
      reason: `App not recognized: ${appRecognition}` 
    };
  }
  validationResults.checks.appRecognition = 'PASS';
  
  // 2.2 Verify package name in app integrity
  if (appIntegrity.packageName !== 'com.example.anonychat') {
    return { valid: false, reason: 'Package name mismatch in app integrity' };
  }
  validationResults.checks.appPackageName = 'PASS';
  
  // 2.3 Check certificate SHA-256 digest (optional but recommended)
  // This ensures the app is signed with your certificate
  const expectedCertDigests = [
    'YOUR_RELEASE_CERT_SHA256_HERE', // Release certificate
    'YOUR_DEBUG_CERT_SHA256_HERE'    // Debug certificate (remove in production)
  ];
  
  const certDigests = appIntegrity.certificateSha256Digest || [];
  const validCert = certDigests.some(digest => 
    expectedCertDigests.includes(digest)
  );
  
  if (!validCert && certDigests.length > 0) {
    return { valid: false, reason: 'Invalid certificate signature' };
  }
  validationResults.checks.certificate = validCert ? 'PASS' : 'SKIP';
  
  // 2.4 Check version code (optional)
  // Reject old versions that might have known vulnerabilities
  const versionCode = appIntegrity.versionCode;
  const minimumVersionCode = 1; // Set your minimum allowed version
  if (versionCode && versionCode < minimumVersionCode) {
    return { 
      valid: false, 
      reason: `App version too old: ${versionCode}` 
    };
  }
  validationResults.checks.versionCode = 'PASS';
  
  // ============================================
  // 3. DEVICE INTEGRITY VALIDATION (CRITICAL)
  // ============================================
  
  const deviceIntegrity = tokenPayload.deviceIntegrity;
  if (!deviceIntegrity) {
    return { valid: false, reason: 'Missing device integrity data' };
  }
  
  // 3.1 Check device integrity verdicts
  // MEETS_DEVICE_INTEGRITY: Device is genuine and unmodified
  // MEETS_BASIC_INTEGRITY: Device passes basic integrity checks
  // MEETS_STRONG_INTEGRITY: Device has strong hardware-backed security
  // MEETS_VIRTUAL_INTEGRITY: Running in a virtual environment (emulator)
  
  const deviceVerdicts = deviceIntegrity.deviceRecognitionVerdict || [];
  
  // For maximum security, require at least basic integrity
  const hasBasicIntegrity = deviceVerdicts.includes('MEETS_BASIC_INTEGRITY') ||
                            deviceVerdicts.includes('MEETS_DEVICE_INTEGRITY') ||
                            deviceVerdicts.includes('MEETS_STRONG_INTEGRITY');
  
  if (!hasBasicIntegrity) {
    // Check if it's a virtual device (emulator)
    const isVirtual = deviceVerdicts.includes('MEETS_VIRTUAL_INTEGRITY');
    
    // In production, you might want to reject emulators
    // In development, you might allow them
    if (isVirtual) {
      // DECISION POINT: Allow or reject emulators?
      // For development: allow
      // For production: reject
      validationResults.checks.deviceIntegrity = 'WARN_VIRTUAL';
      // return { valid: false, reason: 'Emulator not allowed' };
    } else {
      return { 
        valid: false, 
        reason: 'Device integrity check failed' 
      };
    }
  } else {
    validationResults.checks.deviceIntegrity = 'PASS';
  }
  
  // ============================================
  // 4. ACCOUNT DETAILS VALIDATION (RECOMMENDED)
  // ============================================
  
  const accountDetails = tokenPayload.accountDetails;
  if (accountDetails) {
    // 4.1 Check app licensing verdict
    // LICENSED: User has a valid license
    // UNLICENSED: User doesn't have a valid license
    // UNEVALUATED: Licensing check couldn't be performed
    const licensingVerdict = accountDetails.appLicensingVerdict;
    if (licensingVerdict === 'UNLICENSED') {
      // DECISION POINT: Reject unlicensed users?
      // For free apps, this might not matter
      validationResults.checks.licensing = 'WARN_UNLICENSED';
    } else {
      validationResults.checks.licensing = licensingVerdict || 'UNEVALUATED';
    }
  }
  
  // ============================================
  // 5. ENVIRONMENT DETAILS (OPTIONAL)
  // ============================================
  
  const environmentDetails = tokenPayload.environmentDetails;
  if (environmentDetails) {
    // 5.1 Check Play Protect verdict
    // NO_ISSUES: Play Protect is enabled and no issues found
    // NO_DATA: Play Protect data not available
    // POSSIBLE_RISK: Potential security risk detected
    const playProtect = environmentDetails.playProtectVerdict;
    if (playProtect === 'POSSIBLE_RISK') {
      validationResults.checks.playProtect = 'WARN_RISK';
      // DECISION POINT: Reject devices with Play Protect risks?
      // return { valid: false, reason: 'Play Protect detected risk' };
    } else {
      validationResults.checks.playProtect = playProtect || 'NO_DATA';
    }
    
    // 5.2 Check app access risk verdict
    // APPS_DETECTED: Risky apps detected on device
    // NO_DATA: No data available
    const appAccessRisk = environmentDetails.appAccessRiskVerdict;
    if (appAccessRisk === 'APPS_DETECTED') {
      validationResults.checks.appAccessRisk = 'WARN_RISKY_APPS';
      // DECISION POINT: Reject devices with risky apps?
    } else {
      validationResults.checks.appAccessRisk = appAccessRisk || 'NO_DATA';
    }
  }
  
  // ============================================
  // 6. ADDITIONAL SECURITY MEASURES
  // ============================================
  
  // 6.1 Rate limiting per device/IP
  // Implement rate limiting to prevent abuse
  // Example: Max 5 registration attempts per device per hour
  
  // 6.2 Store nonce to prevent reuse (CRITICAL)
  // Store the nonce in database/cache with expiration
  // Reject if the same nonce is used again (replay attack prevention)
  await storeUsedNonce(receivedNonce, Date.now() + (5 * 60 * 1000)); // 5 min expiry
  
  // 6.3 Log all validation attempts
  // Log all validation results for security monitoring
  console.log('Integrity validation:', validationResults);
  
  return validationResults;
}
```

#### Step 4: Handle Validation Results

### 4. Nonce Management (CRITICAL)

#### A. Why Nonce Management is Essential

**Nonce (Number used ONCE)** is critical for preventing replay attacks:
- Each nonce can only be used once
- Binds the integrity token to a specific registration attempt
- Prevents attackers from reusing captured tokens
- Must be stored server-side with expiration

#### B. Database Schema

```sql
-- Create table to store used nonces
CREATE TABLE used_nonces (
  nonce VARCHAR(255) PRIMARY KEY,
  used_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  user_id VARCHAR(255),
  INDEX idx_expires_at (expires_at)
);

-- Clean up expired nonces (run periodically)
DELETE FROM used_nonces 
WHERE expires_at < NOW();
```

#### C. Redis Implementation (Recommended)

Redis is ideal for nonce management due to automatic expiration:

```javascript
// Node.js with Redis
const redis = require('redis');
const client = redis.createClient();

async function checkNonceUsed(nonce) {
  const exists = await client.exists(`nonce:${nonce}`);
  return exists === 1;
}

async function storeUsedNonce(nonce, expiryTimestamp) {
  const ttl = Math.floor((expiryTimestamp - Date.now()) / 1000);
  if (ttl > 0) {
    await client.setex(`nonce:${nonce}`, ttl, '1');
  }
}

// Atomic check-and-store (prevents race conditions)
async function checkAndStoreNonce(nonce, expiryTimestamp) {
  const ttl = Math.floor((expiryTimestamp - Date.now()) / 1000);
  if (ttl <= 0) {
    return false; // Nonce expired
  }
  
  // Use SETNX for atomic check-and-set
  const result = await client.setnx(`nonce:${nonce}`, '1');
  if (result === 1) {
    await client.expire(`nonce:${nonce}`, ttl);
    return true; // Nonce stored successfully
  }
  return false; // Nonce already exists (replay attack)
}
```

```python
# Python with Redis
import redis
import time

redis_client = redis.Redis(host='localhost', port=6379, db=0)

def check_nonce_used(nonce):
    """Check if nonce has been used"""
    return redis_client.exists(f'nonce:{nonce}') == 1

def store_used_nonce(nonce, expiry_timestamp):
    """Store nonce with expiration"""
    ttl = int(expiry_timestamp - time.time())
    if ttl > 0:
        redis_client.setex(f'nonce:{nonce}', ttl, '1')

def check_and_store_nonce(nonce, expiry_timestamp):
    """Atomically check and store nonce"""
    ttl = int(expiry_timestamp - time.time())
    if ttl <= 0:
        return False
    
    # Use SETNX for atomic operation
    result = redis_client.setnx(f'nonce:{nonce}', '1')
    if result:
        redis_client.expire(f'nonce:{nonce}', ttl)
        return True
    return False
```

#### D. Nonce Validation Flow

```javascript
// In your registration endpoint
app.post('/auth/register', async (req, res) => {
  const { integrityToken, nonce } = req.body;
  
  // 1. Check if nonce has been used (replay attack check)
  const nonceUsed = await checkNonceUsed(nonce);
  if (nonceUsed) {
    return res.status(403).json({
      error: 'Nonce already used - replay attack detected',
      code: 'NONCE_REUSED'
    });
  }
  
  // 2. Verify integrity token (includes nonce validation)
  const validation = await verifyIntegrityToken(integrityToken, nonce);
  if (!validation.valid) {
    return res.status(403).json({
      error: validation.reason,
      code: 'INTEGRITY_FAILED'
    });
  }
  
  // 3. Store nonce to prevent reuse (5 minute expiry)
  const expiryTime = Date.now() + (5 * 60 * 1000);
  await storeUsedNonce(nonce, expiryTime);
  
  // 4. Proceed with registration
  // ...
});
```

### 5. JWT Signature Verification

#### A. Why Signature Verification is Critical

Before trusting any data in the integrity token, you MUST verify:
1. Token was signed by Google (not forged)
2. Token hasn't been tampered with
3. Token hasn't expired

#### B. Fetching Google's Public Keys

```javascript
// Node.js - Fetch and cache Google's public keys
const jwksClient = require('jwks-rsa');

const client = jwksClient({
  jwksUri: 'https://www.googleapis.com/oauth2/v3/certs',
  cache: true,
  cacheMaxAge: 86400000, // 24 hours
  rateLimit: true,
  jwksRequestsPerMinute: 10
});

function getKey(header, callback) {
  client.getSigningKey(header.kid, (err, key) => {
    if (err) {
      callback(err);
      return;
    }
    const signingKey = key.getPublicKey();
    callback(null, signingKey);
  });
}
```

```python
# Python - Fetch and cache Google's public keys
import requests
import time
from jose import jwt

# Cache for Google's public keys
_google_keys_cache = None
_cache_timestamp = 0

def get_google_public_keys():
    """Fetch and cache Google's public keys"""
    global _google_keys_cache, _cache_timestamp
    
    # Cache for 24 hours
    if _google_keys_cache and (time.time() - _cache_timestamp) < 86400:
        return _google_keys_cache
    
    response = requests.get('https://www.googleapis.com/oauth2/v3/certs')
    response.raise_for_status()
    _google_keys_cache = response.json()
    _cache_timestamp = time.time()
    return _google_keys_cache
```

#### C. Complete Verification Example

```javascript
// Node.js - Complete JWT verification
const jwt = require('jsonwebtoken');

async function verifyIntegrityTokenSignature(integrityToken) {
  return new Promise((resolve, reject) => {
    jwt.verify(integrityToken, getKey, {
      algorithms: ['RS256', 'ES256'],
      ignoreExpiration: false
    }, (err, decoded) => {
      if (err) {
        reject(new Error(`JWT verification failed: ${err.message}`));
        return;
      }
      resolve(decoded);
    });
  });
}

// Usage in registration endpoint
app.post('/auth/register', async (req, res) => {
  const { integrityToken, nonce } = req.body;
  
  // Step 1: Verify JWT signature
  let decodedToken;
  try {
    decodedToken = await verifyIntegrityTokenSignature(integrityToken);
  } catch (error) {
    console.error('JWT signature verification failed:', error);
    return res.status(403).json({
      error: 'Invalid integrity token signature',
      code: 'INVALID_SIGNATURE'
    });
  }
  
  // Step 2: Decode and validate token payload
  const validation = await verifyIntegrityToken(integrityToken, nonce);
  // ... continue with validation
});
```

```python
# Python - Complete JWT verification
from jose import jwt, jwk
from jose.exceptions import JWTError

def verify_integrity_token_signature(integrity_token):
    """Verify JWT signature using Google's public keys"""
    try:
        # Get the key ID from token header
        header = jwt.get_unverified_header(integrity_token)
        kid = header.get('kid')
        
        # Fetch Google's public keys
        keys = get_google_public_keys()
        
        # Find the matching key
        key_data = None
        for key in keys.get('keys', []):
            if key.get('kid') == kid:
                key_data = key
                break
        
        if not key_data:
            raise ValueError(f'Public key not found for kid: {kid}')
        
        # Verify signature and decode
        decoded = jwt.decode(
            integrity_token,
            key_data,
            algorithms=['RS256', 'ES256'],
            options={'verify_exp': True}
        )
        
        return decoded
        
    except JWTError as e:
        raise ValueError(f'JWT verification failed: {str(e)}')

# Usage in registration endpoint
@app.route('/auth/register', methods=['POST'])
def register():
    data = request.get_json()
    integrity_token = data.get('integrityToken')
    nonce = data.get('nonce')
    
    # Step 1: Verify JWT signature
    try:
        decoded_token = verify_integrity_token_signature(integrity_token)
    except ValueError as e:
        return jsonify({
            'error': 'Invalid integrity token signature',
            'code': 'INVALID_SIGNATURE'
        }), 403
    
    # Step 2: Validate token payload
    validation = verify_integrity_token(integrity_token, nonce)
    # ... continue with validation
```


```javascript
// In your registration endpoint
app.post('/auth/register', async (req, res) => {
  const { username, password, userId, email, integrityToken, nonce } = req.body;
  
  // 1. Validate required fields
  if (!integrityToken || !nonce) {
    return res.status(400).json({
      error: 'Integrity token and nonce required',
      code: 'MISSING_INTEGRITY_TOKEN'
    });
  }
  
  // 2. Verify JWT signature first
  let decodedToken;
  try {
    decodedToken = await verifyIntegrityTokenSignature(integrityToken);
  } catch (error) {
    console.error('JWT signature verification failed:', error);
    return res.status(403).json({
      error: 'Invalid integrity token signature',
      code: 'INVALID_SIGNATURE'
    });
  }
  
  // 3. Validate token payload
  const validation = await verifyIntegrityToken(integrityToken, nonce);
  
  if (!validation.valid) {
    // Log the failed attempt
    console.error('Registration blocked:', {
      userId,
      reason: validation.reason,
      checks: validation.checks
    });
    
    return res.status(403).json({ 
      error: 'Security verification failed',
      reason: validation.reason 
    });
  }
  
  // 4. Store nonce to prevent reuse
  await db.storeUsedNonce(nonce, Date.now() + (5 * 60 * 1000));
  
  // 5. Proceed with registration
  // ... create user account ...
  
  res.status(201).json({ message: 'Registration successful' });
});
```

### 3. Debug Mode Handling

The client sends special tokens in debug mode:
- `"DEBUG_BUILD_SKIP_INTEGRITY"` - Debug build with integrity checks disabled
- `"DEBUG_NO_PROJECT_NUMBER"` - Cloud project number not configured
- `"DEBUG_BUILD_ERROR:..."` - Error occurred during token generation

**Backend handling:**

```javascript
function validateTokenPayload(tokenPayload, expectedRequestHash) {
  // Check for debug tokens
  if (typeof tokenPayload === 'string' && tokenPayload.startsWith('DEBUG_')) {
    // DECISION POINT: How to handle debug builds?
    
    // Option 1: Reject all debug builds in production
    if (process.env.NODE_ENV === 'production') {
      return { valid: false, reason: 'Debug builds not allowed in production' };
    }
    
    // Option 2: Allow debug builds in development
    console.warn('Debug build detected:', tokenPayload);
    return { 
      valid: true, 
      checks: { debug: 'ALLOWED' },
      reason: 'Debug build allowed in development'
    };
  }
  
  // Continue with normal validation...
}
```

### 4. Security Best Practices

#### A. Token Storage and Reuse Prevention

```sql
-- Create table to store used tokens
CREATE TABLE used_integrity_tokens (
  token_hash VARCHAR(64) PRIMARY KEY,
  used_at TIMESTAMP NOT NULL,
  user_id VARCHAR(255),
  INDEX idx_used_at (used_at)
);

-- Clean up old tokens (older than 24 hours)
DELETE FROM used_integrity_tokens 
WHERE used_at < NOW() - INTERVAL 24 HOUR;
```

#### B. Rate Limiting

```javascript
// Example rate limiting
const rateLimit = require('express-rate-limit');

const registrationLimiter = rateLimit({
  windowMs: 60 * 60 * 1000, // 1 hour
  max: 5, // 5 requests per hour per IP
  message: 'Too many registration attempts, please try again later',
  standardHeaders: true,
  legacyHeaders: false,
});

app.post('/auth/register', registrationLimiter, async (req, res) => {
  // ... registration logic ...
});
```

#### C. Monitoring and Alerting

```javascript
// Log all integrity validation failures
function logIntegrityFailure(userId, reason, checks) {
  // Send to your monitoring system (e.g., Sentry, DataDog)
  logger.warn('Integrity validation failed', {
    userId,
    reason,
    checks,
    timestamp: new Date().toISOString()
  });
  
  // Alert if too many failures from same IP/device
  // This could indicate an attack
}
```

### 5. Certificate SHA-256 Digest

To get your app's certificate SHA-256 digest:

```bash
# For debug certificate
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# For release certificate
keytool -list -v -keystore /path/to/your/release.keystore -alias your_alias
```

Look for the "SHA256" line in the output.

### 6. Testing

#### A. Test with Debug Build

1. Set `allowDebugApps = true` in [`IntegrityManager.kt`](app/src/main/java/com/example/anonychat/utils/IntegrityManager.kt:44)
2. Backend should handle debug tokens appropriately
3. Test registration flow

#### B. Test with Release Build

1. Build release APK
2. Upload to Google Play Console (Internal Testing track)
3. Install from Play Store
4. Test registration flow
5. Verify backend receives valid integrity tokens

#### C. Test Failure Scenarios

1. **Expired token**: Delay registration after token generation
2. **Token reuse**: Try to register twice with same token
3. **Modified app**: Install modified APK (should fail)
4. **Rooted device**: Test on rooted device (should fail or warn)

## Summary

### What the Client Sends

```json
{
  "integrityToken": "eyJhbGc...",  // JWT from Play Integrity API
  "requestHash": "a1b2c3d4..."     // SHA-256 hash for verification
}
```

### What the Backend MUST Validate

1. ✅ **Request hash matches** (prevents tampering)
2. ✅ **Token timestamp is fresh** (prevents replay attacks)
3. ✅ **Package name is correct** (prevents impersonation)
4. ✅ **App is recognized by Play Store** (prevents modified apps)
5. ✅ **Device passes integrity checks** (prevents rooted/modified devices)
6. ✅ **Token hasn't been used before** (prevents replay attacks)
7. ⚠️ **Certificate signature matches** (optional but recommended)
8. ⚠️ **App version is acceptable** (optional but recommended)
9. ⚠️ **Play Protect is enabled** (optional)
10. ⚠️ **No risky apps detected** (optional)

### Security Levels

- **Minimum Security**: Validate items 1-6
- **Recommended Security**: Validate items 1-8
- **Maximum Security**: Validate all items 1-10

## Additional Resources

- [Play Integrity API Documentation](https://developer.android.com/google/play/integrity)
- [Play Integrity API Best Practices](https://developer.android.com/google/play/integrity/verdicts)
- [Google Cloud Console](https://console.cloud.google.com/)
- [Google Play Console](https://play.google.com/console/)