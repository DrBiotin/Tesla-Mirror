package com.apptesla.mirror;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** App strings in 6 languages. Order of every row: TR, EN, DE, FR, ES, AR. */
final class I18n {
    static final String[] CODES = {"tr", "en", "de", "fr", "es", "ar"};
    static final String[] NAMES = {"Türkçe", "English", "Deutsch", "Français", "Español", "العربية"};
    private static final String PREFS = "settings", KEY = "lang";
    private static final Map<String, String[]> S = new HashMap<>();
    private static volatile String current;

    private static void put(String k, String... v) { S.put(k, v); }

    static {
        put("start", "▶  Yayını başlat", "▶  Start streaming", "▶  Übertragung starten",
                "▶  Démarrer la diffusion", "▶  Iniciar transmisión", "▶  بدء البث");
        put("stop", "■  Yayını durdur", "■  Stop streaming", "■  Übertragung beenden",
                "■  Arrêter la diffusion", "■  Detener transmisión", "■  إيقاف البث");
        put("open_in_tesla", "Tesla tarayıcısında şu adreslerden birini açın:",
                "Open one of these addresses in the Tesla browser:",
                "Öffnen Sie eine dieser Adressen im Tesla-Browser:",
                "Ouvrez l'une de ces adresses dans le navigateur de la Tesla :",
                "Abra una de estas direcciones en el navegador del Tesla:",
                "افتح أحد هذه العناوين في متصفح تسلا:");
        put("copy_hint", "Kopyalamak için adrese uzun basın.", "Long-press an address to copy it.",
                "Zum Kopieren lange auf eine Adresse drücken.",
                "Appuyez longuement sur une adresse pour la copier.",
                "Mantenga pulsada una dirección para copiarla.",
                "اضغط مطولاً على العنوان لنسخه.");
        put("no_address", "(Ağ adresi bulunamadı — hotspot açık mı?)",
                "(No network address found — is the hotspot on?)",
                "(Keine Netzwerkadresse gefunden – ist der Hotspot an?)",
                "(Aucune adresse réseau trouvée — le point d'accès est-il activé ?)",
                "(No se encontró ninguna dirección de red: ¿está activado el punto de acceso?)",
                "(لم يتم العثور على عنوان شبكة — هل نقطة الاتصال مفعّلة؟)");
        put("hotspot_hint", "Hotspot adresi genelde 192.168.x.x veya 10.x.x.x ile başlar.",
                "The hotspot address usually starts with 192.168.x.x or 10.x.x.x.",
                "Die Hotspot-Adresse beginnt meist mit 192.168.x.x oder 10.x.x.x.",
                "L'adresse du point d'accès commence généralement par 192.168.x.x ou 10.x.x.x.",
                "La dirección del punto de acceso suele empezar por 192.168.x.x o 10.x.x.x.",
                "يبدأ عنوان نقطة الاتصال عادةً بـ 192.168.x.x أو 10.x.x.x.");
        put("step1", "1. Telefonda Wi-Fi hotspot'u açın.", "1. Turn on the Wi-Fi hotspot on your phone.",
                "1. Aktivieren Sie den WLAN-Hotspot auf dem Telefon.",
                "1. Activez le point d'accès Wi-Fi du téléphone.",
                "1. Active el punto de acceso Wi-Fi del teléfono.",
                "1. شغّل نقطة اتصال Wi-Fi على الهاتف.");
        put("step2", "2. Tesla'yı bu hotspot'a bağlayın (Kontroller > Wi-Fi).",
                "2. Connect the Tesla to this hotspot (Controls > Wi-Fi).",
                "2. Verbinden Sie den Tesla in den WLAN-Einstellungen des Fahrzeugs mit diesem Hotspot.",
                "2. Connectez la Tesla à ce point d'accès dans les réglages Wi-Fi du véhicule.",
                "2. Conecte el Tesla a este punto de acceso desde los ajustes Wi-Fi del vehículo.",
                "2. وصّل سيارة تسلا بنقطة الاتصال هذه من إعدادات Wi-Fi في السيارة.");
        put("step3", "3. \"Yayını başlat\"a basın ve ekran paylaşımını onaylayın.",
                "3. Tap \"Start streaming\" and confirm screen sharing.",
                "3. Tippen Sie auf „Übertragung starten“ und bestätigen Sie die Bildschirmfreigabe.",
                "3. Appuyez sur « Démarrer la diffusion » et confirmez le partage d'écran.",
                "3. Pulse «Iniciar transmisión» y confirme el uso compartido de pantalla.",
                "3. اضغط على «بدء البث» ووافق على مشاركة الشاشة.");
        put("step4", "4. Görünen adresi Tesla tarayıcısında açıp ▶ Başlat'a dokunun.",
                "4. Open the shown address in the Tesla browser and tap ▶ Start.",
                "4. Öffnen Sie die angezeigte Adresse im Tesla-Browser und tippen Sie auf ▶ Start.",
                "4. Ouvrez l'adresse affichée dans le navigateur de la Tesla et appuyez sur ▶ Démarrer.",
                "4. Abra la dirección mostrada en el navegador del Tesla y pulse ▶ Iniciar.",
                "4. افتح العنوان الظاهر في متصفح تسلا واضغط على ▶ ابدأ.");
        put("notes_title", "Notlar:", "Notes:", "Hinweise:", "Remarques :", "Notas:", "ملاحظات:");
        put("note_lock", "• Yayın sırasında telefon ekranı açık tutulur; telefonu kilitlerseniz yayın durur.",
                "• The phone screen stays on while streaming; locking the phone stops the stream.",
                "• Während der Übertragung bleibt der Bildschirm an; wenn Sie das Telefon sperren, endet die Übertragung.",
                "• L'écran du téléphone reste allumé pendant la diffusion ; verrouiller le téléphone arrête la diffusion.",
                "• La pantalla del teléfono permanece encendida durante la transmisión; si bloquea el teléfono, la transmisión se detiene.",
                "• تبقى شاشة الهاتف مضاءة أثناء البث؛ قفل الهاتف يوقف البث.");
        put("note_drm", "• Netflix gibi korumalı (DRM) uygulamalar siyah ekran/sessiz görünür.",
                "• DRM-protected apps such as Netflix appear black and/or silent.",
                "• Geschützte (DRM-)Apps wie Netflix erscheinen schwarz bzw. ohne Ton.",
                "• Les applications protégées (DRM) comme Netflix apparaissent noires et/ou sans son.",
                "• Las aplicaciones protegidas (DRM), como Netflix, se ven en negro y/o sin sonido.",
                "• التطبيقات المحمية (DRM) مثل Netflix تظهر بشاشة سوداء و/أو بدون صوت.");
        put("note_drive", "• Tesla sürüşte tarayıcı videosunu gizleyebilir; ses devam eder.",
                "• Tesla may hide browser video while driving; audio continues.",
                "• Tesla kann Browser-Videos während der Fahrt ausblenden; der Ton läuft weiter.",
                "• Tesla peut masquer la vidéo du navigateur pendant la conduite ; le son continue.",
                "• Tesla puede ocultar el vídeo del navegador durante la conducción; el audio continúa.",
                "• قد تُخفي تسلا فيديو المتصفح أثناء القيادة؛ ويستمر الصوت.");
        put("lang_title", "Dil", "Language", "Sprache", "Langue", "Idioma", "اللغة");
        put("copy", "Kopyala", "Copy", "Kopieren", "Copier", "Copiar", "نسخ");
        put("copied", "Kopyalandı", "Copied", "Kopiert", "Copié", "Copiado", "تم النسخ");
        put("toast_no_audio", "Ses izni verilmedi: yalnızca görüntü aktarılacak",
                "Audio permission denied: only video will be streamed",
                "Audio-Berechtigung verweigert: Es wird nur das Bild übertragen",
                "Autorisation audio refusée : seule l'image sera diffusée",
                "Permiso de audio denegado: solo se transmitirá el vídeo",
                "تم رفض إذن الصوت: سيتم بث الصورة فقط");
        put("toast_no_notif", "Bildirim izni yok: yayını durdurmak için uygulamaya dönün",
                "No notification permission: return to the app to stop streaming",
                "Keine Benachrichtigungsberechtigung: Zum Beenden zur App zurückkehren",
                "Pas d'autorisation de notification : revenez à l'application pour arrêter la diffusion",
                "Sin permiso de notificaciones: vuelva a la aplicación para detener la transmisión",
                "لا يوجد إذن للإشعارات: عُد إلى التطبيق لإيقاف البث");
        put("toast_capture_denied", "Ekran yakalama izni verilmedi", "Screen capture permission was not granted",
                "Bildschirmaufnahme wurde nicht erlaubt",
                "L'autorisation de capture d'écran n'a pas été accordée",
                "No se concedió el permiso de captura de pantalla",
                "لم يتم منح إذن التقاط الشاشة");
        put("err_capture", "Ekran yakalama izni alınamadı", "Could not get screen capture permission",
                "Bildschirmaufnahme-Berechtigung konnte nicht abgerufen werden",
                "Impossible d'obtenir l'autorisation de capture d'écran",
                "No se pudo obtener el permiso de captura de pantalla",
                "تعذّر الحصول على إذن التقاط الشاشة");
        put("err_start", "Başlatılamadı: ", "Could not start: ", "Start fehlgeschlagen: ",
                "Échec du démarrage : ", "No se pudo iniciar: ", "تعذّر البدء: ");
        put("err_rotation", "Döndürme sonrası video yeniden başlatılamadı",
                "Video could not restart after rotation",
                "Video konnte nach dem Drehen nicht neu gestartet werden",
                "La vidéo n'a pas pu redémarrer après la rotation",
                "El vídeo no pudo reiniciarse tras la rotación",
                "تعذّر إعادة تشغيل الفيديو بعد تدوير الشاشة");
        put("err_lock", "Paylaşım sistem tarafından durduruldu (telefon kilitlenmiş olabilir).",
                "Sharing was stopped by the system (the phone may have been locked).",
                "Die Freigabe wurde vom System beendet (das Telefon wurde möglicherweise gesperrt).",
                "Le partage a été arrêté par le système (le téléphone a peut-être été verrouillé).",
                "El sistema detuvo el uso compartido (es posible que el teléfono se haya bloqueado).",
                "أوقف النظام المشاركة (ربما تم قفل الهاتف).");
        put("err_busy", "Önceki yayın kapanıyor, birkaç saniye sonra tekrar deneyin.",
                "The previous stream is closing; try again in a few seconds.",
                "Die vorherige Übertragung wird beendet; versuchen Sie es in ein paar Sekunden erneut.",
                "La diffusion précédente se ferme ; réessayez dans quelques secondes.",
                "La transmisión anterior se está cerrando; vuelva a intentarlo en unos segundos.",
                "البث السابق قيد الإغلاق؛ حاول مرة أخرى بعد بضع ثوانٍ.");
        put("notif_channel", "Ekran yansıtma", "Screen mirroring", "Bildschirmspiegelung",
                "Recopie d'écran", "Duplicación de pantalla", "انعكاس الشاشة");
        put("notif_title", "TeslaMirror yayında", "TeslaMirror is streaming", "TeslaMirror überträgt",
                "TeslaMirror diffuse", "TeslaMirror está transmitiendo", "TeslaMirror يبث الآن");
        put("notif_text", "Tesla tarayıcısında uygulamadaki adresi açın",
                "Open the address shown in the app in the Tesla browser",
                "Öffnen Sie die in der App angezeigte Adresse im Tesla-Browser",
                "Ouvrez l'adresse affichée dans l'application dans le navigateur de la Tesla",
                "Abra en el navegador del Tesla la dirección que muestra la aplicación",
                "افتح العنوان الظاهر في التطبيق في متصفح تسلا");
        put("notif_stop", "Durdur", "Stop", "Beenden", "Arrêter", "Detener", "إيقاف");
    }

    private I18n() {}

    static int index(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return i;
        return 1; // English
    }

    /** Saved choice, else the phone's language if supported, else English. */
    static String current(Context c) {
        String cur = current;
        if (cur != null) return cur;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cur = p.getString(KEY, null);
        if (cur == null) {
            String sys = Locale.getDefault().getLanguage();
            cur = CODES[index(sys)];
            if (!cur.equals(sys)) cur = "en";
        }
        current = cur;
        return cur;
    }

    static void set(Context c, String code) {
        current = code;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply();
    }

    static String t(Context c, String key) {
        String[] v = S.get(key);
        if (v == null) return key;
        return v[index(current(c))];
    }

    static boolean rtl(Context c) { return "ar".equals(current(c)); }
}
