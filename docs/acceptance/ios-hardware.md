# iOS hardware acceptance plan

IME, hardware keyboard, terminal rendering, accessibility and process-death
recovery cannot be proven by the simulator. This is the checklist for the
physical-device pass. A row counts only with the device model, iOS version,
date and tester recorded.

Status: **not run**.

## Device matrix (fill per run)

| Device | iOS | Date | Tester |
| --- | --- | --- | --- |
| — | — | — | — |

## 1. IME and hardware keyboard

| # | Step | Expected |
| --- | --- | --- |
| 1 | Type in the terminal with the system keyboard + dictation | bytes arrive in order, no duplication from autocorrect |
| 2 | Smart Keyboard arrows, Esc, Ctrl sequences | cursor keys honour application-cursor mode, `^C` interrupts |
| 3 | Rotate / Stage Manager resize with the terminal open | PTY resize propagates (`window-change`), cursor stays in grid |

## 2. Terminal rendering

| # | Step | Expected |
| --- | --- | --- |
| 1 | `htop` + 256-colour + true-colour patterns | colours match, wide glyphs never split mid-cell |
| 2 | Fast output flood | backpressure pauses/resumes reads, app is not jetsammed |
| 3 | Smallest and largest Dynamic Type settings | layout holds, row count adapts |

## 3. Accessibility

| # | Step | Expected |
| --- | --- | --- |
| 1 | VoiceOver through connect → GitHub → deploy remove-confirm | every destructive action announces itself before acting |
| 2 | VoiceOver over terminal output | usable announcements, no per-byte chatter lockup |

## 4. Process death and recovery

| # | Step | Expected |
| --- | --- | --- |
| 1 | Swipe-kill with a shell open, relaunch | no half-trusted session; tmux re-attach offered by id |
| 2 | Loss of network mid-shell, then reconnect | re-read state, host-key change still refused until approved |
| 3 | Background the app for 30+ minutes, foreground | channel state revalidated, stale output never presented as live |

## Results

| Date | Device | iOS | Area | Outcome |
| --- | --- | --- | --- | --- |
| — | — | — | — | not run yet |
