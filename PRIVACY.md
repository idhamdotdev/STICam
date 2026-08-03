# STICam Privacy Policy

**Last updated:** August 3, 2026

STICam is a free Android app that turns your phone into a webcam for a Windows PC you
own. This page explains what the app does — and does not do — with your data.

The short version: STICam has no server. There is nowhere for your data to go.

## What STICam does

- Streams camera video from your phone to a Windows PC over your local Wi-Fi network or
  a USB cable, using an IP address you type into the app.
- Runs on-device face tracking (MediaPipe) so the shot stays framed on you. This
  processing happens entirely on your phone.
- Optionally applies on-device AR filters (face effects).

## What STICam does not do

- No account, sign-in, or registration.
- No analytics, crash reporting, or advertising SDKs.
- No data of any kind is sent to the developer or to any third-party server.
- Video and face-tracking data never leave your own devices — they travel directly from
  your phone to your PC, and nowhere else.

You can verify this yourself: block STICam from the internet in your firewall or with
Android's per-app data settings. Every feature keeps working, because nothing it does
requires the internet.

## Permissions used

| Permission | Why STICam needs it |
|---|---|
| Camera | To capture and stream video to your PC. |
| Network / Wi-Fi state | To connect to your PC over your local network. |
| Foreground service (camera) | To keep streaming while you switch to another app, like Zoom or Discord. |
| Notifications | To show the ongoing-stream notification Android requires for foreground services. |

## Network security

Video and control traffic sent between your phone and PC over Wi-Fi are **not encrypted
or authenticated**. Use Wi-Fi mode only on a network you trust. USB mode stays entirely
on your own machine and never touches Wi-Fi.

This is a documented design limitation, not an oversight. The full security model is in
[SECURITY.md](SECURITY.md).

## Children's privacy

STICam does not knowingly collect personal information from anyone, including children —
it does not collect personal information from anyone, period.

## Changes to this policy

Any future changes will be posted at this same URL with an updated "Last updated" date.

## Contact

Questions about this policy: **hello@idham.dev**

Project home: <https://github.com/idhamdotdev/STICam>
