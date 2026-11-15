# Synthetic audio fixtures

`opus-20ms.bin` and `opus-20ms-reference.pcm` contain a generated 440 Hz test signal for comparing the Rust decoder with Concentus. They contain no microphone or user audio.

Windows null-password certificate compatibility is tested using a fresh key and certificate generated in memory during each test. No certificate private-key fixture is distributed or committed.

Audio fixtures are used only by tests, never by the released application.
