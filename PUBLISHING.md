# JetBrains Plugin Marketplace Distribution Guide

This document describes how to configure, sign, and publish the **Remote ADB Connector** plugin to the JetBrains Plugin Marketplace.

---

## 1. Prerequisites

Before publishing, you need:
1. A **JetBrains Account** registered on the [JetBrains Marketplace Hub](https://plugins.jetbrains.com/).
2. A **Personal Access Token** to authenticate publishing requests.
3. A **Private Key** and **Certificate Chain** to sign the plugin distribution (mandatory for all plugins on the Marketplace).

---

## 2. Generating Signing Certificates

JetBrains Marketplace requires all uploaded plugins to be signed. You can generate a self-signed key pair using OpenSSL.

### Step 2.1: Generate Private Key
Run the following command to generate a 2048-bit RSA private key encrypted with AES-256:
```bash
openssl genrsa -aes256 -out private_key.pem 2048
```
You will be prompted to enter a password. Keep this password safe! It corresponds to `PRIVATE_KEY_PASSWORD`.

### Step 2.2: Generate Certificate Chain
Generate a self-signed certificate for your private key:
```bash
openssl req -key private_key.pem -new -x509 -days 365 -out certificate_chain.pem
```
This generates a certificate valid for 365 days.

---

## 3. Configuration

The plugin's Gradle build is configured to read publishing and signing credentials from environment variables securely to avoid hardcoding secrets.

### Environment Variables Reference

| Environment Variable | Description |
|----------------------|-------------|
| `PUBLISH_TOKEN` | JetBrains Marketplace Personal Access Token. |
| `CERTIFICATE_CHAIN` | Path to `certificate_chain.pem` OR the Base64-encoded content of the file. |
| `PRIVATE_KEY` | Path to `private_key.pem` OR the Base64-encoded content of the file. |
| `PRIVATE_KEY_PASSWORD` | Password used to encrypt/decrypt the private key. |

> [!TIP]
> If you provide the content of `certificate_chain.pem` or `private_key.pem` directly as environment variables, you must Base64-encode them. The IntelliJ Platform Gradle Plugin automatically detects Base64-encoded variables and decodes them on-the-fly.
>
> Encode command example (macOS/Linux):
> `cat private_key.pem | base64`

---

## 4. First-time Manual Upload

Before using automated Gradle tasks, you must manually register the plugin on the Marketplace portal:

1. Log in to the [JetBrains Marketplace Hub](https://plugins.jetbrains.com/).
2. Click **Upload Plugin** in the top-right corner.
3. Upload the generated zip file located at `build/distributions/remote-adb-connector-1.0.0.zip`.
4. Fill in the required metadata (name, description, vendor email, etc.).
5. Wait for approval. JetBrains typically reviews first-time plugins within 2-3 business days.

Once the plugin page is approved and created on the portal, subsequent releases can be automated.

---

## 5. Publishing Releases

### Local Publishing
Set the environment variables and run:
```bash
# Set your secrets (Linux/macOS example)
export PUBLISH_TOKEN="perm:..."
export CERTIFICATE_CHAIN="/path/to/certificate_chain.pem"
export PRIVATE_KEY="/path/to/private_key.pem"
export PRIVATE_KEY_PASSWORD="your-passphrase"

# Sign and publish to default channel
./gradlew publishPlugin
```

### CI/CD Publishing (GitHub Actions)
Add these secrets to your GitHub Repository Secrets:
- `JETBRAINS_MARKETPLACE_TOKEN`
- `JETBRAINS_CERTIFICATE_CHAIN` (Base64-encoded)
- `JETBRAINS_PRIVATE_KEY` (Base64-encoded)
- `JETBRAINS_PRIVATE_KEY_PASSWORD`

You can use the following GitHub Actions workflow snippet (`.github/workflows/publish.yml`):

```yaml
name: Publish Plugin

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - name: Fetch Sources
        uses: actions/checkout@v4

      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: '21'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Publish Plugin
        env:
          PUBLISH_TOKEN: ${{ secrets.JETBRAINS_MARKETPLACE_TOKEN }}
          CERTIFICATE_CHAIN: ${{ secrets.JETBRAINS_CERTIFICATE_CHAIN }}
          PRIVATE_KEY: ${{ secrets.JETBRAINS_PRIVATE_KEY }}
          PRIVATE_KEY_PASSWORD: ${{ secrets.JETBRAINS_PRIVATE_KEY_PASSWORD }}
        run: ./gradlew publishPlugin
```
