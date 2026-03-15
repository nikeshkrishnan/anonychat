# Google Cloud Console Setup Guide for Play Integrity API

This guide walks you through setting up Google Cloud Console and configuring your Android app for Play Integrity API.

## Prerequisites

- Google account
- Android app package name: `com.example.anonychat`
- Access to Google Play Console (for linking)

---

## Part 1: Google Cloud Console Setup

### Step 1: Create/Select Google Cloud Project

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Sign in with your Google account
3. Click on the project dropdown at the top
4. Click **"New Project"**
   - **Project Name**: `AnonyChat` (or your preferred name)
   - **Organization**: Leave as default or select your organization
   - Click **"Create"**
5. Wait for project creation (takes a few seconds)
6. **IMPORTANT**: Note your **Project Number** (numeric ID shown on dashboard)
   - Example: `123456789012`
   - You'll need this for the app

### Step 2: Enable Play Integrity API

1. In Google Cloud Console, go to **"APIs & Services"** → **"Library"**
2. Search for **"Play Integrity API"**
3. Click on **"Play Integrity API"**
4. Click **"Enable"**
5. Wait for API to be enabled (takes a few seconds)

### Step 3: Create Service Account (For Backend)

1. Go to **"IAM & Admin"** → **"Service Accounts"**
2. Click **"Create Service Account"**
3. Fill in details:
   - **Service account name**: `play-integrity-verifier`
   - **Service account ID**: `play-integrity-verifier` (auto-filled)
   - **Description**: `Service account for verifying Play Integrity tokens`
4. Click **"Create and Continue"**
5. Grant role:
   - Click **"Select a role"**
   - Search for **"Service Account Token Creator"**
   - Select it
   - Click **"Continue"**
6. Click **"Done"**

### Step 4: Create Service Account Key (For Backend)

1. In the Service Accounts list, find `play-integrity-verifier`
2. Click on the service account email
3. Go to **"Keys"** tab
4. Click **"Add Key"** → **"Create new key"**
5. Select **"JSON"** format
6. Click **"Create"**
7. **IMPORTANT**: A JSON file will download automatically
   - **Keep this file secure!** Never commit to Git
   - Rename it to something like `service-account-key.json`
   - Store it securely on your backend server
8. This JSON file contains:
   ```json
   {
     "type": "service_account",
     "project_id": "your-project-id",
     "private_key_id": "...",
     "private_key": "-----BEGIN PRIVATE KEY-----\n...",
     "client_email": "play-integrity-verifier@your-project.iam.gserviceaccount.com",
     "client_id": "...",
     "auth_uri": "https://accounts.google.com/o/oauth2/auth",
     "token_uri": "https://oauth2.googleapis.com/token",
     ...
   }
   ```

---

## Part 2: Google Play Console Setup

### Step 5: Link Google Cloud Project to Play Console

1. Go to [Google Play Console](https://play.google.com/console/)
2. Select your app (or create one if you haven't)
3. Navigate to: **"Release"** → **"Setup"** → **"App Integrity"**
4. Under **"Play Integrity API"**, click **"Link a Cloud project"**
5. Select your Google Cloud project from the dropdown
6. Click **"Link"**
7. Wait for linking to complete

### Step 6: Upload Your App (If Not Already Done)

**For Play Integrity to work, your app MUST be uploaded to Play Console:**

1. Build a signed release APK/AAB:
   ```bash
   ./gradlew bundleRelease
   # or
   ./gradlew assembleRelease
   ```

2. In Play Console, go to **"Release"** → **"Testing"** → **"Internal testing"**
3. Click **"Create new release"**
4. Upload your APK/AAB
5. Fill in release notes
6. Click **"Review release"** → **"Start rollout to Internal testing"**

**Note**: It can take 24-48 hours for Google to recognize your app after first upload.

---

## Part 3: Update Your Android App

### Step 7: Add Project Number to App

1. Open [`app/src/main/java/com/example/anonychat/utils/IntegrityManager.kt`](app/src/main/java/com/example/anonychat/utils/IntegrityManager.kt)

2. Find line 23:
   ```kotlin
   private const val CLOUD_PROJECT_NUMBER = 0L
   ```

3. Replace `0L` with your actual project number:
   ```kotlin
   private const val CLOUD_PROJECT_NUMBER = 123456789012L  // Your project number
   ```

4. **Where to find Project Number:**
   - Google Cloud Console → Dashboard
   - Or: Google Cloud Console → IAM & Admin → Settings
   - It's a 12-digit number like `123456789012`

### Step 8: Verify Package Name

1. Ensure your package name matches in all places:

   **In [`app/build.gradle.kts`](app/build.gradle.kts:16):**
   ```kotlin
   applicationId = "com.example.anonychat"
   ```

   **In [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml):**
   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       package="com.example.anonychat">
   ```

2. This package name MUST match what you use in Play Console

---

## Part 4: Backend Configuration

### Step 9: Configure Backend Environment Variables

Add these to your backend `.env` file:

```bash
# Enable Play Integrity checks
PLAY_INTEGRITY_ENABLED=true

# Allow debug apps (set to false in production)
ALLOW_DEBUG_APPS=false

# Your Google Cloud project number (12-digit number)
GOOGLE_CLOUD_PROJECT_NUMBER=123456789012

# Your app's package name
EXPECTED_PACKAGE_NAME=com.example.anonychat

# Maximum age for nonce/token (milliseconds)
NONCE_MAX_AGE_MS=300000

# Path to service account key JSON file
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
```

### Step 10: Secure the Service Account Key

**CRITICAL SECURITY STEPS:**

1. **Never commit the JSON key to Git:**
   ```bash
   # Add to .gitignore
   echo "service-account-key.json" >> .gitignore
   echo "*.json" >> .gitignore  # If you want to ignore all JSON files
   ```

2. **Store securely on server:**
   ```bash
   # Create secure directory
   sudo mkdir -p /etc/secrets
   sudo chmod 700 /etc/secrets
   
   # Move key file
   sudo mv service-account-key.json /etc/secrets/
   sudo chmod 600 /etc/secrets/service-account-key.json
   ```

3. **Update environment variable:**
   ```bash
   GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/service-account-key.json
   ```

---

## Part 5: Testing

### Step 11: Test with Debug Build

1. **Enable debug mode in backend:**
   ```bash
   ALLOW_DEBUG_APPS=true
   ```

2. **Build and install debug APK:**
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Test registration:**
   - Open app
   - Try to register a new account
   - Check backend logs for integrity verification

4. **Expected debug behavior:**
   - Client sends special debug tokens like `"DEBUG_BUILD_SKIP_INTEGRITY"`
   - Backend should allow these when `ALLOW_DEBUG_APPS=true`

### Step 12: Test with Release Build

1. **Disable debug mode in backend:**
   ```bash
   ALLOW_DEBUG_APPS=false
   ```

2. **Build signed release APK:**
   ```bash
   ./gradlew assembleRelease
   ```

3. **Upload to Play Console Internal Testing**

4. **Install from Play Store:**
   - Add yourself as internal tester
   - Install app from Play Store link
   - **DO NOT sideload the APK** - it must come from Play Store

5. **Test registration:**
   - Open app
   - Try to register
   - Should receive valid integrity token
   - Backend should verify successfully

---

## Part 6: Get Your Certificate SHA-256 (Optional but Recommended)

### Step 13: Get Debug Certificate SHA-256

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Look for the line starting with `SHA256:` and copy the fingerprint.

### Step 14: Get Release Certificate SHA-256

```bash
keytool -list -v -keystore /path/to/your/release.keystore -alias your_alias
```

Enter your keystore password when prompted, then copy the SHA256 fingerprint.

### Step 15: Add to Backend Validation (Optional)

In your backend code, add certificate validation:

```javascript
const expectedCertDigests = [
  'YOUR_RELEASE_CERT_SHA256_HERE',  // Release certificate
  'YOUR_DEBUG_CERT_SHA256_HERE'     // Debug certificate (remove in production)
];

const certDigests = appIntegrity.certificateSha256Digest || [];
const validCert = certDigests.some(digest => 
  expectedCertDigests.includes(digest)
);

if (!validCert && certDigests.length > 0) {
  throw new Error('Invalid certificate signature');
}
```

---

## Summary Checklist

### Google Cloud Console ✅
- [ ] Created Google Cloud project
- [ ] Noted Project Number (12-digit)
- [ ] Enabled Play Integrity API
- [ ] Created service account
- [ ] Downloaded service account key JSON

### Google Play Console ✅
- [ ] Linked Google Cloud project
- [ ] Uploaded app to Internal Testing
- [ ] Waited 24-48 hours for app recognition

### Android App ✅
- [ ] Updated `CLOUD_PROJECT_NUMBER` in IntegrityManager.kt
- [ ] Verified package name matches everywhere
- [ ] Built and tested debug build
- [ ] Built and uploaded release build

### Backend ✅
- [ ] Added environment variables
- [ ] Stored service account key securely
- [ ] Never committed key to Git
- [ ] Tested with debug build
- [ ] Tested with release build from Play Store

---

## Troubleshooting

### "CLOUD_PROJECT_NUMBER not configured"
- Update the constant in IntegrityManager.kt with your 12-digit project number

### "App not recognized by Google Play"
- Wait 24-48 hours after first upload to Play Console
- Ensure app is installed from Play Store, not sideloaded
- Check that package name matches in all places

### "Device integrity check failed"
- Device might be rooted or modified
- Try on a different device
- Enable debug mode for testing

### "Token signature verification failed"
- Ensure service account key is correct
- Check that Play Integrity API is enabled
- Verify Google Cloud project is linked to Play Console

### "Nonce mismatch"
- Check that nonce is being sent correctly from client
- Verify nonce encoding (Base64)
- Ensure nonce hasn't expired (5 minutes)

---

## Important Notes

1. **First Upload**: It takes 24-48 hours for Google to recognize your app after the first upload to Play Console.

2. **Testing**: Always test with release builds installed from Play Store for production-like behavior.

3. **Security**: Never commit service account keys to version control.

4. **Debug Mode**: Only enable `ALLOW_DEBUG_APPS=true` in development environments.

5. **Package Name**: Must be consistent across:
   - build.gradle.kts
   - AndroidManifest.xml
   - Play Console
   - Backend configuration

---

## Additional Resources

- [Play Integrity API Documentation](https://developer.android.com/google/play/integrity)
- [Google Cloud Console](https://console.cloud.google.com/)
- [Google Play Console](https://play.google.com/console/)
- [Service Account Keys Best Practices](https://cloud.google.com/iam/docs/best-practices-for-managing-service-account-keys)

---

**Last Updated**: 2026-03-15
**Version**: 1.0.0