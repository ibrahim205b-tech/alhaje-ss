# Iraqi Arabic Voice-Over Demonstration (15 s)

A voice-quality test, not an advertisement. The only sound in the video is the voice.

| File | What it is |
|---|---|
| `iraqi_voice_demo.mp4` | Final video: 1920×1080, 30 fps, H.264 High, exactly 15.000 s, AAC 320 kb/s 48 kHz |
| `iraqi_voice_master_48k24.wav` | Mastered voice only: 48 kHz / 24-bit, exactly 15.000 s |

## Script (Baghdadi Iraqi, 36 words)

> تدري شنو؟ الدنيا ما تسوى بلا ناس طيبين.
> ساعات كلمة حلوة، أو إيد تنمدّ بوكت ضيگ، تغيّر يوم كامل لواحد تعبان.
> إحنا ما نعيش بس لنفسنا… نعيش حتى نخفّف عن غيرنا.
> والخير، صدگني، ما يضيع أبد.

Iraqi markers used on purpose: شنو، تسوى، إيد، بوكت (b-wakit)، ضيگ (ḍīg)، إحنا، بس، حتى (meaning "so that")، صدگني (ṣaddigni)، أبد.
The text sent to the voice engine spells qaf as **گ** in ضيگ and صدگني. That pushes the engine toward the Iraqi g sound instead of the Modern Standard Arabic q.

## Voice research

Every test used the full script. No service available here offers a native Iraqi **male** voice:

| Service / voice | Result |
|---|---|
| Hamsa "Fatma", "Lyali" (labelled Iraqi) | Female only; 16 kHz output (not broadcast quality). Fatma rushed the script in 12.3 s with no pauses. |
| Hamsa "Adam" (labelled male) | Measured median pitch 271 Hz, which is a female voice; 16 kHz output. |
| Azure (Iraqi locale ar-IQ) | Routed to a multilingual "DragonHD" female voice (233 Hz), not an Iraqi voice. |
| ElevenLabs "Eid" | Locked on this account (needs a credit top-up), so not tested. |
| Munsit (about 85 Arabic male voices) | Emirati, Najdi, Kuwaiti, Hijazi, Qatari, Omani and MSA only. None is Iraqi, so none was used. |
| OpenAI gpt-4o-mini-tts "Cedar" + Iraqi direction | Male (131 Hz) but 19.8 s long. The recognizer heard ساعات as "سعاد", a pronunciation slip. |
| Gemini TTS: Algenib, Orus, Sadaltager, Charon + Iraqi direction | All male. Gemini follows dialect direction best. The engine re-samples every take, so 9 Gemini takes were made and compared. |

**Selected: Gemini TTS (gemini-3.8-flash-tts), voice "Charon", directed as a Baghdadi documentary narrator.**
This is the deepest and steadiest take tested (median pitch 99 Hz, pitch spread 4.3 semitones; Sadaltager and Algenib swung 5–7 semitones). It has every word right and a g-type qaf.

## Verification

- **Words:** two independent Arabic recognition engines (Gemini and Meta) returned every word of the script correctly. The Meta engine wrote ضيگ as **ضيج**, meaning it heard a g/j-type qaf, not Modern Standard q. Gemini's recognizer writes in standard spelling, so it says nothing about qaf.
- **Timing:** the raw take was 16.8 s. Pauses were shortened, never removed: about 0.4 s between sentences and about 0.3 s at commas. Breaths kept. No time-stretch or pitch change. The voice runs 0.25 s → 14.7 s inside exactly 15.000 s.
- **Mastering:** 24 → 48 kHz high-precision resample, 65 Hz high-pass, gentle 2:1 compression, then a limiter.
  - Loudness: −16.3 LUFS integrated, 3.6 LU range (consistent level).
  - Peaks: −2.3 dBTP true peak in the master; the AAC audio decodes to a −5.2 dBFS peak. No clipping.
  - Silence: digital silence before and after the voice, with no hiss.
- **Video:** 450 frames at 1920×1080. Near-black warm-neutral field in a 2.39:1 letterbox, with one soft light slowly drifting and fine film grain (so no banding). Fades in from black and out to black. No text, no subtitles, no music, no sound effects.

## Honest limitations

- **Nobody has listened to this audio.** Verification is measurement plus speech recognition. Before the voice is used for real work, a native Iraqi listener should confirm the accent.
- **Charon is a multilingual voice acted into Iraqi dialect**, not a native Iraqi speaker's voice. Expect good Iraqi word choice and some Iraqi pronunciation (the g-type qaf). Some vowels or intonation may still lean toward a general "Arabic" or Modern Standard colour.
- **The best route to a truly authentic Iraqi male voice** is a professional Iraqi narrator, or cloning a consenting Iraqi narrator's voice (about 1–3 minutes of clean audio) on ElevenLabs or a similar service. Neither was available in this session.
