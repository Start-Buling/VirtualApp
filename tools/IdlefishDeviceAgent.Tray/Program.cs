using System.Diagnostics;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace IdlefishDeviceAgentTray;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(true, "IdlefishDeviceAgent.Tray.Singleton", out var createdNew);
        if (!createdNew)
        {
            MessageBox.Show(
                "Idlefish Device Agent 托盘已经在运行。",
                "Idlefish Device Agent",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayAgentContext());
    }
}

internal sealed class TrayAgentContext : ApplicationContext
{
    private readonly NotifyIcon _notifyIcon;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _startItem;
    private readonly ToolStripMenuItem _stopItem;
    private readonly ToolStripMenuItem _restartItem;
    private readonly string _baseDir;
    private readonly string _agentPath;
    private readonly string _logPath;
    private readonly Icon _icon;
    private Process? _agentProcess;
    private bool _intentionalStop;
    private bool _exiting;

    public TrayAgentContext()
    {
        _baseDir = AppContext.BaseDirectory;
        _agentPath = Path.Combine(_baseDir, "IdlefishDeviceAgent.exe");
        _logPath = Path.Combine(_baseDir, "agent-tray.log");
        _icon = LoadTrayIcon();

        _statusItem = new ToolStripMenuItem("状态：启动中") { Enabled = false };
        _startItem = new ToolStripMenuItem("启动 Agent", null, (_, _) => StartAgent());
        _stopItem = new ToolStripMenuItem("停止 Agent", null, (_, _) => StopAgent());
        _restartItem = new ToolStripMenuItem("重启 Agent", null, (_, _) => RestartAgent());

        var openLogItem = new ToolStripMenuItem("打开日志", null, (_, _) => OpenFile(_logPath));
        var openFolderItem = new ToolStripMenuItem("打开目录", null, (_, _) => OpenFolder());
        var exitItem = new ToolStripMenuItem("退出托盘", null, (_, _) => ExitTray());

        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_startItem);
        menu.Items.Add(_stopItem);
        menu.Items.Add(_restartItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(openLogItem);
        menu.Items.Add(openFolderItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(exitItem);

        _notifyIcon = new NotifyIcon
        {
            Icon = _icon,
            Text = "Idlefish Device Agent",
            ContextMenuStrip = menu,
            Visible = true
        };
        _notifyIcon.DoubleClick += (_, _) => OpenFile(_logPath);

        StartAgent();
    }

    private void StartAgent()
    {
        if (_agentProcess is { HasExited: false })
        {
            UpdateStatus("运行中", true);
            return;
        }

        using var externalAgent = FindExternalAgentProcess();
        if (externalAgent is not null)
        {
            Log($"External Agent already running pid={externalAgent.Id}");
            UpdateStatus($"外部运行 pid={externalAgent.Id}", false);
            ShowBalloon("检测到已有 Agent", "请先退出旧的 run-agent-poll.ps1，再让托盘版接管。");
            return;
        }

        if (!File.Exists(_agentPath))
        {
            Log($"Agent executable not found: {_agentPath}");
            UpdateStatus("未找到 IdlefishDeviceAgent.exe", false);
            ShowBalloon("启动失败", "同目录下没有找到 IdlefishDeviceAgent.exe。");
            return;
        }

        try
        {
            Directory.CreateDirectory(_baseDir);
            _intentionalStop = false;

            var startInfo = new ProcessStartInfo
            {
                FileName = _agentPath,
                Arguments = "--mode poll",
                WorkingDirectory = _baseDir,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };

            _agentProcess = new Process
            {
                StartInfo = startInfo,
                EnableRaisingEvents = true
            };
            _agentProcess.OutputDataReceived += (_, e) => LogProcessLine(e.Data);
            _agentProcess.ErrorDataReceived += (_, e) => LogProcessLine(e.Data);
            _agentProcess.Exited += (_, _) => OnAgentExited();

            _agentProcess.Start();
            _agentProcess.BeginOutputReadLine();
            _agentProcess.BeginErrorReadLine();

            Log($"Started Agent pid={_agentProcess.Id}");
            UpdateStatus("运行中", true);
            ShowBalloon("Agent 已启动", "正在轮询 Web 任务。");
        }
        catch (Exception ex)
        {
            Log("Failed to start Agent: " + ex);
            UpdateStatus("启动失败", false);
            ShowBalloon("启动失败", ex.Message);
        }
    }

    private void StopAgent()
    {
        _intentionalStop = true;

        var process = _agentProcess;
        if (process is null || process.HasExited)
        {
            UpdateStatus("已停止", false);
            return;
        }

        try
        {
            Log($"Stopping Agent pid={process.Id}");
            process.Kill(entireProcessTree: true);
            process.WaitForExit(5000);
        }
        catch (Exception ex)
        {
            Log("Failed to stop Agent: " + ex);
            ShowBalloon("停止失败", ex.Message);
        }
        finally
        {
            UpdateStatus("已停止", false);
        }
    }

    private void RestartAgent()
    {
        StopAgent();
        _intentionalStop = false;
        StartAgent();
    }

    private void OnAgentExited()
    {
        var code = 0;
        try
        {
            code = _agentProcess?.ExitCode ?? 0;
        }
        catch
        {
            // The process can be disposed while the tray is exiting.
        }

        Log($"Agent exited code={code}, intentionalStop={_intentionalStop}, exiting={_exiting}");

        if (_exiting || _intentionalStop)
        {
            UpdateStatus("已停止", false);
            return;
        }

        UpdateStatus("已退出，准备重启", false);
        ShowBalloon("Agent 已退出", "3 秒后自动重启。");

        Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
            if (!_exiting && !_intentionalStop)
            {
                StartAgentOnUiThread();
            }
        });
    }

    private void StartAgentOnUiThread()
    {
        if (_notifyIcon.ContextMenuStrip?.IsDisposed == false)
        {
            _notifyIcon.ContextMenuStrip.BeginInvoke(new Action(StartAgent));
        }
    }

    private void UpdateStatus(string status, bool running)
    {
        if (_notifyIcon.ContextMenuStrip?.InvokeRequired == true)
        {
            _notifyIcon.ContextMenuStrip.BeginInvoke(new Action(() => UpdateStatus(status, running)));
            return;
        }

        _statusItem.Text = $"状态：{status}";
        _notifyIcon.Text = $"Idlefish Agent：{status}";
        _startItem.Enabled = !running;
        _stopItem.Enabled = running;
        _restartItem.Enabled = true;
    }

    private void ShowBalloon(string title, string message)
    {
        try
        {
            _notifyIcon.BalloonTipTitle = title;
            _notifyIcon.BalloonTipText = message;
            _notifyIcon.ShowBalloonTip(2500);
        }
        catch
        {
            // Balloon notifications are best effort on older Windows shell states.
        }
    }

    private void LogProcessLine(string? line)
    {
        if (!string.IsNullOrWhiteSpace(line))
        {
            Log(line);
        }
    }

    private void Log(string message)
    {
        try
        {
            var line = $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} {message}{Environment.NewLine}";
            File.AppendAllText(_logPath, line, Encoding.UTF8);
        }
        catch
        {
            // Avoid crashing the tray process because a log file is locked or unavailable.
        }
    }

    private void OpenFile(string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                File.WriteAllText(path, "", Encoding.UTF8);
            }

            Process.Start(new ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            Log("Failed to open file: " + ex);
            ShowBalloon("打开失败", ex.Message);
        }
    }

    private void OpenFolder()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = _baseDir,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            Log("Failed to open folder: " + ex);
            ShowBalloon("打开失败", ex.Message);
        }
    }

    private void ExitTray()
    {
        _exiting = true;
        StopAgent();
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        _icon.Dispose();
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _exiting = true;
            StopAgent();
            _notifyIcon.Dispose();
            _icon.Dispose();
            _agentProcess?.Dispose();
        }

        base.Dispose(disposing);
    }

    private Icon LoadTrayIcon()
    {
        var iconCandidates = new[]
        {
            Path.Combine(_baseDir, "idlefish-agent.ico"),
            Path.Combine(_baseDir, "Assets", "idlefish-agent.ico")
        };

        foreach (var iconPath in iconCandidates)
        {
            if (File.Exists(iconPath))
            {
                return new Icon(iconPath);
            }
        }

        var associatedIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application;
        return (Icon)associatedIcon.Clone();
    }

    private Process? FindExternalAgentProcess()
    {
        foreach (var process in Process.GetProcessesByName("IdlefishDeviceAgent"))
        {
            try
            {
                var processPath = process.MainModule?.FileName;
                if (string.Equals(processPath, _agentPath, StringComparison.OrdinalIgnoreCase))
                {
                    return process;
                }
            }
            catch
            {
                process.Dispose();
            }
        }

        return null;
    }
}
