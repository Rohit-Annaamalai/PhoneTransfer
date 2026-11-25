# PhoneTransfer

Move files between an Android phone and a Windows laptop over the phone's own Wi-Fi hotspot.
The phone app runs a small web server; the laptop just opens a web page. Nothing to install on the laptop.

## Build and install (one time, on the laptop)

1. Install **Android Studio** (free).
2. `File > Open` and choose this `PhoneTransfer` folder. Wait for Gradle sync to finish.
3. On the Nord 4: `Settings > About phone`, tap **Build number** 7 times, then
   `Settings > System > Developer options` and turn on **USB debugging**.
4. Plug the phone in with USB, accept the prompt on the phone, pick it at the top of Android Studio and press the green **Run** button.
   (Or `Build > Build APK(s)`, copy the APK to the phone and install it.)

## Everyday use

1. Phone: turn on **Hotspot**. In the hotspot settings choose the **5 GHz** band if you can.
2. Laptop: connect to the phone's hotspot Wi-Fi.
3. Phone: open PhoneTransfer and tap **Start sharing**.
4. Laptop: open a browser and type the address shown on the phone (for example `http://192.168.43.1:8765`).
5. Drag files onto the page to send them to the phone, or tap **Choose files to send to laptop** on the phone and click **Download** on the laptop page.

Received files are saved on the phone in `Downloads/PhoneTransfer`.

## Notes

- Only devices connected to your hotspot can reach the page, and the hotspot password protects it. Don't use this on public Wi-Fi you don't trust.
- Speed depends on the hotspot band and how close the devices are. 5 GHz is much faster than 2.4 GHz.
- The page works the same if both devices are on the same home Wi-Fi.
- The app has no custom icon yet (Android shows a default one).
