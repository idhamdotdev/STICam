# STICam Privacy Policy

**Last updated:** August 5, 2026

STICam is a free Android app that turns your phone into a webcam for a Windows PC you
own. This page explains what the app does — and does not do — with your data.

The short version: STICam has no developer-operated cloud backend. Camera video goes
only to the Windows PC address you choose. Read the version note below if you installed
the original v1.0.0 Android release.

## Important version note

- **Android v1.0.0:** the bundled upstream MediaPipe Tasks 0.10.35 runtime can send API
  performance and utilization metrics to Google. Google states that MediaPipe does not
  send the image/video input itself. The metrics are not received by the STICam
  developer. See Google's [MediaPipe Tasks Privacy Notice](https://developers.google.com/edge/mediapipe/solutions/tasks#mediapipe_tasks_privacy_notice).
- **Android v1.0.1 and later:** the release build selects MediaPipe's bundled no-op
  statistics logger and excludes Google Data Transport and Firebase. These builds send
  no MediaPipe metrics.

Until v1.0.1 appears on the official Releases page, treat v1.0.0 as the current affected
version. This disclosure will remain here so users can distinguish old and remediated
builds.

## What STICam does

- Streams camera video from your phone to a Windows PC over your local Wi-Fi network or
  a USB cable, using an IP address you type into the app.
- Runs on-device face tracking (MediaPipe) so the shot stays framed on you. Landmark
  processing happens entirely on your phone; only the resulting camera video is sent to
  the Windows PC you choose.
- Optionally applies on-device AR filters (face effects).

## What STICam does not do

- No account, sign-in, or registration.
- No STICam analytics service, crash reporting, advertising SDK, or developer-operated
  cloud backend.
- No camera frames, video, or face landmarks are sent to Google by MediaPipe.
- No data is sent to the STICam developer.

Every feature works without internet access because streaming is local. On v1.0.0,
blocking internet access also prevents MediaPipe's metrics transport; v1.0.1 removes that
transport from the APK.

## Permissions used

| Permission | Why STICam needs it |
|---|---|
| Camera | To capture and stream video to your PC. |
| Network / Wi-Fi state | To connect to your PC over your local network. |
| Foreground service (camera) | To keep streaming while you switch to another app, like Zoom or Discord. |
| Notifications | To show the ongoing-stream notification Android requires for foreground services. |
| Wake lock | To keep the phone's processor awake during an active stream. |

STICam v1.0.1 and later does not request microphone, storage, location, contacts, or
advertising-ID permissions. The original v1.0.0 APK declared storage permissions that
were not needed by its user-facing features; those declarations were removed in v1.0.1.

## Network security

Video and control traffic sent between your phone and PC over Wi-Fi are **not encrypted
or authenticated**. Use Wi-Fi mode only on a network you trust. USB mode uses an ADB
reverse tunnel between your phone and your own PC and does not expose the listener to
Wi-Fi.

This is a documented design limitation. The full security model is in
[SECURITY.md](SECURITY.md).

## Children's privacy

STICam does not knowingly collect personal information from children. The STICam
developer does not collect personal information from users. The v1.0.0 MediaPipe metrics
behavior is described separately above.

## Changes to this policy

Future changes will be posted at this same URL with an updated "Last updated" date.

## Contact

Questions about this policy: **hello@idham.dev**

Project home: <https://github.com/idhamdotdev/STICam>
