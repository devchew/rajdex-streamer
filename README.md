# Rajdex Streamer

Android USB UVC preview for up to two cameras at once. Bulk MJPEG devices such
as the UGREEN 25854 use the Android USB Host stream path. Isochronous UVC
webcams use the included AndroidUSBCamera/libuvc implementation. Each camera
has its own resolution selector.

## Run

Open the project in Android Studio and run the `app` configuration on an Android
device with USB host/OTG support. Connect up to two UVC devices and approve the
camera and USB permission prompts. For an HDMI capture device, provide an active
HDMI source. The app starts at 640×480 when available; use each selector to
request a different mode.

The project includes AndroidUSBCamera 3.3.3 modules and native libraries for
UVC cameras that stream over isochronous USB endpoints.
