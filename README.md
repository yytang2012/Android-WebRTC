# AndroidWebRTC

## WebRTC Live Streaming

An Android client for [ProjectRTC](https://github.com/yytang2012/ProjectRTC) and [WebRTCDemo](https://appr.tc/).

This project is ported from [webrtc.org](https://webrtc.org/native-code/android/),
and keeps all the features of the original source code and can be used as a client for [WebRTCDemo](https://appr.tc/).

For Study purpose, this project can also be used as a client for [ProjectRTC](https://github.com/yytang2012/ProjectRTC),
which demonstrates WebRTC video calls between androids and/or desktop browsers.
Currently, the mobile client and desktop server must be in the same subnet

Build with Android Studio. The Intellij IDEA version is in the master branch.
You can decide which client to use by choosing the checkbox in settings

## How To

You need [ProjectRTC](https://github.com/yytang2012/ProjectRTC) up and running, and it must be somewhere that your android can access.
(You can quickly test this with your android browser).

When you launch the app, you need to enter the destination in ip-address:port format

Your stream should appear as "android_test" in ProjectRTC, so you can also use the call feature there.

## Author

- [Yutao Tang](kissingers800@gmail.com)