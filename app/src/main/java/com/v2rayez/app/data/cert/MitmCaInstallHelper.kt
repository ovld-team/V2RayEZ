package com.v2rayez.app.data.cert

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.security.KeyChain
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * One step of the on-screen "how to install the CA" walkthrough.
 */
data class GuideStep(
    val title: String,
    val body: String,
    val titleRes: Int? = null,
    val bodyRes: Int? = null
)

/**
 * Preferred install strategy for the current OS. Android 11+ rejects in-app CA install via
 * [KeyChain.createInstallIntent] with "Can't install CA certificates … must be installed in
 * Settings"; we therefore prefer viewing the cert file / Settings fallbacks on R+.
 */
enum class MitmCaInstallMethod {
    /** Pre-R KeyChain installer (works on many older devices). */
    KEYCHAIN,
    /** ACTION_VIEW on a FileProvider .crt/.cer URI (opens OEM cert sheet when available). */
    VIEW_CERT,
    /** Manual: Settings → Install a certificate → CA certificate (+ optional Downloads file). */
    SETTINGS_MANUAL
}

/**
 * Result of [MitmCaInstallHelper.prepareInstall]: primary [intent] to launch, plus extras for
 * UI fallbacks (security settings, downloads path, guide copy).
 */
data class MitmCaInstallPlan(
    val method: MitmCaInstallMethod,
    /** Best-effort Intent to launch now (may still land on Settings on some OEMs). */
    val intent: Intent?,
    /** Opens Security / Encryption & credentials settings. */
    val securitySettingsIntent: Intent,
    /** Display name / path hint when the cert was written to Downloads (null if not). */
    val downloadsDisplayName: String?,
    /** Public content Uri in Downloads (MediaStore) when export succeeded. */
    val downloadsUri: Uri?,
    val guideSteps: List<GuideStep>,
    /** True when R+ policy means KeyChain will show the "Can't install CA" dialog — skip it. */
    val keyChainBlockedByOs: Boolean
)

/**
 * Builds install Intents and Downloads exports for the MITM CA.
 *
 * Prefer installing for the user when the OS allows it; on Android 11+ always provide a working
 * Settings + file-picker path because the platform blocks silent / KeyChain CA installs.
 */
object MitmCaInstallHelper {

    const val CA_DISPLAY_NAME = "V2RayEz MITM CA"
    const val DOWNLOADS_CRT_NAME = "V2RayEz-MITM-CA.crt"
    const val DOWNLOADS_CER_NAME = "V2RayEz-MITM-CA.cer"
    private const val CACHE_DIR = "mitm-certs"
    private const val MIME_CA = "application/x-x509-ca-cert"
    private const val MIME_PKIX = "application/pkix-cert"

    /**
     * Prepare the best install path for [context]. Also copies PEM+DER into cache and (when
     * possible) Downloads so Settings → Install a certificate can pick the file.
     */
    fun prepareInstall(context: Context): MitmCaInstallPlan? {
        val crt = MitmCaStore.crtFile(context)
        if (!crt.exists()) return null
        val pemBytes = crt.readBytes()
        val cert = CertInstaller.parse(pemBytes) ?: return null
        val derBytes = cert.encoded

        // Cache both PEM .crt and DER .cer for ACTION_VIEW / share.
        val cacheCrt = writeCacheFile(context, "V2RayEz-MITM-CA.crt", pemBytes)
        val cacheCer = writeCacheFile(context, "V2RayEz-MITM-CA.cer", derBytes)

        val downloadsUri = runCatching {
            saveToDownloads(context, DOWNLOADS_CRT_NAME, MIME_CA, pemBytes)
        }.getOrNull()
        // Also drop a DER copy — some OEMs only accept .cer from the file picker.
        runCatching { saveToDownloads(context, DOWNLOADS_CER_NAME, MIME_CA, derBytes) }

        val keyChainBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val viewIntent = viewCertIntent(context, cacheCrt) ?: viewCertIntent(context, cacheCer)
        val keyChainIntent = if (!keyChainBlocked) systemInstallIntent(derBytes) else null

        // Prefer installing for the user when the platform allows it.
        // On R+ KeyChain only shows "Can't install CA certificates" — skip it.
        // ACTION_VIEW often opens an OEM sheet; otherwise open Security settings after
        // staging the file in Downloads for the file picker.
        val (method, primary) = when {
            keyChainIntent != null -> MitmCaInstallMethod.KEYCHAIN to keyChainIntent
            viewIntent != null -> MitmCaInstallMethod.VIEW_CERT to viewIntent
            else -> MitmCaInstallMethod.SETTINGS_MANUAL to securitySettingsIntent()
        }

        return MitmCaInstallPlan(
            method = method,
            intent = primary,
            securitySettingsIntent = securitySettingsIntent(),
            downloadsDisplayName = if (downloadsUri != null) "Downloads/$DOWNLOADS_CRT_NAME" else null,
            downloadsUri = downloadsUri,
            guideSteps = installGuideSteps(keyChainBlocked),
            keyChainBlockedByOs = keyChainBlocked
        )
    }

    /**
     * KeyChain CA-install intent. Prefer DER ([X509Certificate.getEncoded]). On Android 11+
     * launching this usually shows "Can't install CA certificates" — callers should use
     * [prepareInstall] instead, which skips it on R+.
     */
    fun systemInstallIntent(certBytes: ByteArray): Intent? {
        val cert = CertInstaller.parse(certBytes) ?: return null
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
            putExtra(KeyChain.EXTRA_NAME, CA_DISPLAY_NAME)
        }
    }

    /** ACTION_VIEW on a FileProvider Uri so the OEM certificate installer can open it. */
    fun viewCertIntent(context: Context, file: File): Intent? {
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_CA)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri)
            // Some OEMs resolve via pkix-cert; keep primary MIME and let PackageManager match.
            putExtra(Intent.EXTRA_STREAM, uri)
        }.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_PKIX)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("", uri)
            }.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    fun securitySettingsIntent(): Intent =
        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the device's Security settings screen. */
    fun openSecuritySettings(context: Context) {
        runCatching { context.startActivity(securitySettingsIntent()) }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Deep-links to the trusted-credentials / user-CA list when the OS exposes one (best effort).
     */
    fun openTrustedCredentialsSettings(context: Context) {
        val intent = Intent("com.android.settings.TRUSTED_CREDENTIALS_USER").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure { openSecuritySettings(context) }
    }

    /**
     * Writes PEM bytes to public Downloads. Returns the MediaStore/content [Uri], or a file://
     * Uri on older APIs. Requires no runtime permission on API 29+ (MediaStore).
     */
    fun saveToDownloads(
        context: Context,
        displayName: String,
        mime: String,
        bytes: ByteArray
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, displayName)
            FileOutputStream(out).use { it.write(bytes) }
            Uri.fromFile(out)
        }
    }

    /** Save the current CA PEM into Downloads; returns display hint or null. */
    fun exportCrtToDownloads(context: Context): String? {
        val crt = MitmCaStore.crtFile(context)
        if (!crt.exists()) return null
        val uri = saveToDownloads(context, DOWNLOADS_CRT_NAME, MIME_CA, crt.readBytes()) ?: return null
        // Best-effort DER twin for picky file pickers.
        runCatching {
            val der = CertInstaller.parse(crt.readBytes())?.encoded ?: return@runCatching
            saveToDownloads(context, DOWNLOADS_CER_NAME, MIME_CA, der)
        }
        return "Downloads/$DOWNLOADS_CRT_NAME (${uri})"
    }

    fun installGuideSteps(android11Plus: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R): List<GuideStep> {
        val steps = mutableListOf(
            GuideStep(
                title = "Generate the local CA",
                body = "V2RayEz creates a private root certificate on this device. The private key never leaves your phone."
            )
        )
        if (android11Plus) {
            steps += GuideStep(
                title = "Save or open the certificate file",
                body = "Android 11+ blocks in-app CA install. Tap Install to open the cert, or Save to Downloads " +
                    "($DOWNLOADS_CRT_NAME)."
            )
            steps += GuideStep(
                title = "Install via Settings",
                body = "Settings → Security → Encryption & credentials → Install a certificate → CA certificate. " +
                    "Pick $DOWNLOADS_CRT_NAME (or the .cer file). Confirm with your PIN/pattern."
            )
        } else {
            steps += GuideStep(
                title = "Tap Install on this device",
                body = "Android asks for your PIN/pattern/biometric, then shows \"$CA_DISPLAY_NAME\". Confirm to add it."
            )
        }
        steps += GuideStep(
            title = "Verify under USER credentials",
            body = "Settings → Security → Trusted credentials → USER should list \"$CA_DISPLAY_NAME\"."
        )
        steps += GuideStep(
            title = "Confirm in V2RayEz",
            body = "Back in Domain Fronting, check \"I've installed it\", then enable fronting."
        )
        return steps
    }

    private fun writeCacheFile(context: Context, name: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        return File(dir, name).also { it.writeBytes(bytes) }
    }
}
