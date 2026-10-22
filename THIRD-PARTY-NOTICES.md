# Third-party notices

The MIT license in `LICENSE` covers original code in this repository. It does not relicense third-party components. `gradlew` and `gradlew.bat` are Gradle wrapper scripts, SPDX-License-Identifier Apache-2.0, and are not original MIT code. The wrapper JAR is an upstream Gradle component. Neither is embedded in the APK.

OpenCode CLI is not in the APK. The installer downloads pinned official archives onto the user's Linux host. Those binaries keep their upstream license.

Picker and WebView behavior was adapted from [anthracite-labs/OpenCode-Android](https://github.com/anthracite-labs/OpenCode-Android), which is MIT, Copyright (c) 2026 anthracite-labs. Its local runtime, native libraries, and auth-token URL handling were not copied.

## Shipped in the APK

| Component | Version | License |
| --- | --- | --- |
| App code | — | MIT, this repository |
| Adapted OpenCode-Android picker/WebView policy | — | MIT, Copyright (c) 2026 anthracite-labs |
| [mwiede/jsch](https://github.com/mwiede/jsch) | 2.28.8 | BSD-3-Clause, including bundled JZlib and jBCrypt |
| [Bouncy Castle](https://www.bouncycastle.org) `bcprov-jdk18on` | 1.86 | MIT |
| AndroidX activity, appcompat, core, lifecycle | 1.13.0, 1.7.1, 1.19.1, 2.11.0 | Apache-2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.13.0 | Apache-2.0 |
| kotlinx-coroutines-android | 1.11.0 | Apache-2.0 |
| Kotlin standard library, pulled by the Android Gradle plugin | AGP 9.4.1 default | Apache-2.0 |

JUnit 4.13.2, Apache MINA SSHD 2.20.0, and slf4j-nop 2.0.16 are test-only dependencies. They are not shipped in the APK. OpenCode CLI binaries and native toolchains are not packaged either.

JSch's Android build reports Java 8 and drops multi-release classes, so Ed25519 and X25519 run through JSch's Bouncy Castle lightweight classes (`org.bouncycastle.crypto`). That is why `bcprov-jdk18on` is a direct dependency. The app does not register Bouncy Castle as a JCE provider and does not move it ahead of Android's providers. Android Keystore AES-GCM uses `AndroidKeyStoreBCWorkaround` or `AndroidOpenSSL` when those providers exist. ConnectBot's SSH library is not included.

`assets/THIRD-PARTY-NOTICES.md` and `assets/LICENSE` are copies of the repository notices so the APK carries them. Jar signature files are excluded from the APK because they are not license texts. License and notice resources from dependencies are merged rather than discarded.

## JSch

Source: https://github.com/mwiede/jsch, release `jsch-2.28.8`. The artifact is `com.github.mwiede:jsch:2.28.8`.

```text
JSch 0.0.* was released under the GNU LGPL license. Later, we have switched
over to a BSD-style license.

Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft,Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

  1. Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.

  2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in
     the documentation and/or other materials provided with the distribution.

  3. The names of the authors may not be used to endorse or promote products
     derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT,
INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## JZlib, bundled in JSch

```text
JZlib 0.0.* were released under the GNU LGPL license. Later, we have switched
over to a BSD-style license.

Copyright (c) 2000-2011 ymnk, JCraft,Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

  1. Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.

  2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in
     the documentation and/or other materials provided with the distribution.

  3. The names of the authors may not be used to endorse or promote products
     derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT,
INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## jBCrypt, bundled in JSch

```text
Copyright (c) 2006 Damien Miller <djm@mindrot.org>

Permission to use, copy, modify, and distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## Bouncy Castle

Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Apache-2.0 components

AndroidX, Material Components for Android, Kotlin, kotlinx-coroutines, and the Gradle wrapper are under the Apache License, Version 2.0. The full license is at https://www.apache.org/licenses/LICENSE-2.0.

```text
Copyright notices for those components are in their upstream artifacts.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
