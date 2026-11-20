# SMS Forwarder v2 — Android 10+ only

این نسخه مستقل از نسخه اصلی است و فقط برای اندروید 10 (API 29) و بالاتر طراحی شده.
نسخه اصلی (`SmsHostForwarder`) دست‌نخورده باقی مانده.

---

## تغییرات نسبت به نسخه اصلی

### مشکل اصلی که رفع شد
در اندروید 13+ (API 33)، دریافت‌کننده‌ای که با `RECEIVER_NOT_EXPORTED` ثبت شود،
`SMS_RECEIVED_ACTION` را دریافت نمی‌کند چون این broadcast از طرف سیستم می‌آید.
این نسخه receiver را با `RECEIVER_EXPORTED` ثبت می‌کند.

### بهینه‌سازی باتری (Android 10+)
اندروید 10+ می‌تواند سرویس foreground را در پس‌زمینه متوقف کند.
هنگام اولین اجرا، یک دیالوگ نمایش داده می‌شود تا کاربر برنامه را
از بهینه‌سازی باتری معاف کند.

### حذف کدهای قدیمی
- تمام branch های `Build.VERSION < API 29` حذف شدند.
- `startForegroundService` مستقیم فراخوانی می‌شود (نه `startService`).
- `PendingIntent.FLAG_IMMUTABLE` همیشه استفاده می‌شود.
- `readMissedCallLog` از `getColumnIndexOrThrow` و `cursor.use` استفاده می‌کند.

### package و applicationId
| مورد | نسخه اصلی | نسخه v2 |
|------|-----------|---------|
| applicationId | `com.example.smsfrowarder` | `com.example.smsforwarder.v2` |
| minSdk | 23 (Android 6) | 29 (Android 10) |
| versionName | 4.9.x | 1.0.0 |

---

## نصب
هر دو نسخه می‌توانند روی یک دستگاه نصب باشند (applicationId متفاوت است).
