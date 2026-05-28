package dev.lenscast.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Detection + deep-link for Android's system DeviceAsWebcam feature (Android 14+,
 * Pixel 8 and a handful of other OEMs). When the system service is present, the OS
 * exposes the phone as a USB UVC webcam — the frames come from the system camera,
 * not from Lenscast. We can only nudge the user into the right Settings screen;
 * the AIDL needed to push our own frames in (`IDeviceAsWebcam`) is @SystemApi and
 * unreachable from third-party apps.
 */
object SystemWebcam {

    private const val DEVICE_AS_WEBCAM_PKG = "com.android.DeviceAsWebcam"

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching {
            context.packageManager.getPackageInfo(DEVICE_AS_WEBCAM_PKG, 0)
            true
        }.getOrDefault(false)
    }

    // Action string for the USB Preferences screen. Not a public SDK constant on every
    // platform version, so spelled out raw — the screen itself has existed since at
    // least API 31, well below our minSdk 26 / DeviceAsWebcam's API 34 floor.
    private const val ACTION_USB_PREFERENCES = "android.settings.USB_PREFERENCES"

    fun openUsbSettings(context: Context) {
        val primary = Intent(ACTION_USB_PREFERENCES).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(primary)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
