# Design preferences

**Authoritative:** `docs/ARCHITECTURE.md` (UI sections) and the code in `ui/`.

## Dashboard layouts
- **The knowledge level picks the dashboard:**
  - Everyday → Companion (built);
  - DIY → Workshop (proposed);
  - Advanced → Telemetry (proposed).
- **Home layout:** **Easy** (Penelope's arrangement) or **Customizable** (the user's shortcuts and order). Stored per level, so switching level never erases a customization.
- **Required shortcuts:** **Car Health** and **Extras** can be moved but never hidden.
- **Everyday dashboard, top to bottom:** header with the car name and connection status; large orb; greeting and "How can I help with your car?"; a plain-language health summary; quick actions (What's wrong with my car?, Check engine light, Maintenance, Ask Penelope).
- **Design references:** images the owner shared (orb-centered home, car-health summary, dark backgrounds with glowing blue/teal light).

## Visual requirements
- **Dark by default.** Styles: **Midnight** (default: near-black navy, cyan, teal and blue light) and **Amethyst** (black and purple). Light mode and "Match phone" are also available.
- **The voice orb is the centerpiece.** It shows the **real** voice state, with a distinct shape per state. Listening follows the real microphone level; speaking uses a steady animation, never a fake waveform.
- **Panels:** soft, rounded, glassy. No stock car images; users can add their own photo.
- **Version label:** shown on the home screen in debug builds only, and in Settings → About.

## Wording
- **Everyday level:** short, plain sentences. No codes, addresses, protocols or raw errors.
- **Honest:** "All clear" only after a real code read; sample data always labeled; missing data explained, never guessed.
- **Connection steps say exactly:**
  1. "Looking for your car adapter."
  2. "Found your adapter."
  3. "Connecting to your vehicle."
  4. "You're connected."
  5. On failure: "I couldn't connect. Let's try again."

## Conversation screen
- **Before the first message:** the introduction, with instructions and suggestions.
- **Once messages exist:** a compact voice bar (small orb, status, 48 dp mic/pause/end), and messages get the rest of the screen.
- **Scrolling:** reading older messages is never interrupted; a "New messages" button jumps to the latest.
- **Clear:** clearing the transcript brings back the introduction.

## Hands-free interaction
- **Continuous conversation:** "goodbye", "stop" or "that's all" ends it; "pause" pauses.
- **The mic is off while Penelope speaks.** Notices wait for a pause in the conversation.
- **Driving:** everything works by voice; nothing requires looking at or tapping the screen.
- **Messages and calls** (when built): Penelope repeats the action, and the user says yes, before anything is sent or dialed.

## Accessibility (required)
- WCAG AA contrast in every theme and style (`UiPolishTest`).
- Touch targets at least 48 dp; TalkBack labels; polite live regions for status.
- **Reduce motion** makes everything static.
