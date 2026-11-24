# Notes for Claude

## Voice-over work (Arabic / Iraqi)

- Always use the approved Iraqi narrator voice defined in `voiceover-demo/voice_profile.json`: Gemini TTS `Charon` voice, model `gemini-3.8-flash-tts`, via Speko, with the Baghdadi narrator direction stored there. Do not switch voices unless the user asks.
- `voiceover-demo/iraqi_voice.mp3` is the reference take the user approved. Match new takes to it.
- Deliver audio files only (MP3 + WAV). Do not make a video unless the user asks for one.
