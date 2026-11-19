# UI and UX preferences

Details: `docs/ARCHITECTURE.md` (UI sections).

## Visual style
- **Dark by default.** Color styles: **Midnight** (default: near-black navy with cyan, teal and blue light) and **Amethyst** (black and purple). Light mode and "Match phone" stay available.
- **The voice orb is the centerpiece:** a glass sphere whose look follows the *real* voice state. Listening follows the real microphone level; speaking uses a steady animation, never a made-up waveform.
- **Soft, rounded, glassy panels.** No stock car images; users can add their own photo.
- Design references came from images the owner shared (an orb-centered home with a car-health summary).

## Wording
- **Everyday level:** short, plain sentences. No codes, addresses, protocols or raw errors.
- **Honest states:** "All clear" only after a real code read; sample data always labeled; missing data explained, never guessed.
- **Exact connection messages:** "Looking for your car adapter." / "Found your adapter." / "Connecting to your vehicle." / "You're connected." / "I couldn't connect. Let's try again."

## Voice behavior
- Continuous conversation: after Penelope answers, she listens again. "Goodbye", "stop" or "that's all" ends; "pause" pauses.
- Half-duplex: the mic is never open while she speaks. Status notices wait for a gap, never interrupting.
- Natural voice: rate and pitch default to 1.0; the user can choose the engine and voice.

## Accessibility (required)
- WCAG AA contrast in every theme and style (`UiPolishTest`).
- Touch targets at least 48 dp; TalkBack descriptions; polite live regions for status.
- Reduce motion makes everything static.
