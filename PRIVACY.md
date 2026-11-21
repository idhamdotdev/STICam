# STICam Privacy Policy

**Last updated:** November 21, 2026

STICam is a free Android app that turns your phone into a webcam for a Windows PC you
own. This page explains what the app does — and does not do — with your data.

## Important version note

- **Android v1.0.0:** the bundled upstream MediaPipe Tasks 0.10.35 runtime can send API
  performance and utilization metrics to Google. Google's MediaPipe notice states that
  the image/video input itself is not sent. The STICam Developer does not receive those
  metrics.
- **Android v1.0.1 and later:** the release build replaces the upstream remote
  statistics logger with MediaPipe's bundled no-op logger and excludes Google Data
  Transport and Firebase. The current v1.0.3 release is in this group.

This historical disclosure remains so users can distinguish the original v1.0.0 APK
from current releases.

## What STICam does

- Streams camera video from your phone to a Windows PC over your local Wi-Fi network or
  a USB cable. You can type the PC address manually. The code 12 QR preview also lets
  you scan that address from STICam Host. QR processing happens on your phone; the scan
  image is not saved or sent to a server, and scanning does not start streaming.
- Runs on-device face tracking (MediaPipe) so the shot stays framed on you. Landmark
  processing happens entirely on your phone; only the resulting camera video is sent to
  the Windows PC you choose.
- Optionally applies on-device AR filters (face effects).

## What the Developer does not receive

- No account, sign-in, or registration.
- No analytics, crash reporting, advertising SDKs, or developer-operated cloud backend.
- No camera video, face landmarks, account information, analytics, or crash reports are
  sent to the Developer or to a Developer-operated service. Current releases replace
  MediaPipe's upstream remote statistics logger with MediaPipe's bundled no-op logger
  and exclude Google Data Transport and Firebase from the app.
- Video travels directly from your phone to the PC address you choose. Face landmarks
  are not sent separately or retained by STICam.

## Permissions used

| Permission | Why STICam needs it |
| --- | --- |
| Camera | To capture and stream video to your PC, and to scan the optional Wi-Fi setup QR in builds that include it. |
| Network / Wi-Fi state | To connect to your PC over your local network. |
| Foreground service (camera) | To keep streaming while you switch to another app, like Zoom or Discord. |
| Notifications | To show the ongoing-stream notification Android requires for foreground services. |
| Wake lock | To keep the phone's processor awake during an active stream. |

STICam does not request microphone, storage, location, contacts, or advertising-ID
permissions.

## Optional local diagnostics

Diagnostic builds can log device/model information, OS and app versions, measured
frame dimensions, stage frame rates, and errors locally. Android USB stage logging is
debug-only. The Windows USB baseline is opt-in. These logs do not contain camera
images and are not automatically uploaded to the Developer. Review logs before
sharing them with support.

## Network security

Video and control traffic sent between your phone and PC over Wi-Fi are **not encrypted
or authenticated**. Use Wi-Fi mode only on a network you trust. USB mode uses an ADB
reverse tunnel between your phone and your own PC and does not expose the listener to
Wi-Fi.

## Children's privacy

The Developer does not knowingly collect personal information from children. STICam
still processes and transmits camera video locally as described above; that local
phone-to-PC processing is distinct from collection by the Developer.

## Changes to this policy

Any future changes will be posted at this same URL with an updated "Last updated" date.

## Contact

Questions about this policy: **hello@idham.dev**

Project home: <https://github.com/idhamdotdev/STICam>
