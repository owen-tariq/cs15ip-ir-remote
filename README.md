# CS15iP IR Remote

**An Android IR-blaster remote for the Sony ICF-CS15iP — and the first public documentation of its infrared codes.**

The ICF-CS15iP (2011) is a Sony clock radio / iPhone dock whose remote, the **RMT-CCS15iP**, was never captured in any public IR database. Not RemoteCentral, not the JP1 forums, not LIRC, not irdb, not Flipper-IRDB, not Global Caché. A [JP1 forum request from 2021](http://www.hifi-remote.com/forums/viewtopic.php?t=102896) went unanswered.

So we found the codes ourselves — by brute-forcing the Sony SIRC address space with an Android phone's IR blaster, pointed at a real unit. This repo is the app, and the codes.

## About

A small indie project born out of a simple problem: a perfectly good Sony dock radio, a lost remote, and zero replacement codes anywhere on the internet. Instead of buying a $15 replacement remote, the phone in your pocket becomes the remote — and the codes get published for everyone who comes after.

The app is a single-screen, dark-themed remote with only the buttons that actually work on real hardware. No ads, no permissions beyond IR transmit and vibration, ~5 MB.

**Tested on a Xiaomi Redmi K40 running Pixel OS (Android 16).** It should work on any Android phone with a built-in IR blaster (most Xiaomi/Redmi/Poco, many Huawei/Honor, some others). If your phone has no IR hardware, the app will tell you — it can't transmit without it.

## Install

Grab **`app-debug.apk`** from [**Releases → Latest build**](../../releases/latest) — built automatically by CI on every push. Requires a phone with an IR blaster.

## The codes

Sony SIRC, **15-bit**, 40 kHz carrier. The unit listens on two device addresses — 68 for system functions, 100 for the tuner — matching Sony's classic boombox scheme.

### ✅ Confirmed working (tested on real hardware)

| Function | Device | Command | Notes |
|:---|:---:|:---:|:---|
| Volume + | 68 | 18 | |
| Volume − | 68 | 19 | |
| Sound | 68 | 48 | toggles between Mega Bass and MegaXpand |
| *(unlabeled)* | 68 | 49 | responds; exact function unidentified |
| Sleep | 68 | 96 | |
| Audio In ON | 68 | 67 | turns on Audio In (docked music pauses) |
| Audio In OFF | 68 | 87 | turns off Audio In |
| Play/Pause (iPod) | 100 | 51 | toggles play and pause |
| Next track (iPod) | 100 | 68 | skips to next song |
| Previous track (iPod) | 100 | 67 | tap = previous song (holding scrubs back within the track) |
| Band | 100 | 111 | **toggles AM/FM only** — does not switch to iPod |
| Tune + | 100 | 115 | radio mode only |
| Tune − | 100 | 116 | radio mode only |

