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

## Second pass — compact playback, error recovery and performance (2026-11-21)

**Assessment: DEGRADED, sequential source-only review.** No sub-agents, native screen reader, on-device screenshot comparison, or Impeccable detector binary is available.

### P1 fixes beyond the first pass

- **Compact-height playback:** Both players collapse the detailed silence panel and secondary chips on short landscape windows. The top-right overflow menu provides explicit access to silence skipping, speed, subtitles and playback tools; seeking and transport controls remain visible. This also activates at large system text sizes.
- **Modal keyboard isolation:** Playback shortcuts cannot act behind iOS importer, sheets, errors or confirmations and Android sheets, errors, confirmations and gesture lock.
- **Import recovery:** iOS failed document/folder/subtitle imports now open a native alert above Library instead of placing a hidden gesture HUD under it. Android empty/denied folder imports show an accessible dialog instead of an invisible HUD; unexpected folder read failures are recovered rather than propagated unhandled.
- **Player UI performance:** Removed an Android `ContentResolver.query` from the 200 ms polling loop. File-size metadata is loaded on `Dispatchers.IO` once per media URI. Estimated silence skipped is refreshed when the runtime publishes a new range list, rather than repeating a full range sum each polling tick or using a stale captured list.

### P2 polish

- Android's audio-only mode text is brighter against black.
- Android Library copy now says `Open folder` and explains that the app skips quiet pauses, consistent with iOS.
- Dialog and empty-folder titles explain recovery without exposing invisible-only HUD feedback.
- Android seek/timing labels use readable font sizes; TalkBack receives selected-state annotations and the actual seek interval.

### Build evidence

- **Public Playground run #1:** [native Android tests + APK and iOS simulator build](https://github.com/Persie0/Playground/actions/runs/44940775188), both successful.
- **Public Playground run #2:** [native Android tests + APK and iOS simulator build](https://github.com/Persie0/Playground/actions/runs/44941139888), both successful, including compact layout and SAF/performance fixes.
- **Public Playground run #3:** [final validation of PR head `4cd962daa91f`](https://github.com/Persie0/Playground/actions/runs/44941333270), Android unit tests + debug APK **passed**, iOS simulator compilation **passed**. The tested commit included the final wording/contrast-only fixes.
- No workflows were run in the application repository. All three validations executed only in the public `Persie0/Playground` repository.
- Native build success does not substitute for screenshots, VoiceOver/TalkBack, emulator rotation, large text and real-device media testing.

### Score and finish criteria

The original source-only **27/40** baseline, first-pass **34/40**, and second-pass **37/40 (provisional)** are heuristic estimates. **40/40 remains unverified.** Screenshots and interaction evidence from an iPhone, iPad, Android phone, and tablet are still required for visual, gesture, orientation and accessibility certification.

**Remaining device acceptance:** open Library and play a video at narrow landscape and 140–200% font scale; access all tools; import a broken file and empty folder and dismiss error; verify TalkBack/VoiceOver and min touch targets; scrub boundaries with subtitles visible; verify performance with a slow SAF provider, Bluetooth disconnect, PiP and audio-only mode.

Questions skipped: the user already requested implementing all fixes; device-only validation is the remaining constraint.

## Third pass — gesture guidance, sheet accessibility and safe areas (2026-11-21)

**Review mode: DEGRADED single-context.** The Impeccable context/detector launcher was attempted but is not installed in the current runtime; independent sub-agents are unavailable. Design and technical checks were conducted sequentially from native source. A public-runner screenshot workflow was requested to gather actual on-device render evidence.

### Ranked additional findings and resolution

| Priority | Finding | Source location | Fix |
| --- | --- | --- | --- |
| P1 | The player relies on hidden gestures after the first-run hint | ContentView home/player menu and Android HomeScreen/menu | Contextual Player guide, available from Library and playback More menu, explaining touch controls, local analysis, and advanced settings |
| P1 | Android sleep-timer cancellation can be hidden in a horizontal scroller | MainActivity Tools sheet | FlowRow wrapping choices and a separate, always visible Cancel action |
| P1 | A–B actions fight for limited horizontal space with long timestamps/system text | Both Tools sheets | Two primary A/B controls grouped together, destructive Clear action on its own row |
| P1 | Android recent media appeared to accessibility services as unselected radio options | MainActivity Recent sheet | Standard text and history-icon click targets with readable multi-line filenames |
| P2 | iOS chapter and playlist entries had small click areas/truncated labels | ContentView Tools/Playlist sheets | Minimum 44-pt targets, two-line chapter and playlist titles, selected playlist semantics |
| P2 | Android adaptive-noise-floor toggle could be adjusted while skipping was disabled | MainActivity Silence sheet | Disabled while Skip silence is off, entire row is a single 48-dp switch target |
| P2 | iOS subtitle import and destructive actions crowd narrow settings sheets | ContentView Subtitle/Tools sheets | Stacked import/removal and destructive actions; long filenames wrap |
| P2 | Android locked-player unlock button sat in the top screen region without a status-bar inset | MainActivity unlock overlay | Applied statusBarsPadding so the unlock action clears the status bar/cutout |

### First native screenshot review

- **iPhone simulator first-run:** the application title, import cards, and guide clustered in the upper portion of the screen with a large empty lower area. The empty-state content is now centered vertically within the available height, without centering a populated Library or disabling scrolling.
- **Android emulator (130% font):** the same first-run imbalance was present. The empty-state container now centers the import actions when no recent or active video exists; populated Library remains top-aligned. The dark player also forces light system-bar icon appearance to avoid low-contrast status icons when the device's system theme is light.
- Captured renders: [iOS native screenshot run](https://github.com/Persie0/Playground/actions/runs/44952560304) and [Android native screenshot run](https://github.com/Persie0/Playground/actions/runs/44952918697). These screenshots were taken before the centering correction and motivated the change.

### Native checks

- Android unit tests/debug APK and iOS simulator compilation: [Public Playground CI](https://github.com/Persie0/Playground/actions/runs/44952448005).
- Android phone (130% system text) and iPhone simulator screenshot capture: [Public Playground native screenshots](https://github.com/Persie0/Playground/actions/runs/44952560304).
- Source structural scan: Swift and Kotlin delimiters balanced; all 105 referenced Android string IDs resolved, 156 total string IDs, no duplicate resource names (as of this pass).
- No private-repository GitHub Actions runs were requested.

### Rating

Previous provisional assessment: **37/40**. This third pass fixes the remaining concrete source-level discoverability, touch-target, and responsive control issues. **39/40 remains a provisional source-based estimate, not a certified native UX score.** A 40/40 rating requires evidence from interactive playback, VoiceOver/TalkBack, real media, screen rotations, and multiple form factors. Native compilation and an empty-state screenshot alone are insufficient.

### Device acceptance

- [ ] Test playback on actual videos in both portrait and landscape, including subtitle overlays, edge seeking, locked gestures and short-window overflow
- [ ] Verify both light/dark system accessibility settings and at least 130–200% text sizes in controls, sheets and error dialogs
- [ ] Validate VoiceOver/TalkBack navigation and focus order for the guide, timer, radio/choice controls, destructive confirmation and playlist
- [ ] Capture iPhone, iPad, Android phone and Android tablet screenshots and correct any visual failures
- [ ] Verify media sessions/background audio/PiP and file permission errors with real providers

Questions skipped: user explicitly requested implementing all prioritized fixes; unverified native device behavior is the remaining acceptance constraint.

### Round 3 final source-pinned CI and screenshot limitations

- **Native build and Android unit tests: PASSED** in [public Playground run 44953305558](https://github.com/Persie0/Playground/actions/runs/44953305558). Both the iOS simulator compile and Android unit-test/debug-APK jobs completed successfully at source commit `ac79ad452f176de36627a5c8ba5ccd02cf96ee1b`.
- **iPhone pre-centering capture: usable** in [public run 44952560304](https://github.com/Persie0/Playground/actions/runs/44952560304); led to fixing top-heavy intro composition.
- **Android pre-centering capture: usable** in [public run 44952918697](https://github.com/Persie0/Playground/actions/runs/44952918697); led to corresponding empty-state and status-bar adjustments.
- **Centered Android capture** in [public run 44953309446](https://github.com/Persie0/Playground/actions/runs/44953309446) showed the new centered composition and readable status-bar icons but was obscured by a **Pixel Launcher system-process ANR dialog**. This is a snapshot validation failure and cannot count as a clean UI pass or an app crash. A clean screenshot attempt is recorded in [public run 44953670294](https://github.com/Persie0/Playground/actions/runs/44953670294).
- Device screen-reader and in-media gesture testing remain unverified. **Do not interpret build success or first-run screenshots as a certified 40/40.**

## Fifth pass — reconciled real-media native UX (2026-11-22)

**Review provenance:** ⚠️ DEGRADED: single-context. The Impeccable detector/launcher and independent sub-agents were unavailable; native source review, public Android 35 Pixel emulator runs, and public macOS simulator builds were used. Product visuals retain the established dark player with teal interactive accents.

### Prioritized regressions corrected on latest `main`

| Priority | Native finding | Correction |
| --- | --- | --- |
| P1 | Android screen remained permanently awake, including while navigating the Library | Manage `FLAG_KEEP_SCREEN_ON` only during foreground non-audio-only video playback |
| P1 | Android selectable presets and timer chips hid their later options and Cancel action in horizontal scrolling lanes | Use wrapping options; a separate, visible Cancel timer action |
| P1 | Android A–B start/end values and Clear competed for a single short row | Move Clear A–B onto its own 48 dp action row |
| P1 | A real-media emulator capture showed Library Back to the right of the filename | Restore leading-edge Back action and allow two filename lines in portrait |
| P2 | Default checked Android switches used a purple thumb in a teal player | Explicit teal/deep-teal checked colors on player and settings controls |
| P2 | Open video and Open folder shared folder icons | Play arrow for Open video; folder for Open folder |
| P2 | Audio-only label was faint against the dark canvas | Increase foreground contrast |
| P2 | Chip icons touched their captions | Add an explicit icon–label gap |
| P2 | Android recent file titles truncated in a single short line | Allow two lines and improve text size |
| P2 | iOS bookmark and progress thumb markers clipped at timeline endpoints | Clamp marker offsets inside the actual timeline width |

### Public CI and native interaction evidence

- [Initial public real-media run](https://github.com/Persie0/Playground/actions/runs/44973032343): generated MP4 opened; native unit tests and simulator compilation passed; initial player controls auto-hid during UI hierarchy inspection.
- [Public screenshot finding](https://github.com/Persie0/Playground/actions/runs/44973422036): player loaded H.264/AAC; source-specific title, purple switch and menu behavior informed fixes.
- [Public source-pinned run](https://github.com/Persie0/Playground/actions/runs/44974479590): **Android unit tests, debug APK, iOS simulator compile, and full Android generated-video interaction smoke PASSED**. Checked gesture guide, MP4 title, Settings, audio-only toggle and return to Library. Player screenshot exposed the next leading-navigation improvement.
- [Final public verification run](https://github.com/Persie0/Playground/actions/runs/44974923349): **PASSED** Android unit tests, debug APK, iOS simulator compilation, and generated-video emulator interaction at source commit `c4693123840f2f7524c2588b16dab178c021328f`. Native screenshot confirmed the Back control now leads the header.

No GitHub Actions were executed in SkipSilencePlayer itself. The workflow is located in public `Persie0/Playground` and uses the user-requested token preference.


- **Final typography confirmation:** [public Playground run 44975403353](https://github.com/Persie0/Playground/actions/runs/44975403353) **PASSED** the same native checks at source commit `968e1b47712a80d5e9a28d30cdaf9898b9f525e3`. The Android screenshot confirms the filename now uses an ellipsis rather than wrapping its extension to an isolated line. The complete filename remains available to accessibility services.

### Remaining 40/40 acceptance gate

The real-media Android emulator flow meaningfully extends verification beyond compilation. It still **does not** certify 40/40: actual iPhone/iPad and Android tablet media views, VoiceOver/TalkBack reading order with real audio, landscape gestures, hardware PiP and high text scaling need hands-on or dedicated simulator screenshot evidence. No score of 40/40 should be claimed without those checks.

Questions skipped: the user requested full implementation; no additional preference is necessary for this pass.

## Round 6 — accessible controls and native error recovery (2026-11-22)

⚠️ DEGRADED: single-context (no sub-agent tool available, Impeccable native CLI launcher unavailable). The native source and prior simulator screenshots are the design evidence. A native code/a11y audit is the deterministic assessment; the web-only overlay detector does not apply to SwiftUI / Jetpack Compose targets. Checks were conducted sequentially, not by independent agents.

### Assessment A: design critique

**Design direction:** retain the immersive black player, restrained teal controls, familiar native dialogs, and clear entry points for local video, subtitle adjustment and silence skipping. The prior subjective 39/40 estimate was **not** device-verified; treat this as a new source-level review, not as a 39-to-40 score progression.

Strengths: (1) high-priority video and transport actions remain visible, (2) speed/silence/gesture options serve power users without dominating playback, and (3) local-file/import cues are clear. Main weaknesses: iOS sheet exit and invisible choices, Android lock exit, nonmodal failure recovery, scaling of crowded controls, and stale selection semantics.

| Nielsen heuristic | Source-level score (0–4) | Why not 4 everywhere |
| --- | ---: | --- |
| Visibility of system status | 3 | Playback/analysis status exists; not all operations are device-tested |
| Match to user language | 3 | Technical audio settings still require explanations |
| User control and freedom | 4 | Visible Done, Cancel, Back unlock, safe exit paths |
| Consistency and platform conventions | 3 | Player-specific controls are intentional, but need full OS acceptance |
| Error prevention | 3 | Confirmations and state constraints exist; all file-provider paths untested |
| Recognition over recall | 4 | Responsive preset grids and in-player guide expose choices |
| Flexibility and efficiency | 4 | Gestures, playback rates, bookmarks, subtitles and queues |
| Aesthetic and minimalist design | 3 | Dark/teal hierarchy strong; small-device layout needs screenshot checks |
| Error diagnosis and recovery | 3 | Native modal recovery implemented, but error permutations untested |
| Help and documentation | 3 | In-app gesture guide exists, but no contextual feature walkthroughs |
| **Provisional total** | **33/40** | **Subjective source assessment, not certified UX performance** |

### Priority fixes applied

| Priority | Issue | Native code resolution |
| --- | --- | --- |
| P1 | iOS speed, silence, subtitle-position and timer choices hidden in unmarked horizontal scroll regions | `LazyVGrid` with adaptive item widths, 44-pt targets and wrapped labels |
| P1 | Active sleep timer cancellation was an ambiguous chip displaying the timer state | Active status shown separately; clearly named **Cancel sleep timer** button |
| P1 | iOS Playback tools, Audio and other sheets lacked a visible exit | Reusable `SheetCard` now has a Done action using the system dismiss environment |
| P1 | iOS A–B repeat packed timestamps and destructive Clear into one row | Equal primary A/B targets plus separate destructive Clear action |
| P1 | Android playback errors were nonmodal overlays over live player controls | Native Material `AlertDialog` with Dismiss, Retry and Open another video |
| P1 | Locked Android controls had no system Back path, appeared near the status bar, and offered a small unlock target | BackHandler unlock, status-bar inset and at least 48 dp accessible target |
| P2 | iOS subtitle import/remove, chapters, playlist and recent filenames clipped or were crowded | Separate actions, two-line labels, 44-pt row targets |
| P2 | iOS sheet selection states were not announced reliably | Selected accessibility traits on real chip/list choices |
| P2 | Android compact-landscape silence switch inherited a purple thumb | Teal track and deep-teal thumb consistent with portrait |
| P1 | First-run iPhone/iPad Library was top-heavy and stretched on tablet | Vertically center the truly empty state and constrain content to 700 pt on iPad |

### Assessment B: native code and screenshot audit

- Static scan of current PR: four iOS responsive grids, explicit Done/Cancel, Android modal playback error and Back unlock all present.
- Android resources: **120 source-referenced string IDs**, no missing IDs or duplicate string names.
- [Public Playground run 44977446103](https://github.com/Persie0/Playground/actions/runs/44977446103): **PASSED** Android unit tests/APK, generated-video playback, gesture-lock → system Back unlock, Settings and Library navigation; iOS simulator build, iPhone and iPad launches and native PNG screenshot captures also passed.
- The first iPhone/iPad screenshots showed a top-heavy empty Library and nearly edge-to-edge action bars on iPad. The revised view centers only the truly empty state and caps content width at 700 pt.
- [iOS layout confirmation run 44977905944](https://github.com/Persie0/Playground/actions/runs/44977905944) tests the responsive correction with iPhone and iPad native screenshots. The outcome must be checked before claiming success.
- No private-repository GitHub Actions. All runner jobs and artifacts remain in public `Persie0/Playground`.

### Non-certified acceptance conditions

A 40/40 certification would require a broad device matrix: interactive video playback on iPhone/iPad and Android phone/tablet (portrait, landscape, 130–200% text), VoiceOver and TalkBack focus/announcements, all modal failure pathways, subtitle file-provider errors, background audio, PiP and physical-device gesture hardware checks. Simulator compilation and empty-state screenshots cannot establish these alone.

Questions skipped: the user asked for full implementation; no missing preference blocks this review. The remaining uncertainty requires device evidence, not a clarification.
