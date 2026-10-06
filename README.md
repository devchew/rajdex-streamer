# Rajdex Streamer

Android USB UVC capture preview for an HDMI grabber such as the UGREEN 25854.
The current app shows one USB stream and lets you switch between MJPEG modes
reported by the connected device. The stream currently runs at 30 fps.

## Run

Open the project in Android Studio and run the `app` configuration on an Android
device with USB host/OTG support. Connect the grabber, approve USB access, and
provide an active HDMI source to the capture device. The app starts with
640×480 when available; use the resolution selector to request another mode.

The project includes the AndroidUSBCamera 3.3.3 modules and native libraries.
The current preview path uses Android USB Host and UVC control transfers so it
can negotiate the bulk MJPEG stream exposed by this grabber.
