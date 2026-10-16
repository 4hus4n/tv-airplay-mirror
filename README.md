# AirMirror

Ad-free, unlimited AirPlay screen-mirroring receiver for Android TV. Mirror an iPhone or iPad to the TV with video and sound — no app needed on the iPhone.

## Install
1. Download **AirMirror.apk** from the latest [Release](../../releases/latest).
2. Install it on the TV (e.g. with Downloader or a USB stick) and open **AirMirror**.
3. On the iPhone (same Wi-Fi): **Control Centre → Screen Mirroring →** pick the TV name shown on screen.

## Using it
- **Back** on the remote while mirroring disconnects the phone.
- **Rename TV** changes the name the iPhone sees.
- **Connection code** makes the TV show a 4-digit code that the iPhone must enter.
- Keep the app open on the TV while mirroring.

## Limits
- Netflix, Disney+ and other DRM apps show black on the TV (Apple blocks them over AirPlay mirroring).
- AirPlay *audio-only* (music sent with the AirPlay button, not Screen Mirroring) uses Apple Lossless, which isn't supported yet — use Screen Mirroring.
- iPhone and TV must be on the same network; some routers with "client isolation" or blocked multicast hide the TV.

## How it works
The AirPlay protocol (pairing, FairPlay handshake, mDNS discovery, RTP streams) comes from the open-source [UxPlay](https://github.com/FDH2/UxPlay) library, compiled for Android with the NDK. Video (H.264) and audio (AAC-ELD) are decoded with Android's hardware MediaCodec.

## License
GPL-3.0 (inherited from UxPlay). Bundles libplist (LGPL-2.1) and uses OpenSSL.
