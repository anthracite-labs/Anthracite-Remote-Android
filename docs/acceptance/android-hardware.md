# Android hardware acceptance plan

IME, hardware keyboard, terminal rendering, accessibility and process-death
recovery cannot be proven by emulators or unit tests. This is the checklist
for the physical-device pass. A row counts only with the device model, Android
version, date and tester recorded.

Status: **not run**.

## Device matrix (fill per run)

| Device | Android | Date | Tester |
| --- | --- | --- | --- |
| — | — | — | — |

## 1. IME and hardware keyboard

| # | Step | Expected |
| --- | --- | --- |
| 1 | Type in the terminal with Gboard (gesture + voice dictation off) | bytes arrive in order, no duplication on autocorrect |
| 2 | Connect a Bluetooth/USB hardware keyboard, arrows + Ctrl-C/Ctrl-D | control sequences reach the host (`^C` interrupts) |
| 3 | Rotate the device with the terminal open | grid resizes, no lost input, cursor stays inside the grid |

## 2. Terminal rendering

| # | Step | Expected |
| --- | --- | --- |
| 1 | `htop` + a 256-colour test pattern | colours match, wide glyphs never split mid-cell |
| 2 | Fast output (`dmesg`, `cat` a large log) | backpressure engages, no ANR, every byte eventually shown |
| 3 | Smallest and largest font/display-size settings | layout holds, scrolling stays usable |

## 3. Accessibility

| # | Step | Expected |
| --- | --- | --- |
| 1 | TalkBack through connect → browse → delete-confirm | every destructive action announces itself before acting |
| 2 | Screen reader over terminal output | polite announcements, no per-byte chatter lockup |

## 4. Process death and recovery

| # | Step | Expected |
| --- | --- | --- |
| 1 | `adb shell am kill` with managed deployments recorded | relaunch reconciles each record by identity; nothing offered on a name |
| 2 | Kill during an SFTP upload | temp file remains server-side under a random name; no half file at the final name |
| 3 | Airplane mode mid-session, then reconnect | session state re-read, never presented as current from cache |

## Results

| Date | Device | Android | Area | Outcome |
| --- | --- | --- | --- | --- |
| — | — | — | — | not run yet |
