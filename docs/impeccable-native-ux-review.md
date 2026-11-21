# Skip Silence Player — Impeccable native UX review

Date: 2026-11-21  
Scope: SwiftUI iOS player + library, Jetpack Compose Android player + library.

> **DEGRADED — single-context assessment:** no independent sub-agents, native simulator, browser-equivalent native screenshot capture, or accessible Impeccable detector binary were available. The detector launcher was attempted and was not installed. Findings are grounded in repository source, not rendered-screen evidence. A 40/40 claim would be unverified.

## Product design direction

Preserve the immersive, dark, teal-accent player; use native dialogs and controls instead of replacing the visual identity. Put the current media and core playback actions first. Surface power tools only when requested. Retain all current silence detection and playback behavior.

## Prioritized issues and changes

| Priority | Finding | Impact | Resolution |
| --- | --- | --- | --- |
| P1 | Playback errors were custom overlays on both platforms | Background controls could remain active; recovery did not read as a platform dialog | Adopted SwiftUI alert and Material AlertDialog with Dismiss / Retry |
| P1 | Android locked gestures allowed system Back to exit instead of unlocking | Unexpected navigation, loss of playback context | Back unlocks the controls first |
| P1 | iOS settings sheets had no explicit visible dismissal | Users had to discover swipe-to-dismiss | Added an accessible Done action |
| P1 | Player presets sat in horizontally scrolling, non-indicated lanes | Available speeds, silence presets, subtitle positions, or timer cancellation could be hidden | Replaced with visible adaptive grids (iOS) and wrapping choices (Android) |
| P1 | Some tap targets were below platform guidance | Difficult operation with motor impairments | Enlarged unlock, history, bookmarks, and settings targets |
| P1 | Android radio rows had two independent click targets | Duplicate screen-reader focus or taps | Moved selection semantics to the enclosing row; radio displays state only |
| P2 | Android Back action sat to the right of the title | Off-platform navigation placement | Moved Back to leading edge on both platforms |
| P2 | Android screen-awake flag was set even while idle or browsing Library | Unnecessary battery use | Set only during active visible foreground playback |
| P2 | Android Library stretched on tablets | Long lines and scattered controls | Centered readable content in a 640 dp column |
| P2 | Library filenames and help were small/faint | Poor text legibility | Increased copy size and contrast, allowed wrapping and minimum row heights |
| P2 | Seekbar thumbs were clipped at their start/end positions | Progress looked cut off at 0% and 100% | Constrained drawn thumb positions |
| P2 | Speed/seek actions lacked specific spoken state on Android; selected options lacked spoken state on iOS | TalkBack / VoiceOver ambiguity | Added dynamic seek interval, speed and selection announcements |

## Heuristic score (source-review estimate, not native certification)

| Nielsen heuristic | Before | After (provisional) |
| --- | ---: | ---: |
| Visibility of system status | 4 | 4 |
| Match to the real-world task | 3 | 3 |
| User control and freedom | 2 | 4 |
| Consistency and standards | 3 | 3 |
| Error prevention | 3 | 3 |
| Recognition over recall | 2 | 4 |
| Flexibility and efficiency | 4 | 4 |
| Aesthetic and minimal design | 3 | 3 |
| Error recognition and recovery | 2 | 4 |
| Help and documentation | 1 | 2 |
| **Total** | **27/40** | **34/40** |

The after score represents code-level expectations, *not* a verified device score. Do not call this 40/40 without evidence.

## Regressions to validate on real platforms

- [ ] iPhone portrait, iPhone landscape, iPad, Android phone, Android tablet/split-screen: library, playback, tools and fullscreen controls remain accessible without truncation.
- [ ] Increase system text to ~130–200%; verify accessible touch targets, wrapping, 3-column presets, error dialogs and bottom controls.
- [ ] VoiceOver and TalkBack: previous/next, playback speed, selected presets, seeking, bookmark removal, dismiss/retry, and the unlock control.
- [ ] Test Android system Back while controls are locked, fullscreen, any sheet is open and a dialog is visible.
- [ ] Test portrait/landscape, safe-area, navigation-bar, display cutout and inset behavior.
- [ ] With background audio, PiP, Bluetooth disconnection and app pause/resume, confirm no regression in media service and screen-awake behavior.
- [ ] Verify A–B repeat, book/timer actions, silent-range visualization, manual scrubbing, subtitle imports, and automatic next-video playback.
- [ ] Compare screenshots and semantic contrast against both platforms' accessibility settings.

### Verification performed

- Read current repository source and platform design guidance.
- Reviewed changes against source and checked delimiter balance in the modified Swift/Kotlin files.
- Confirmed all `R.string` references used in Android main UI resolve, with no duplicate string IDs.
- No native compile, screenshot, accessibility-tree scan, runtime test, or Impeccable detector run could complete in this environment.
- No GitHub Actions were invoked. Follow the project's public Playground verification process when running CI.
