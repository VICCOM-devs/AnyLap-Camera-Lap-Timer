# AnyLap design system

Look: a race-broadcast graphic. Near-black surfaces, a deep red glow at the top of every screen, bold italic Barlow for numbers and headlines, red accent used sparingly.

## Tokens (`ui/theme/Theme.kt` → `RT`)
| Token | Value | Use |
|---|---|---|
| `Bg` | #09090B | page |
| `Surface` / `SurfaceHigh` | #131316 / #18181C | cards (`CardGradient` top→bottom) |
| `Surface2` / `Surface3` | #1C1C21 / #26262D | inner tiles, buttons, tracks |
| `Stroke` / `StrokeHigh` | #26262C / #34343C | 1 dp borders |
| `Text` / `TextDim` / `TextFaint` | #F6F6F8 / #A1A1AA / #6B6B74 | text levels |
| `Accent` / `AccentHot` / `AccentDeep` | #E10600 / #FF3B2F / #7A0A0E | brand red; `AccentGradient` for primary actions |
| `Purple` | #B15CFF | fastest lap (overall) |
| `Green` | #22C55E | personal best, ready/calibrated |
| `Amber` | #F5A524 | attention: unknown crossings, needs calibration, final lap |
| `Gold` / `Silver` / `Bronze` | podium | positions 1–3 |

Racer colours come from `RacerPalette` and appear as avatar fill and as the 4 dp stripe on cards and tower rows.

## Type
- Font: **Barlow** (SIL OFL, `composeResources/font`, licence in `/licenses`).
- Headlines ExtraBold; display styles Black *italic* (hero text, race clock, positions).
- **Numbers use `Num.style(size, weight, italic)`**: tabular figures (`tnum`), so live times don't jitter.
- Labels: `labelSmall` uppercase with 1.6 sp tracking.

## Shape & spacing
- Cards 22 dp radius (stripe cards 20), tiles 12–14, chips 6–8, pills fully rounded.
- Screen padding 18 dp, list spacing 12 dp, card padding 14–20 dp.
- Touch targets ≥ 44–48 dp.

## Components (`ui/components`)
`ScreenScaffold` (glow + header + one LazyColumn) · `BrandWordmark` / `ChequeredMark` · `RtCard` · `StripeCard` · `SectionLabel` (red tick + caps) · `Tag` · `StatTile` · `PillTabs` · `PrimaryButton` (gradient, caps) · `SecondaryButton` · `Stepper` · `RtSlider` · `RacerAvatar` · `AvatarStack` · `PositionBadge` · `DateDivider` / `TimelineItem` · `EmptyState` · `KeyValue` · charts (`LineChart` with area fill for single series, `BarList`).

Live screen (`LiveLayout`): clock HUD (red label block + italic clock), three start lights, broadcast lower-third for each lap, timing tower (position · colour stripe · 3-letter code · last lap · gap/best).

## Motion
Fades between screens; lap banner slides in from the left and stays 3.2 s; pills animate text colour. No looping animations except loading.

## Anti-patterns
- No raw `Color(...)`/`sp` in screens beyond the tokens above.
- No monospace fonts – use `Num.style`.
- Don't use red for "good" states; purple = fastest, green = PB.

## Screenshots
`./gradlew :composeApp:testDebugUnitTest --tests '*Screenshot*'` renders every screen with demo data to `composeApp/screenshots/`.
