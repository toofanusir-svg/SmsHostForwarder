using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web;
using Newtonsoft.Json.Linq;

namespace SmsDecoder;

/// <summary>
/// Mirrors the AES-256-CBC decryption used in the Android SMS Forwarder app.
/// Accepts all real-world input formats produced by copy/paste, share, or webhook.
/// </summary>
public static class DecoderCrypto
{
    // ── Public API ────────────────────────────────────────────────────────────

    /// <summary>
    /// Quick check: does this text look like it contains an encrypted payload?
    /// Used by the clipboard monitor to avoid processing every clipboard change.
    /// </summary>
    public static bool LooksEncrypted(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return false;
        return text.Contains("\"encrypted\"") ||
               text.Contains("%22encrypted%22") ||
               text.Contains("\"iv\"")          ||
               text.Contains("%22iv%22")         ||
               (text.Contains("\"data\"") && text.Contains("\"iv\""));
    }

    /// <summary>
    /// Accepts ANY of these formats and returns the decrypted plaintext:
    ///   1. Full JSON: {"encrypted":true,"iv":"...","data":"..."}
    ///   2. Partial (no opening brace): "encrypted":true,"iv":"...","data":"..."}
    ///   3. Wrapped: {"message":"{\"encrypted\":true,...}"}
    ///   4. URL-encoded anywhere in the input
    ///   5. Embedded inside a URL or larger text
    ///   6. Base64 with whitespace/newlines from copy-paste
    /// </summary>
    public static string ExtractAndDecrypt(string rawInput, string key)
    {
        var jsonStr = ExtractEncryptedJson(rawInput);
        return DecryptJson(jsonStr, key);
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    private static string ExtractEncryptedJson(string raw)
    {
        // Build candidate list: original, URL-decoded, collapsed-whitespace variants
        var candidates = new List<string>();
        candidates.Add(raw.Trim());

        try { candidates.Add(HttpUtility.UrlDecode(raw.Trim())); } catch { }

        var collapsed = Regex.Replace(raw, @"\s+", "");
        candidates.Add(collapsed);
        try { candidates.Add(HttpUtility.UrlDecode(collapsed)); } catch { }

        foreach (var c in candidates.Distinct())
        {
            var result = TryExtractFrom(c);
            if (result != null) return result;
        }

        throw new InvalidOperationException(
            "No encrypted payload found.\n" +
            "Make sure the text contains the encrypted output from SMS Forwarder.");
    }

    private static string? TryExtractFrom(string text)
    {
        // A: find balanced JSON object containing "encrypted"
        var fullJson = FindJsonObjectContaining(text, "encrypted");
        if (fullJson != null)
        {
            try
            {
                var obj = JObject.Parse(fullJson);
                if (obj.ContainsKey("data") && obj.ContainsKey("iv"))
                    return fullJson;                                // it's the envelope itself
                if (obj.ContainsKey("message"))
                    return TryExtractFrom(obj["message"]!.ToString()); // unwrap
            }
            catch { }
        }

        // B: text missing opening brace — extract iv + data via regex
        return ExtractFromPartialText(text);
    }

    private static string? ExtractFromPartialText(string text)
    {
        // Normalise escaped quotes and collapse whitespace
        var t = text.Replace("\\\"", "\"").Replace("\\n", "").Replace("\\r", "");
        t = Regex.Replace(t, @"\s+", "");

        var iv   = Regex.Match(t, @"""iv""\s*:\s*""([A-Za-z0-9+/=]+)""");
        var data = Regex.Match(t, @"""data""\s*:\s*""([A-Za-z0-9+/=]+)""");

        if (iv.Success && data.Success)
            return $"{{\"encrypted\":true,\"iv\":\"{iv.Groups[1].Value}\",\"data\":\"{data.Groups[1].Value}\"}}";

        return null;
    }

    private static string? FindJsonObjectContaining(string text, string requiredKey)
    {
        int i = 0;
        while (i < text.Length)
        {
            int start = text.IndexOf('{', i);
            if (start < 0) break;
            int depth = 0, end = -1;
            for (int j = start; j < text.Length; j++)
            {
                if (text[j] == '{') depth++;
                else if (text[j] == '}') { depth--; if (depth == 0) { end = j; break; } }
            }
            if (end > start)
            {
                var candidate = text[start..(end + 1)];
                if (candidate.Contains($"\"{requiredKey}\""))
                    return candidate;
            }
            i = start + 1;
        }
        return null;
    }

    // ── Decryption ────────────────────────────────────────────────────────────

    private static string DecryptJson(string jsonStr, string key)
    {
        JObject envelope;
        try { envelope = JObject.Parse(jsonStr); }
        catch (Exception ex) { throw new FormatException($"Could not parse encrypted payload: {ex.Message}"); }

        var ivB64   = envelope["iv"]?.ToString()   ?? throw new FormatException("Missing 'iv' field");
        var dataB64 = envelope["data"]?.ToString()  ?? throw new FormatException("Missing 'data' field");

        // Strip any whitespace that copy-paste / line-wrapping may have introduced
        var iv      = Convert.FromBase64String(CleanBase64(ivB64));
        var data    = Convert.FromBase64String(CleanBase64(dataB64));

        // Derive 256-bit key: SHA-256 of the UTF-8 key string (matches Android implementation)
        var keyBytes = SHA256.HashData(Encoding.UTF8.GetBytes(key));

        using var aes = Aes.Create();
        aes.Key     = keyBytes;
        aes.IV      = iv;
        aes.Mode    = CipherMode.CBC;
        aes.Padding = PaddingMode.PKCS7;

        using var dec    = aes.CreateDecryptor();
        using var ms     = new MemoryStream(data);
        using var cs     = new CryptoStream(ms, dec, CryptoStreamMode.Read);
        using var reader = new StreamReader(cs, Encoding.UTF8);
        return reader.ReadToEnd();
    }

    private static string CleanBase64(string s)
        => Regex.Replace(s.Trim(), @"\s+", "");
}
