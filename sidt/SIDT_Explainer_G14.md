# SIDT Jury Explainer: SENTINEL (on-device detection and data poisoning for AI image, voices and music)

**Group G14: Yajat Vinay Ghule and Samarth S Shetty**

This is our cheat sheet in plain words. Every technical term from the report is explained simply, then each model gets an honest "can do, cannot do" list, then the design thinking stages and likely viva questions. The explanations are oversimplified on purpose, but we tried not to say anything false.

Quick map of this file:

1. The project in one breath
2. Glossary, grouped by theme
3. What each model can and cannot do
4. Design thinking stages
5. Likely jury questions with answers
6. Numbers to remember

---

## 1. The project in one breath

- Today pictures, voices and songs made by AI look and sound real, and most people cannot tell.
- Our app is like a warning light on your phone. It checks a picture, a voice or a song and says AI, Unsure or Human.
- Our second tool is like an invisible shield for artists. It changes a picture a tiny bit so AI image tools read it wrongly.
- Everything runs on your own phone or laptop, so your screen and your art are never sent to anyone's server.
- We also say honestly where it fails, because a warning light is not proof.

---

## 2. Glossary

Read the entry, then the "Where" line to see how it shows up in our project.

### 2.1 AI basics

**AI-generated content.** Pictures, voices or songs made by a computer program instead of a person.
Like a painting made by a robot arm. Where: this is the thing our detector looks for.

**Deepfake.** A fake picture, video or voice that looks or sounds like a real person or a real event. Like a very good forged signature. Where: the audio deepfake papers [7] to [10] and our voice check.

**Dead internet theory.** A joke idea that most of the web is bots talking to bots. Where: the first paragraph of the Introduction.

**Generative model.** A program that makes new things, such as pictures, voices or music, after learning from lots of examples. Like a student who studied thousands of paintings and now paints new ones. Where: the thing artists want to protect against.

**Generator.** The specific program that makes the fake content, for example one image maker or one voice engine. Like a brand of printer. Where: detectors often fail on generators they have not seen.

**Diffusion model.** An image maker that starts from a screen of random dots and slowly cleans it up into a picture. Like a sculptor who starts from a rough block of marble. Where: Stable Diffusion is one, and many surveyed papers detect its pictures.

**Stable Diffusion.** A popular free diffusion image maker. Where: our poisoner is built to confuse the part of it that reads pictures. SDXL is a bigger version mentioned in the Nightshade paper [14].

**Latent diffusion.** A diffusion model that does its work on a small, squeezed copy of the picture instead of the full picture, so it is faster. Like drawing on a small sketch pad and enlarging later. Where: Stable Diffusion is a latent diffusion model.

**GAN.** An older type of image maker where two programs compete, one making fakes and one trying to catch them. Like a forger and a detective training each other. Where: old detectors were built for GAN pictures and failed on newer ones [2].

**Text-to-image, text-to-music, text-to-speech (TTS).** You type words and the program gives you a picture, a song or a spoken voice. Like ordering from a menu by describing the dish. Where: Suno and Udio are text-to-music, Kokoro and SpeechT5 are TTS engines used to train our voice model.

**Suno and Udio.** Websites where users type a prompt and get a full song. Where: our music detector catches Suno well and often misses short Udio clips.

**Kokoro, SpeechT5, Meta MMS.** Three AI voice engines. Kokoro and SpeechT5 were used to train our voice detector. Meta MMS was kept as a surprise test. Where: voice model results.

**Peer-reviewed paper.** A research paper that other experts checked before it was published. Like a homework answer that was marked by several teachers. Where: we surveyed 25 of them.

**Benchmark and dataset.** A benchmark is a fixed exam used to compare programs. A dataset is the pile of examples. Where: CIFAKE, SingFake, FakeMusicCaps and others in the survey.

**Parameters.** The tiny adjustable numbers inside a model that it learns. More parameters usually means a bigger, heavier model. Where: AASIST-L has only 85k, so it is small.

**Foundation model.** A very large model trained on huge data that can be reused for many jobs. Like a general knowledge person who can be taught a new job quickly. Where: paper [10] found these detect fake speech better but are large.

**Few-shot fine-tuning.** Teaching an already trained model a new job using only a few examples. Where: paper [10].

**Train, test and held-out data.** **Train** means learn from examples. **Test** means check on examples it has not seen. **Held-out data** is the secret exam set kept away from training. Where: our voice model results.

**Generalise.** Still working on new things it was not trained on. Like a student who can solve a new type of question, not just the ones from the textbook. Where: the big weakness in most detectors and in our voice model on unseen engines.

**Shortcut learning.** When a model passes by using an accidental clue instead of the real skill. Like guessing the answer from the page number. Where: our first voice model learned the perfect silence in AI speech.

### 2.2 Detecting images, voices and music

**Classifier.** A program that puts things into groups. Ours puts a picture in "AI" or "not AI". Like a sorting machine at a post office. Where: the image detector.

**Vision transformer (ViT).** A kind of image model that looks at a picture as a grid of small tiles and decides how the tiles relate to each other. Like reading a jigsaw puzzle by how the pieces fit. Where: Step 4 of the method, the image detector.

**CNN (convolutional neural network).** An older type of image model that slides small filters over a picture to find patterns. Where: the CIFAKE paper [5] trained 36 of them.

**Neural network, deep network.** A program made of many layers of simple maths units that learns patterns from examples. Like a very large chain of tiny voting boxes. Where: all our models.

**Nearest neighbour search and linear probing.** Two simple ways to use an already trained model. Nearest neighbour asks "which known examples is this closest to". Linear probing puts a very simple decision layer on top. Where: paper [2].

**Vision-language model.** A model trained on pictures together with their captions. Where: paper [2] used its features.

**DIRE.** A trick that rebuilds a picture with a diffusion model and measures the difference. AI pictures rebuild well and real ones do not. Like checking a photocopy by copying it again. Where: paper [3]. It is too heavy for a phone.

**Synthbuster and frequency artefacts.** Hidden repeating patterns that AI pictures leave, found by looking at the picture as waves (Fourier transform). Like hearing a faint hum in a recording. Where: paper [4].

**CIFAKE and Grad-CAM.** CIFAKE is a set of small real and AI pictures. Grad-CAM is a heat map that shows where the model looked. Where: paper [5]. It found the model looked at small flaws in the background.

**Hierarchical detector.** A detector that decides in levels. First real or AI, then which family, then the exact generator. Where: paper [6].

**Hyperparameter tuning.** Trying different settings of a model to find the best one. Where: paper [5].

**mAP.** A score for how well a model ranks right answers above wrong ones. Higher is better. Where: paper [2] improved by 15.07 mAP.

**Anti-spoofing.** Catching fake voices that try to trick a voice-based system. Like a guard who checks if the person at the door is wearing a mask. Where: AASIST and ASVspoof papers.

**AASIST and AASIST-L.** A small anti-spoofing model that works on raw sound. The L version has only 85k parameters, so it is interesting for phones. Where: paper [7].

**ASVspoof.** A yearly contest where teams build fake voice detectors and are tested on the same data. Where: paper [8] on the 2021 round. Replay detection and compressed-speech detection did not carry over well.

**Logical access, physical access, replay.** Types of voice attacks in ASVspoof. Logical access means a computer-made voice sent digitally. Replay means playing a recording to a microphone. Where: paper [8].

**SONAR.** A test suite for AI speech from 9 voice platforms. Where: paper [10].

**SingFake.** A set of real and fake songs. It shows speech detectors do much worse on singing. Where: paper [11].

**CLAP embeddings.** A way to turn sound into a list of numbers that captures what it sounds like, learned from audio together with text. Where: paper [12].

**Resampling and kHz.** Changing how many sound samples are stored per second. Resampling to 22.05 kHz fooled one commercial music detector. Where: paper [12].

**FakeMusicCaps.** A set of AI music made by several text-to-music models. Where: paper [13].

**SONICS SpecTTTra.** The AI-music detector we use. It turns 5 seconds of sound into a picture of its frequencies (a spectrogram) and scores it. Where: Step 5, music check.

**wav2vec2-base.** A pretrained model that turns speech into useful numbers. We froze it up to its third layer and added a small decision part on top. Where: our own voice detector.

**Logistic regression.** A very simple method that turns numbers into a probability between yes and no. Like a see-saw that tips toward AI or Human. Where: the top of our voice model.

**Pooling over time.** Squashing many moments of sound into one summary. Like turning a whole movie into a one-line review. Where: our voice model.

**Frozen layers.** Parts of a model we do not change when training. Where: wav2vec2 up to the third layer.

**Window.** A short slice of audio scored at a time. Our voice check scores the last 4 seconds every 2 seconds. Music uses 5 second windows. Where: Step 5.

**Noise floor and digital silence.** Real recordings always have a faint hiss. AI voices can have perfect silence with no hiss at all. Where: the shortcut our first voice model learned.

**Splice.** Gluing a clip into the middle of another recording. Where: the test speech had an AI voice spliced in from 0:57 to 1:23.

**Timeline.** A bar chart showing which stretches of the speech sound AI. Where: Objective 4 and Test 1.

**Threshold (cutoff).** The score line where a label changes. Where: extension AI at 85% or more, Human below 60%. Phone AI only at 95% or more, songs 0.80 or more.

**Unsure.** Our honest third answer when the evidence is weak. Where: all detectors.

**Graph attention and stack node.** Parts inside AASIST that let the model link clues spread over different times and pitches of a sound. Like connecting dots on a map. Where: paper [7].

**Autoregressive model.** An image maker that builds a picture piece by piece, one after another. Where: paper [2] tested on these too.

**CIFAR-10, ImageNet, Million Song Dataset.** Well-known practice collections. CIFAR-10 and ImageNet hold pictures, and the Million Song Dataset holds real songs. Where: papers [5], [24] and [12].

**Perceptual hash and cache.** A short fingerprint of a picture so we do not check the same picture twice. Where: the speed idea in the Ideate stage became a cache of earlier verdicts.

**Overlay.** Coloured boxes or badges drawn on top of other apps. Where: the Android app.

### 2.3 Protecting images

**Data poisoning.** Sneaking changed examples into the data a model learns from so it learns the wrong thing. Like slipping a few wrong pages into a textbook. Where: Nightshade [14] and the surveys [18].

**Adversarial perturbation.** A tiny change to a picture that people hardly notice but that confuses a model. Like a small smudge that makes a robot misread a sign. Where: the poisoner.

**Nightshade.** A tool that makes poison pictures to take control of a prompt in a text-to-image model. Where: paper [14]. Fewer than 100 were enough in SDXL. It works only if the pictures reach a training set.

**Glaze.** A tool that artists use to protect their style from copying. Where: named as one of the cases behind the Empathise stage.

**Anti-DreamBooth and DreamBooth.** DreamBooth lets a model learn a person's face from a few photos. Anti-DreamBooth adds faint noise so that fails. Where: paper [15], tested only on faces.

**Style imitation and perceptual map.** Copying an artist's style. A perceptual map marks where the human eye notices changes so protection can hide there. Where: paper [16].

**Posterior Collapse Attack (PCA).** A way to protect images by attacking only the VAE encoder. Where: paper [17], close to what we do.

**VAE, encoder, latent space.** The VAE encoder is the part of the image maker that squeezes a picture into a short code. That code lives in latent space. Like turning a photo into a tiny shorthand note. Where: the poisoner aims at this encoder.

**TAESD.** A tiny 5 MB copy of that encoder. Where: our search uses it because the full encoder took 18 to 34 seconds per step.

**sd-vae-ft-mse.** The real Stable Diffusion encoder. Where: we measured results on it, though the search never saw it.

**PhotoGuard and Mist.** Published tools that protect images by attacking the encoder. Where: the poisoner builds on their idea.

**PGD (projected gradient descent).** A step-by-step search that nudges each pixel a little in the direction that confuses the model, never past a set limit. Like turning a key a bit at a time toward the best spot. Where: Step 9.

**Cloak and Scramble.** Our two methods. Cloak pulls the picture toward a fixed high-contrast pattern. Scramble pushes it away from itself. Where: Step 8.

**Light, Standard, Strong.** Our three strengths. Each pixel can change by at most 4, 8 or 16 out of 255. Where: Step 9.

**Cleaning attacks.** Tricks like heavy compression or blurring that can weaken this kind of protection. Where: Measured Results and Future Scope.

**Watermark.** A hidden signal in a picture or sound that says who made it. Like an invisible stamp. Where: papers [20] and [22]. Works only when the generator adds it.

**Stable Signature and Latent Watermark.** Two watermark methods built into latent diffusion image makers. Where: papers [20] and [22].

**SynthID.** A watermark reader idea from our brainstorm. Where: Ideate stage, not built.

**Provenance.** The history of a file: who made it and how it was edited. Like a receipt and past owners for a second-hand bike. Where: Step 3, and paper [21].

**C2PA and content credentials.** C2PA is a standard for attaching that history to a file. The attached tag is called content credentials. Where: Step 3. The extension shows a small CR chip on files that carry it.

**IPTC metadata and trainedAlgorithmicMedia.** IPTC is a standard set of labels inside a picture file. One label, trainedAlgorithmicMedia, says "made by AI". Where: Step 3. If the file says it, we call it AI with certainty.

**Metadata and EXIF.** Extra information stored inside a file. EXIF holds camera details such as GPS location. Where: social sites strip metadata, and the poisoner removes EXIF.

**Getty Images against Stability AI.** A court case where Getty said more than 12 million photos were copied. The UK court ruled mostly for Stability AI in November 2025. Where: Introduction.

**Anchoring bias.** People believe the first version they see is the original. Where: Empathise stage.

**Disruption, detection, authentication.** Three kinds of defence in survey [19]. Detection spots fakes. Disruption spoils the fake maker. Authentication proves where real content came from.

### 2.4 Measuring results

**Accuracy.** The share of answers that were right.

**False positive (false alarm).** A real thing wrongly called AI. Where: the real boat rescue photo called AI at 95%.

**Catch rate (recall).** The share of AI items we caught. Where: the voice model caught 31% of clips from an engine it had never seen.

**Precision.** Of the things we called AI, how many really were. Where: not reported as a single figure, we speak about false alarms instead.

**AUC.** A score from 0 to 1 for how well a detector ranks AI above real across all cutoffs. Like asking if the detector puts the AI ones near the top of the list. 0.5 is a coin flip and 1 is perfect. Where: extension 0.87, voice model 0.99 on clean audio.

**PSNR.** A number in dB for how close the protected picture is to the original. Higher means closer. Where: 35.0 dB at Standard.

**SSIM.** A score from 0 to 1 for how similar two pictures look. 1 means identical. Where: 0.978 at Standard.

**Latent shift.** How far the protected picture moved inside the encoder, compared to the original. 0 means the model sees the same picture. Where: Table 4.3.

**Random noise baseline.** A fair comparison using random noise of exactly the same size as our protection. Where: our protection is 4.3 times stronger at Standard.

**JPEG compression.** Shrinking a picture file by throwing away small details. Social sites do it. Where: the protection survived JPEG quality 85.

**Resizing and cropping.** Making a picture smaller or cutting it. Where: we tested halving the size.

**Pixel and resolution.** A pixel is one dot of a picture. Resolution is how many dots. Where: pictures of about 960 by 640 pixels, and 224 by 224 for the detector.

**dB (decibel).** The unit for PSNR. Where: Table 4.3.

**Mean of six.** The average over six real photographs. Where: Table 4.3.

### 2.5 The app and the tech it runs on

**On-device inference.** Running a trained model on your own phone or laptop, not on a distant server. Like cooking at home instead of ordering delivery. Where: the main idea of the whole project and the privacy promise.

**Screen capture, frame buffer and DLSS.** Screen capture means taking a picture of what is on screen. The frame buffer is where a screen's current picture is held. DLSS is a speed trick from video games. Where: the Ideate stage brainstorm. The speed idea became a cache.

**Cloud inference.** Sending the data to a server to be checked. Where: dropped in Ideate because it clashes with privacy.

**Edge AI.** AI that runs close to the user instead of in a far data centre. Where: survey [25].

**Quantization.** Storing a model's numbers with fewer bits so it becomes smaller and faster, sometimes with a small loss of accuracy. Like saving a photo at lower file size. Where: survey [24], and our image model is an 8-bit version.

**ONNX and ONNX Runtime.** ONNX is a common file format for models. ONNX Runtime is the engine that runs it. Like a PDF and a PDF reader. Where: Table 4.1, both extension and Android.

**transformers.js.** A library that runs models inside a web browser. Where: the Chrome extension.

**WebAssembly.** A way for a browser to run fast program code. Where: ONNX Runtime runs in WebAssembly in the extension.

**Chrome extension.** A small add-on program for the Chrome browser. Where: one of our three parts.

**Content script, service worker, offscreen page.** Three parts inside the extension. The content script reads the web page and finds images. The service worker passes messages. The offscreen page runs the model. Where: System Built.

**Android app and APK.** The app is the program for Android phones. An APK is the file you install it from. Where: our second part. The models are downloaded after install, about 285 MB once.

**Accessibility Service.** An Android feature meant to help users with disabilities by reading what is on screen. We use it to take a screenshot and crop each picture. Where: Step 2.

**Audio playback capture and MediaProjection.** The Android way for an app to listen to sound that other apps are playing, with the user's permission. Where: Phone audio mode. Chrome blocks it and calls cannot be captured.

**Vosk.** A small program that turns speech into text, offline. Where: the live transcript. It is the small English model.

**Speech-to-text and transcript.** Writing down what was said. Where: Step 5.

**Backend, API.** The backend is the program doing the heavy work behind the scenes. An API is the door other programs knock on to ask it for work. Where: the poisoner backend.

**Localhost and 127.0.0.1:8765.** An address that means "this same computer". Where: Step 8. The extension sends the picture there, so it never leaves the machine.

**Token.** A password-like code the backend asks for when it is opened to the home network. Where: the phone talks to the laptop this way.

**CPU and GPU.** The CPU is the computer's general brain. The GPU is a special fast chip for graphics and AI. Where: we ran everything on a laptop CPU with no GPU, which is why the protection takes minutes.

**Python, PyTorch, FastAPI.** Python is the programming language. PyTorch is the AI maths library. FastAPI builds the server. Where: poisoner backend.

**Emulator.** A pretend phone running on a computer. Where: Android 15 emulator tests.

**Debug build and commit.** A test version of the app, and a saved snapshot of the code. Where: Table 4.2.

**GitHub and open source.** A website to share code publicly, so others can check and repeat our results. Where: YajatGhule/ai-poisoner.

**SigLIP.** A newer image model that scored an AUC of 0.95 against 0.85 for our current one, but it is 327 MB. Where: Future Scope.

**IEEE, ACM, MDPI, Elsevier, Ubiquity Press.** The publishers of the papers. Where: Introduction.

---

## 3. Model capabilities

Be honest. The report says the image side is a hint, not proof.

### 3.1 Image detector

**What it is.** A vision transformer that looks at a 224 by 224 version of a picture and gives an AI score. It is an 8-bit ONNX model, 87 MB.

**What it can do.**
- Run fully on the phone or in the browser.
- Catch obvious AI pictures. On the phone one AI image scored 98%, and in the overlay test an AI image was caught at 99%.
- Say a real photo is "likely not AI" (36% in the file test).
- Read C2PA and IPTC tags first. If the file says AI, it is AI with certainty, with no guess.

**What it cannot do or gets wrong.**
- A real photo of a boat rescue was labelled AI at 95%.
- An AI illustration was labelled Human.
- In the extension's small test (20 AI and 16 real pictures) the AUC was 0.87. At the default setting it caught about half the AI images and wrongly flagged about 1 in 16 real photos.
- The phone only sees a shrunken cropped screenshot, which pushes real photos toward "AI". So the phone says AI only at 95% or more, and in a simulation on our 36-image test set it flagged 0% of real photos and caught about 45% of AI images.
- Social sites strip metadata, which weakens the exact check.

**In plain words.** Treat it like a smoke alarm that sometimes beeps for toast and sometimes sleeps through a small fire. A hint, never proof.

### 3.2 AI-voice detector

**What it is.** Our own model. It uses wav2vec2-base frozen up to the third layer, then pooling over time, then logistic regression. It was trained on 159 real clips and 239 clips from two AI engines, Kokoro and SpeechT5. It is 122 MB.

**What it can do.**
- Check a live voice or a file and show which stretches sound AI, with timestamps and a transcript.
- Find an AI voice inside a long speech. In the test, a 1 minute 58 second speech with an AI voice spliced in from 0:57 to 1:23 was located at 0:58 to 1:24, with 15% of 58 windows flagged.
- On held-out data it flagged none of the real voices, caught all 20 clips from unseen Kokoro voices, and reached AUC 0.99 on clean audio.
- A 2 minute speech took about 50 seconds to check.

**What it cannot do or gets wrong.**
- It caught only 31% of clips from an engine it had never seen (Meta MMS). Most of those land in Unsure.
- Our first version learned a shortcut, the perfect silence in AI speech. With a faint noise floor added its catch rate on unseen Kokoro voices fell from 100% to 50%. The second version trims silences and adds random noise to both classes, and gives the same answer with or without noise.
- Commercial voice cloning was not tested.
- The live voice test on the real phone did not produce a whole-session verdict (not verified). It worked on the emulator, where real windows scored 0.00 to 0.10 AI.
- Android does not let apps capture phone calls, and Chrome blocks audio capture. For a call, put it on speaker.

**In plain words.** Good at spotting the kinds of AI voices it has met. Weak against a new voice engine it has never heard.

### 3.3 AI-music detector

**What it is.** The SONICS SpecTTTra model. It scores 5 second windows of a song, and the app calls a song AI at 0.80 or more. It is 35 MB.

**What it can do.**
- Score a Suno track at 95% AI in our test.
- Say Unsure (58% AI) for a real public-domain track, instead of forcing a wrong answer.
- In a small check of 15 real and 39 AI songs, no real song scored above 0.65.
- Work on files, and through the microphone with the Music check.

**What it cannot do or gets wrong.**
- It often misses short Udio clips.
- It was not tested on mainstream commercial music.
- The microphone check is less accurate than a file check, because room sound and phone microphones change what the model hears.
- "Unsure" means unknown, not "probably real".

### 3.4 Speech-to-text (Vosk)

**What it is.** Vosk, a small offline speech-to-text program, the small English model, 40 MB.

**What it can do.**
- Write a live transcript, and a transcript for a file, with no internet needed.
- Sit beside the voice detector so you can read what was said while the timeline shows which parts sound AI.

**What it cannot do.**
- It is a small English model. The report gives no accuracy number for it, so we do not claim one.
- It does not decide if the voice is AI. That is the job of the voice detector.

### 3.5 Poisoner and protector

**What it is.** A local program on a laptop CPU, with no GPU, that adds a tiny change to a picture. The change is searched with PGD against TAESD, and it moves the picture away from what the Stable Diffusion image encoder expects. Pictures are never uploaded.

**What it can do.** Real numbers on the real Stable Diffusion encoder, mean of six photographs of about 960 by 640 pixels:

| Setting | Biggest pixel change | Time | PSNR | SSIM | Times stronger than same-size noise |
|---|---|---|---|---|---|
| Light, Cloak | 4 of 255 | 39 s | 40.9 dB | 0.994 | 6.9 |
| Standard, Cloak | 8 of 255 | 97 s | 35.0 dB | 0.978 | 4.3 |
| Strong, Cloak | 16 of 255 | 132 s | 29.2 dB | 0.939 | 3.2 |
| Standard, Scramble | 8 of 255 | 81 s | 35.5 dB | 0.971 | 6.5 |

- Plain meaning: at Standard the picture still looks very close to the original, and inside the encoder it moves 4.3 times more than random noise of the same size would move it.
- About 95% of the effect measured on TAESD carried over to the real encoder.
- Almost all of it survived JPEG quality 85 and halving the picture size.
- Scramble moved the picture furthest (latent shift 0.83).
- Raising the steps from 40 to 100 on one photo changed the shift by less than 3%. So the limit is how much the picture may change, not search time.
- The server refuses ordinary websites, and 21 automated tests pass.

**What it cannot do or gets wrong.**
- It is a modest protection, not a strong one.
- At Standard the encoder still rebuilds a clearly recognisable picture, with faint colour blotches.
- At Strong, a repeating pattern appears in the rebuilt picture, and a person can see grain in flat areas (PSNR 29 dB).
- It was measured at the encoder only. No image generator was trained or run on protected pictures, because there was no GPU. So the effect on real style copying or editing is not proven.
- Cleaning, heavy compression or blurring can weaken this kind of protection.
- It is slow. Protection takes 39 to 132 seconds per picture.

**In plain words.** A shield that has passed the lab test on the part that reads pictures, but has not yet been tested against a whole image maker in action.

---

## 4. Design thinking stages in our project

**Empathise.** We asked who is hurt by the AI image boom. Artists get no credit when a model copies their style. Anchoring bias makes people think the AI copy is the original. For viewers the worry is simply not knowing what is real. We did not run a formal survey or interviews, so this stage rests on our own use of social media, the Glaze and Nightshade cases and the Getty lawsuit.

**Define.** We turned this into one question. How can a viewer see what is machine-made, and a creator push back, without anyone's screen or artwork being sent to a cloud server? The three problem statements come from this question and from the 25 papers.

**Ideate.** We listed six building blocks. They were screen capture, C2PA identity data, a watermark reader such as SynthID, computer vision models, cloud inference and a speed-up idea borrowed from DLSS in games. We dropped cloud inference for privacy, did not build SynthID reading, and the speed idea became a cache of earlier verdicts. For protection, the idea was to make PhotoGuard and Mist style attacks practical on a laptop.

**Prototype.** We built three parts. They are a Chrome extension, an Android app and a local poisoner backend. Everything is free and public on GitHub.

**Test.** We tested on real files on an iQOO Z5 phone (Android 13), an Android 15 emulator and a ThinkPad T490 laptop with no GPU. We reported every failure, including the real photo called AI at 95%.

---

## 5. Likely jury and viva questions

**Q1. Why did you choose this problem?**
Because pictures, voices and songs made by AI now sit next to real ones and most people cannot tell the difference. Artists also see their style copied with no credit. Both problems hurt ordinary people.

**Q2. How did you use design thinking?**
We went through Empathise, Define, Ideate, Prototype and Test. Empathise gave us the artist and viewer problems. Define gave one guiding question. Ideate gave us a list of ideas and we dropped some. Then we built and tested on real devices.

**Q3. Did you interview users?**
No. We did not run a formal survey or interview panel. The Empathise stage rests on our own social media use and on public cases such as Glaze, Nightshade and the Getty lawsuit. We say so openly in the report.

**Q4. Why does everything run on the device?**
For privacy. Your screen, voice notes, songs and artwork never leave your phone or laptop. We dropped cloud inference during Ideate for exactly this reason.

**Q5. Why not just use an existing detector?**
The 25 papers showed that most detectors check only one kind of media, lose accuracy on generators they have not seen, are tested on clean data and are often too heavy for a phone. We also tested three public voice detectors. None was usable. One called 15 of 16 real voices AI, and the other two caught none of the AI voices.

**Q6. How accurate is it?**
It depends on the model. The image side is a hint. In our small test the AUC was 0.87. The voice model had an AUC of 0.99 on clean audio, but caught only 31% of an engine it had never seen. We never claim it is proof.

**Q7. What is the worst failure you found?**
A real photograph of a boat rescue was called AI at 95% on the phone. That is why the report says a verdict must never be used alone to accuse someone.

**Q8. What does "Unsure" do for you?**
It stops the tool from forcing a yes or no when the evidence is weak. For example a real track got Unsure (58% AI). A wrong confident answer is worse than an honest "I do not know".

**Q9. What is the provenance check and why does it come first?**
Before any model runs, we read the C2PA and IPTC tags in the file. If the file itself says it is AI-made, we label it AI with certainty and need no guess. The weakness is that social sites strip metadata.

**Q10. How does the poisoner work in simple words?**
It makes a tiny change to the picture, at most 8 out of 255 per pixel at Standard. That change is searched so that the part of Stable Diffusion that reads pictures, the encoder, sees something quite different. People still see almost the same picture.

**Q11. Does the poisoner actually stop AI copying your art?**
We do not know yet. We only measured the shift inside the encoder, where it was 4.3 times stronger than random noise of the same size at Standard. We did not train or run any image generator on protected pictures because we had no GPU. So we call it a modest protection, not a strong one.

**Q12. Why does it take so long, and what about quality?**
We ran on a laptop CPU with no GPU, so it takes 39 to 132 seconds per picture, about one and a half minutes at Standard. At Standard the quality stays high (PSNR 35.0 dB, SSIM 0.978). At Strong, a person can see grain in flat areas.

**Q13. Is it cheating to use TAESD for the search?**
No, and we checked. TAESD is a small 5 MB copy of the encoder, because the full one took 18 to 34 seconds per step. But we measured every result on the real encoder (sd-vae-ft-mse), which the search never saw. About 95% of the effect carried over.

**Q14. Can the protection be removed?**
Possibly. Our protection survived JPEG quality 85 and halving the size, but cleaning, heavy compression or blurring can weaken protections of this kind. We did not test those attacks. It is in our Future Scope.

**Q15. Is it ethical to build a tool that protects images from AI?**
The tool is meant for owners who want to protect their own work before posting, and it runs locally. We also say clearly where it fails. The related Nightshade paper presents this kind of tool as a defence for content owners against scrapers that ignore opt-out requests.

**Q16. What would you do next?**
Test the poisoner against a real image generator on a GPU, add more voice engines and real phone recordings to the voice training, try a stronger image model such as SigLIP, and move the poisoner onto the phone.

---

## 6. Numbers to remember

| Number | What it means |
|---|---|
| 25 papers | Peer-reviewed studies we surveyed, from 2021 to 2026 |
| 98% and 95% | AI image score and Suno song score on the phone |
| 95% | A real boat rescue photo wrongly labelled AI. Our worst failure |
| 0.87 | AUC of the image detector in the extension's small test |
| 0.99 and 31% | Voice model AUC on clean audio, and its catch rate on an engine it had never seen |
| 15% of 58 windows | The AI stretch found in the 2 minute speech, at 0:58 to 1:24 |
| 35.0 dB and 0.978 | PSNR and SSIM at Standard, picture stays close to the original |
| 4.3 times | Standard Cloak shift against same-size random noise, inside the real encoder |
| 97 seconds | About one and a half minutes per picture at Standard on a laptop CPU |
| 285 MB | The one-time model download for the phone |

One last line to say: a verdict from our tool is a warning sign, never proof on its own.
