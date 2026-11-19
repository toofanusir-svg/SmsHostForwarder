using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

// Alias to avoid ambiguity with System.Windows.Controls.TextBox and System.Windows.Clipboard
using WinForms = System.Windows.Forms;
using WpfClipboard = System.Windows.Clipboard;

namespace SmsDecoder;

public partial class MainWindow : Window
{
    // ── Timers & state ───────────────────────────────────────────────────────
    private readonly DispatcherTimer _clipTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private string _lastClipboard = string.Empty;
    private bool   _keyVisible    = false;
    private bool   _suppressInputChange = false;   // avoid re-entry in TextChanged

    // Tray
    private WinForms.NotifyIcon? _trayIcon;
    private bool _reallyClose = false;

    private static readonly string SettingsFile =
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "settings.txt");

    // ── Init ─────────────────────────────────────────────────────────────────

    public MainWindow()
    {
        InitializeComponent();
        LoadKey();
        SetupTray();
        _clipTimer.Tick += ClipTimer_Tick;
    }

    // ── Tray icon ────────────────────────────────────────────────────────────

    private void SetupTray()
    {
        _trayIcon = new WinForms.NotifyIcon
        {
            Icon    = System.Drawing.SystemIcons.Information,
            Text    = "SMS Decoder",
            Visible = false
        };
        _trayIcon.DoubleClick += (_, _) => ShowFromTray();
        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => ShowFromTray());
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Exit",  null, (_, _) => ExitApp());
        _trayIcon.ContextMenuStrip = menu;
    }

    private void ShowFromTray()
    {
        Show();
        WindowState  = WindowState.Normal;
        Activate();
        _trayIcon!.Visible = false;
    }

    private void ExitApp()
    {
        _reallyClose = true;
        _trayIcon?.Dispose();
        System.Windows.Application.Current.Shutdown();
    }

    // ── Window events ─────────────────────────────────────────────────────────

    protected override void OnStateChanged(EventArgs e)
    {
        if (WindowState == WindowState.Minimized)
            MinimizeToTray();
        base.OnStateChanged(e);
    }

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        if (!_reallyClose)
        {
            e.Cancel = true;
            MinimizeToTray();
        }
    }

    private void MinimizeToTray()
    {
        Hide();
        _trayIcon!.Visible = true;
        _trayIcon.ShowBalloonTip(1500, "SMS Decoder", "Running in background", WinForms.ToolTipIcon.Info);
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
        if (string.IsNullOrWhiteSpace(key)) { SetStatus("⚠ Key is empty", true); return; }
        File.WriteAllText(SettingsFile, key);
        SetStatus("✔ Key saved", false);
    }

    // When key changes, reset last-clipboard so monitor can re-trigger
    private void PbKey_Changed(object sender, RoutedEventArgs e)
        => _lastClipboard = string.Empty;

    private void ToggleKey_Click(object sender, RoutedEventArgs e)
    {
        _keyVisible = !_keyVisible;
        var parent = (Grid)pbKey.Parent;

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
                Margin           = pbKey.Margin,
                Name             = "tbKeyVisible",
                VerticalContentAlignment = VerticalAlignment.Center
            };
            Grid.SetRow(tb, 1); Grid.SetColumn(tb, 0);
            parent.Children.Remove(pbKey);
            parent.Children.Insert(0, tb);
        }
        else
        {
            var tb = parent.Children.OfType<TextBox>().FirstOrDefault(x => x.Name == "tbKeyVisible");
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
        var parent = (Grid)pbKey.Parent;
        var tb     = parent?.Children.OfType<TextBox>().FirstOrDefault(x => x.Name == "tbKeyVisible");
        return (tb?.Text ?? pbKey.Password).Trim();
    }

    // ── Clipboard monitor ────────────────────────────────────────────────────

    private void Monitor_Checked(object sender, RoutedEventArgs e)
    {
        _lastClipboard = string.Empty;   // reset so next encrypted clip fires immediately
        _clipTimer.Start();
        SetStatus("👁 Clipboard monitor ON", false);
    }

    private void Monitor_Unchecked(object sender, RoutedEventArgs e)
    {
        _clipTimer.Stop();
        SetStatus("", false);
    }

    private void ClipTimer_Tick(object? sender, EventArgs e)
    {
        string clip;
        try { clip = WpfClipboard.GetText(); } catch { return; }
        if (string.IsNullOrWhiteSpace(clip) || clip == _lastClipboard) return;
        _lastClipboard = clip;

        if (!DecoderCrypto.LooksEncrypted(clip)) return;

        // Set input (suppress auto-decode from TextChanged — we do it manually below)
        _suppressInputChange = true;
        tbInput.Text = clip;
        _suppressInputChange = false;

        SetStatus("📋 Encrypted text detected", false);

        if (tbAutoDecode.IsChecked == true)
            TryAutoDecode(clip, restoreWindow: true);
        else
        {
            ShowFromTray();
            Activate();
        }
    }

    // ── Auto-decode toggle ────────────────────────────────────────────────────

    private void AutoDecode_Changed(object sender, RoutedEventArgs e)
    {
        // No special action needed — the flag is read in ClipTimer_Tick and Input_TextChanged
    }

    // ── Input TextChanged — auto-decode on manual paste too ──────────────────

    private void Input_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_suppressInputChange) return;
        if (tbAutoDecode.IsChecked != true) return;
        var input = tbInput.Text.Trim();
        if (string.IsNullOrEmpty(input)) return;
        if (!DecoderCrypto.LooksEncrypted(input)) return;
        TryAutoDecode(input, restoreWindow: false);
    }

    private void TryAutoDecode(string input, bool restoreWindow)
    {
        var key = GetKey();
        if (string.IsNullOrEmpty(key)) return;
        try
        {
            var result = DecoderCrypto.ExtractAndDecrypt(input, key);
            tbResult.Text       = result;
            tbResult.Foreground = System.Windows.Media.Brushes.LightGreen;
            SetStatus("✔ Auto-decoded", false);

            if (restoreWindow) ShowFromTray();
        }
        catch { /* silent — user can manually decode and see the error */ }
    }

    // ── Paste button ─────────────────────────────────────────────────────────

    private void Paste_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var text = WpfClipboard.GetText();
            if (!string.IsNullOrEmpty(text))
                tbInput.Text = text;
        }
        catch { }
    }

    // ── Decode ───────────────────────────────────────────────────────────────

    private void Decode_Click(object sender, RoutedEventArgs e)
    {
        var key   = GetKey();
        var input = tbInput.Text.Trim();
        if (string.IsNullOrEmpty(key))   { SetStatus("⚠ No encryption key set", true); return; }
        if (string.IsNullOrEmpty(input)) { SetStatus("⚠ Input is empty",         true); return; }

        try
        {
            var result = DecoderCrypto.ExtractAndDecrypt(input, key);
            tbResult.Text       = result;
            tbResult.Foreground = System.Windows.Media.Brushes.LightGreen;
            SetStatus("✔ Decrypted successfully", false);
        }
        catch (Exception ex)
        {
            tbResult.Text       = $"❌ {ex.Message}";
            tbResult.Foreground = System.Windows.Media.Brushes.OrangeRed;
            SetStatus("✕ Decryption failed", true);
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        tbInput.Clear();
        tbResult.Clear();
        SetStatus("", false);
        // Reset clipboard tracking so the same encrypted text can be re-detected
        _lastClipboard = string.Empty;
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(tbResult.Text))
        {
            WpfClipboard.SetText(tbResult.Text);
            SetStatus("📋 Copied", false);
        }
    }

    private void Dismiss_Click(object sender, RoutedEventArgs e)
    {
        tbResult.Clear();
        SetStatus("", false);
    }

    private void Exit_Click(object sender, RoutedEventArgs e) => ExitApp();

    private void SetStatus(string msg, bool isError)
    {
        tbStatus.Text       = msg;
        tbStatus.Foreground = isError
            ? System.Windows.Media.Brushes.OrangeRed
            : System.Windows.Media.Brushes.LightGreen;
    }
}
