# Remote ADB Connector

An IntelliJ Platform plugin for Android Studio that connects Android devices attached to a remote Windows ADB server to your local Linux Android Studio instance — with a single click.

## Problem

When using Android Studio on a Linux machine via RDP from a Windows laptop with a USB-connected Android phone, the typical workflow involves multiple shell scripts, manual environment variable exports, and constant monitoring for disconnections.

```
Android Phone ──USB── Windows Laptop ──LAN── Linux Machine
                      (ADB Server)            (Android Studio)
```

**Remote ADB Connector eliminates all of this.** Install the plugin, enter the Windows IP, and click Connect.

## Features

- **One-click connection** — No CLI, no shell scripts, no env variables
- **Automatic device discovery** — Detects USB devices on the Windows ADB server
- **TCP mode switching** — Automatically runs `adb tcpip` on remote devices
- **Continuous monitoring** — Watches for disconnections and auto-reconnects
- **Per-process ADB server switching** — Never modifies your global environment
- **Persistent settings** — Configuration survives IDE restarts
- **Native UI** — Tool window with real-time status, device list, and controls

## Requirements

- **Android Studio Panda** (2025.3.1) or later
- **ADB** installed and accessible on the Linux machine
- **ADB server** running on the Windows machine (`adb start-server`)
- **Network connectivity** between Windows and Linux machines

## Installation

### From ZIP

1. Download the latest `remote-adb-connector-x.x.x.zip` from [Releases](releases)
2. In Android Studio, go to **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded ZIP file
4. Restart Android Studio

### From Source

```bash
git clone https://github.com/adbconnect/remote-adb-connector.git
cd remote-adb-connector
./gradlew buildPlugin
```

The installable ZIP will be in `build/distributions/`.

## Usage

### Initial Setup

1. Open the **Remote ADB** tool window (right sidebar)
2. Enter the **Windows Host** IP address (e.g., `192.168.1.100`)
3. Configure ports if needed:
   - **ADB Port**: Default `5037` (Windows ADB server port)
   - **Device TCP Port**: Default `5555` (for `adb tcpip`)
4. Click **Connect**

### Auto-Connect

Enable **Auto Connect on Startup** to automatically connect when Android Studio opens.

### Auto-Reconnect

When enabled (default), the plugin automatically detects device disconnections and re-establishes the connection without manual intervention.

### Tool Window

```
┌─────────────────────────────────────┐
│  Remote ADB Connector               │
│                                      │
│  Settings                            │
│  ┌────────────────────────────────┐  │
│  │ Windows Host: [192.168.1.100] │  │
│  │ ADB Port:     [5037]         │  │
│  │ Device TCP:   [5555]         │  │
│  │ Polling (s):  [5]            │  │
│  │ ☑ Auto Connect               │  │
│  │ ☑ Auto Detect Devices        │  │
│  │ ☑ Auto Reconnect             │  │
│  └────────────────────────────────┘  │
│                                      │
│  Status                              │
│  ┌────────────────────────────────┐  │
│  │ 🟢 Connected                  │  │
│  │ Host: 192.168.1.100           │  │
│  │ Last update: 14:32:15         │  │
│  │ Devices: 1                    │  │
│  └────────────────────────────────┘  │
│                                      │
│  Connected Devices                   │
│  ┌────────────────────────────────┐  │
│  │ 🟢 Pixel 9 (192.168.1.105)   │  │
│  └────────────────────────────────┘  │
│                                      │
│      [Connect]    [Disconnect]       │
└─────────────────────────────────────┘
```

### Status Indicators

| Icon | State |
|------|-------|
| ⚪ | Idle |
| 🟡 | Connecting / Waiting |
| 🟢 | Connected / Monitoring |
| 🟠 | Reconnecting |
| 🔴 | Error |

## Configuration Reference

| Setting | Default | Description |
|---------|---------|-------------|
| Windows Host | _(empty)_ | IP or hostname of the Windows machine |
| ADB Port | `5037` | ADB server port on Windows |
| Device TCP Port | `5555` | Port for `adb tcpip` mode |
| Polling Interval | `5` seconds | How often to check device status |
| Auto Connect | `false` | Connect automatically on IDE startup |
| Auto Detect | `true` | Auto-detect devices on Windows |
| Auto Reconnect | `true` | Reconnect when device is lost |

## Troubleshooting

### "Cannot reach ADB server"

- Verify the Windows machine IP is correct
- Ensure `adb start-server` is running on Windows
- Check firewall allows connections on port 5037
- Test connectivity: `adb -H <windows-ip> -P 5037 devices`

### "No devices found"

- Verify the phone is connected via USB to Windows
- Check USB debugging is enabled on the phone
- Run `adb devices` on Windows to confirm the device appears

### "Could not determine IP address"

- Ensure the phone is connected to WiFi on the same network
- Verify the phone has a valid IP address in WiFi settings

### Plugin not appearing

- Ensure you're running Android Studio Panda (2025.3.1) or later
- Check **Settings → Plugins** to verify the plugin is enabled

## Development & Publishing

- See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions and development guidelines.
- See [PUBLISHING.md](PUBLISHING.md) for step-by-step instructions on deploying the plugin to the JetBrains Plugin Marketplace.
- See [ARCHITECTURE.md](ARCHITECTURE.md) for the technical design and component overview.

## License

Apache License 2.0
