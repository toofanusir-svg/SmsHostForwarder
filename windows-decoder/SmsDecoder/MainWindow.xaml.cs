using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using System.IO;

namespace SmsDecoder;

public partial class MainWindow : Window
{
    private readonly DispatcherTimer _clipTimer   = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private string   _lastClipboard  = string.Empty;
    private bool     _keyVisible      = false;

    private static readonly string SettingsFile =
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "settings.txt");

    public MainWindow()
    {
        InitializeComponent();
        LoadKey();

        _clipTimer.Tick += ClipTimer_Tick;
    }

    // ── Key management ───────────────────────────────────────────────────────

    private void LoadKey()
    {
        if (File.Exists(SettingsFile))
            pbKey.Password = File.ReadAllText(SettingsFile).Trim();
    }

    private void SaveKey_Click(object sender, RoutedEventArgs e)
    {
        var key = GetKey();
        if (string.IsNullOrWhiteSpace(key)) { SetStatus("⚠ Key is empty", isError: true); return; }
        File.WriteAllText(SettingsFile, key);
        SetStatus("✔ Key saved", isError: false);
    }

    private void ToggleKey_Click(object sender, RoutedEventArgs e)
    {
        _keyVisible = !_keyVisible;
        if (_keyVisible)
        {
            var tb = new TextBox
            {
                Text             = pbKey.Password,
                Background       = pbKey.Background,
                Foreground       = pbKey.Foreground,
                BorderBrush      = pbKey.BorderBrush,
                BorderThickness  = pbKey.BorderThickness,
                Padding          = pbKey.Padding,
                FontSize         = pbKey.FontSize,
                VerticalContentAlignment = VerticalAlignment.Center
            };
            // Swap PasswordBox for TextBox in its parent Grid cell
            var parent = (Grid)pbKey.Parent;
            int row    = Grid.GetRow(pbKey), col = Grid.GetColumn(pbKey);
            parent.Children.Remove(pbKey);
            Grid.SetRow(tb, row); Grid.SetColumn(tb, col);
            tb.Margin = pbKey.Margin;
            tb.Name   = "tbKeyVisible";
            parent.Children.Insert(0, tb);
        }
        else
        {
            var parent = (Grid)pbKey.Parent;
            var tb     = parent.Children.OfType<TextBox>().FirstOrDefault(x => x.Name == "tbKeyVisible");
            if (tb != null)
            {
                pbKey.Password = tb.Text;
                parent.Children.Remove(tb);
                parent.Children.Insert(0, pbKey);
            }
        }
    }

    private string GetKey()
    {
        // Try visible TextBox first, fall back to PasswordBox
        var parent = (Grid)pbKey.Parent;
        var tb     = parent?.Children.OfType<TextBox>().FirstOrDefault(x => x.Name == "tbKeyVisible");
        return (tb?.Text ?? pbKey.Password).Trim();
    }

    // ── Clipboard monitor ────────────────────────────────────────────────────

    private void Monitor_Checked(object sender, RoutedEventArgs e)
    {
        _clipTimer.Start();
        SetStatus("👁 Clipboard monitor ON", isError: false);
    }

    private void Monitor_Unchecked(object sender, RoutedEventArgs e)
    {
        _clipTimer.Stop();
        SetStatus("", isError: false);
    }

    private void ClipTimer_Tick(object? sender, EventArgs e)
    {
        string clip;
        try { clip = Clipboard.GetText(); } catch { return; }

        if (clip == _lastClipboard) return;
        _lastClipboard = clip;

        if (string.IsNullOrWhiteSpace(clip)) return;

        // Check if clipboard contains an encrypted payload
        if (!DecoderCrypto.LooksEncrypted(clip)) return;

        tbInput.Text = clip;
        SetStatus("📋 Encrypted text detected — press Decode", isError: false);
        Activate(); // bring window to front
    }

    // ── Decode ───────────────────────────────────────────────────────────────

    private void Decode_Click(object sender, RoutedEventArgs e)
    {
        var key   = GetKey();
        var input = tbInput.Text.Trim();

        if (string.IsNullOrEmpty(key))  { SetStatus("⚠ No encryption key set", isError: true);  return; }
        if (string.IsNullOrEmpty(input)){ SetStatus("⚠ Input is empty",         isError: true);  return; }

        try
        {
            var result = DecoderCrypto.ExtractAndDecrypt(input, key);
            tbResult.Text    = result;
            tbResult.Foreground = System.Windows.Media.Brushes.LightGreen;
            SetStatus("✔ Decrypted successfully", isError: false);
        }
        catch (Exception ex)
        {
            tbResult.Text    = $"❌ {ex.Message}";
            tbResult.Foreground = System.Windows.Media.Brushes.OrangeRed;
            SetStatus("✕ Decryption failed", isError: true);
        }
    }

    // ── Utility buttons ──────────────────────────────────────────────────────

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        tbInput.Clear();
        tbResult.Clear();
        SetStatus("", isError: false);
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(tbResult.Text))
        {
            Clipboard.SetText(tbResult.Text);
            SetStatus("📋 Copied", isError: false);
        }
    }

    private void Dismiss_Click(object sender, RoutedEventArgs e)
    {
        tbResult.Clear();
        SetStatus("", isError: false);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private void SetStatus(string msg, bool isError)
    {
        tbStatus.Text       = msg;
        tbStatus.Foreground = isError
            ? System.Windows.Media.Brushes.OrangeRed
            : System.Windows.Media.Brushes.LightGreen;
    }
}
