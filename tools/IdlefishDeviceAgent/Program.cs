using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Xml.Linq;

namespace IdlefishDeviceAgent;

internal static class Program
{
    private const string HostPackage = "com.carlos.multiapp";
    private const string ReceiverComponent = "com.carlos.multiapp/com.carlos.home.idlefish.IdlefishDeepLinkReceiver";
    private const string LauncherComponent = "com.carlos.multiapp/com.carlos.splash.splashActivity";
    private const string TargetPackage = "com.taobao.idlefish";
    private const string TargetComponent = "com.taobao.idlefish.webview.WebHybridActivity";
    private const string ActionOpenUrl = "com.carlos.multiapp.IDLEFISH_OPEN_URL";
    private const string ActionListVirtualUsers = "com.carlos.multiapp.IDLEFISH_LIST_VIRTUAL_USERS";
    private const string ServicePointsUrl = "https://h5.m.goofish.com/wow/moyu/moyu-project/fish-shop-data/pages/service-points?spm=a2170.28358589.0.0&isOldFriendly=false&_from__=webhybrid";
    private const string WorkbenchUrl = "https://h5.m.goofish.com/wow/moyu/moyu-project/fish-pro-workbench/pages/Workbench?spm=a2170.7905589.0.0&isOldFriendly=false&_from__=main";

    public static async Task<int> Main(string[] args)
    {
        try
        {
            return await Run(args);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Fatal error: {ex}");
            return 1;
        }
    }

    private static async Task<int> Run(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var config = AgentConfig.Load(args);
        config.AdbPath = Adb.ResolvePath(config.AdbPath);
        if (config.Mode is not ("discover" or "collect" or "both" or "poll"))
        {
            Console.Error.WriteLine("Mode must be discover, collect, both, or poll.");
            return 2;
        }

        if (config.Mode is "poll" && string.IsNullOrWhiteSpace(config.Serial))
        {
            await RunMultiDevicePoll(config);
            return 0;
        }

        if (string.IsNullOrWhiteSpace(config.Serial))
        {
            config.Serial = Adb.FirstDevice(config.AdbPath);
        }

        PrepareDeviceConfig(config);

        RefreshVirtualUsers(config);

        Console.WriteLine("Idlefish Device Agent");
        Console.WriteLine($"Agent ID: {config.AgentId}");
        Console.WriteLine($"Server: {config.ServerBaseUrl}");
        Console.WriteLine($"Device no: {config.DeviceNo}");
        Console.WriteLine($"ADB serial: {config.Serial}");
        Console.WriteLine($"ADB path: {config.AdbPath}");
        Console.WriteLine($"Virtual user mode: {config.VirtualUserMode}");
        Console.WriteLine($"Virtual users: {string.Join(", ", config.VirtualUserIds)}");
        Console.WriteLine($"Local relay: http://127.0.0.1:{config.LocalPort}");

        using var relay = new RelayServer(config);
        relay.Start();

        Adb.Run(config.AdbPath, config.Serial, "reverse", $"tcp:{config.LocalPort}", $"tcp:{config.LocalPort}");

        if (config.Mode is "poll")
        {
            using var api = new AgentApi(config);
            await RunPollLoop(config, api, CancellationToken.None);
            return 0;
        }

        if (config.Mode is "discover" or "both")
        {
            await RunForUsers(config, config.VirtualUserIds, WorkbenchUrl, "identity discovery", "idlefish_shop_identity", config.WaitSeconds, CancellationToken.None);
        }

        if (config.Mode is "collect" or "both")
        {
            await RunForUsers(config, config.VirtualUserIds, ServicePointsUrl, "service score collection", "idlefish_shop_service_score", config.WaitSeconds, CancellationToken.None);
        }

        Console.WriteLine("Done.");
        return 0;
    }

    private static async Task RunMultiDevicePoll(AgentConfig baseConfig)
    {
        Console.WriteLine("Idlefish Device Agent");
        Console.WriteLine($"Server: {baseConfig.ServerBaseUrl}");
        Console.WriteLine($"ADB path: {baseConfig.AdbPath}");
        Console.WriteLine($"Device scan interval: {baseConfig.DeviceScanSeconds}s");

        var workers = new Dictionary<string, DeviceWorkerState>(StringComparer.OrdinalIgnoreCase);
        var portAssignments = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        var nextPortOffset = 0;
        while (true)
        {
            string[] devices;
            try
            {
                devices = Adb.ListDevices(baseConfig.AdbPath);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"Unable to scan adb devices: {ex.Message}");
                devices = [];
            }

            var connected = new HashSet<string>(devices, StringComparer.OrdinalIgnoreCase);
            foreach (var serial in devices)
            {
                if (workers.TryGetValue(serial, out var existing)
                    && !existing.Task.IsCompleted)
                {
                    continue;
                }

                if (existing != null)
                {
                    Console.WriteLine($"Restarting Agent worker for adb serial {serial}.");
                    existing.Dispose();
                }

                if (!portAssignments.TryGetValue(serial, out var port))
                {
                    port = baseConfig.LocalPort + nextPortOffset++;
                    portAssignments[serial] = port;
                }

                var workerConfig = baseConfig.Clone();
                workerConfig.Serial = serial;
                workerConfig.AgentId = "";
                workerConfig.LocalPort = port;
                PrepareDeviceConfig(workerConfig);
                var cts = new CancellationTokenSource();
                var task = Task.Run(() => RunDeviceWorker(workerConfig, cts.Token), cts.Token);
                workers[serial] = new DeviceWorkerState(serial, workerConfig, cts, task);
                Console.WriteLine($"Started Agent worker serial={serial} agent_id={workerConfig.AgentId} port={port}.");
            }

            foreach (var serial in workers.Keys.ToArray())
            {
                var worker = workers[serial];
                if (!connected.Contains(serial))
                {
                    Console.WriteLine($"ADB device disconnected; stopping Agent worker serial={serial} agent_id={worker.Config.AgentId}.");
                    worker.Dispose();
                    using var api = new AgentApi(worker.Config);
                    await SafeHeartbeat(api, worker.Config, "no_device", "device_not_connected");
                    workers.Remove(serial);
                    continue;
                }

                if (worker.Task.IsCompleted)
                {
                    Console.WriteLine($"Agent worker exited serial={serial} agent_id={worker.Config.AgentId}; will restart if still connected.");
                    worker.Dispose();
                    workers.Remove(serial);
                }
            }

            await Task.Delay(TimeSpan.FromSeconds(Math.Max(5, baseConfig.DeviceScanSeconds)));
        }
    }

    private static async Task RunDeviceWorker(AgentConfig config, CancellationToken token)
    {
        RefreshVirtualUsers(config);

        Console.WriteLine();
        Console.WriteLine($"Idlefish Device Agent worker: {config.AgentId}");
        Console.WriteLine($"Device no: {config.DeviceNo}");
        Console.WriteLine($"ADB serial: {config.Serial}");
        Console.WriteLine($"Virtual user mode: {config.VirtualUserMode}");
        Console.WriteLine($"Virtual users: {string.Join(", ", config.VirtualUserIds)}");
        Console.WriteLine($"Local relay: http://127.0.0.1:{config.LocalPort}");

        using var relay = new RelayServer(config);
        relay.Start();

        Adb.Run(config.AdbPath, config.Serial, "reverse", $"tcp:{config.LocalPort}", $"tcp:{config.LocalPort}");
        using var api = new AgentApi(config);
        await RunPollLoop(config, api, token);
    }

    private static void PrepareDeviceConfig(AgentConfig config)
    {
        DeviceRegistry.ApplyDeviceNo(config);
        if (string.IsNullOrWhiteSpace(config.AgentId))
        {
            config.AgentId = BuildAgentId(config);
        }
        else if (!string.IsNullOrWhiteSpace(config.Serial)
                 && !config.AgentId.Contains(config.Serial, StringComparison.OrdinalIgnoreCase))
        {
            config.AgentId = $"{config.AgentId.Trim()}-{SanitizeAgentIdPart(config.Serial)}".ToLowerInvariant();
        }
    }

    private static string BuildAgentId(AgentConfig config)
    {
        var host = SanitizeAgentIdPart(Environment.MachineName);
        var serial = SanitizeAgentIdPart(config.Serial);
        var deviceNo = SanitizeAgentIdPart(config.DeviceNo);
        return string.IsNullOrWhiteSpace(deviceNo)
            ? $"{host}-{serial}".ToLowerInvariant()
            : $"{host}-{deviceNo}-{serial}".ToLowerInvariant();
    }

    private static string SanitizeAgentIdPart(string value)
    {
        var trimmed = string.IsNullOrWhiteSpace(value) ? "unknown" : value.Trim();
        return Regex.Replace(trimmed, "[^A-Za-z0-9_-]+", "-").Trim('-');
    }

    private static async Task RunPollLoop(AgentConfig config, AgentApi api, CancellationToken token)
    {
        var registered = false;
        var completedTasks = 0;
        var lastHeartbeat = DateTimeOffset.MinValue;

        while (!token.IsCancellationRequested)
        {
            if (!IsConfiguredDeviceConnected(config, out var disconnectedMessage))
            {
                if ((DateTimeOffset.Now - lastHeartbeat).TotalSeconds >= config.HeartbeatSeconds)
                {
                    await SafeHeartbeat(api, config, "no_device", disconnectedMessage);
                    lastHeartbeat = DateTimeOffset.Now;
                }

                Console.WriteLine($"{disconnectedMessage}. Sleeping {config.PollSeconds}s...");
                await Task.Delay(TimeSpan.FromSeconds(config.PollSeconds), token);
                continue;
            }

            if (!registered)
            {
                RefreshVirtualUsers(config);
                await SafeRegister(api, config);
                registered = true;
            }

            if ((DateTimeOffset.Now - lastHeartbeat).TotalSeconds >= config.HeartbeatSeconds)
            {
                RefreshVirtualUsers(config);
                await SafeHeartbeat(api, config, "idle", null);
                lastHeartbeat = DateTimeOffset.Now;
            }

            AgentTask? task = null;
            try
            {
                EnsureConfiguredDeviceConnected(config);
                task = await api.GetNextTask(config.AgentId);
                if (task == null)
                {
                    Console.WriteLine($"No task. Sleeping {config.PollSeconds}s...");
                    await Task.Delay(TimeSpan.FromSeconds(config.PollSeconds), token);
                    continue;
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"Poll loop failed before claiming a task: {ex.Message}");
                await SafeHeartbeat(api, config, "error", ex.Message);
                await Task.Delay(TimeSpan.FromSeconds(config.PollSeconds), token);
                continue;
            }

            Console.WriteLine($"Claimed task {task.TaskId}: {task.TaskType}");
            var taskLog = new TaskLogBuffer();
            using var taskLogScope = AgentTaskLogs.Register(config.AgentId, taskLog);
            LogTask(config, taskLog, $"Claimed task {task.TaskId}: {task.TaskType}");
            try
            {
                EnsureConfiguredDeviceConnected(config);
                await api.Heartbeat(config, "running", null);
                using var runningHeartbeatCts = CancellationTokenSource.CreateLinkedTokenSource(token);
                var runningHeartbeatTask = RunRunningHeartbeatLoop(api, config, task.TaskId, runningHeartbeatCts.Token);
                object result;
                try
                {
                    result = await ExecuteTask(config, task, api, token);
                }
                finally
                {
                    runningHeartbeatCts.Cancel();
                    await IgnoreCancellation(runningHeartbeatTask);
                }
                LogTask(config, taskLog, $"Task {task.TaskId} succeeded.");
                await SafeUploadTaskLog(api, config.AgentId, task.TaskId, "succeeded", taskLog);
                await SafeCompleteTask(api, config.AgentId, task.TaskId, "succeeded", WithTaskLog(result, taskLog), null);
                completedTasks++;
            }
            catch (OperationCanceledException ex) when (token.IsCancellationRequested)
            {
                Console.Error.WriteLine($"Task {task.TaskId} cancelled: {ex.Message}");
                LogTask(config, taskLog, $"Task {task.TaskId} cancelled because device worker stopped.");
                await SafeHeartbeat(api, config, "error", "device disconnected or worker stopped");
                await SafeUploadTaskLog(api, config.AgentId, task.TaskId, "failed", taskLog);
                await SafeCompleteTask(api, config.AgentId, task.TaskId, "failed", WithTaskLog(new { message = "device disconnected or worker stopped" }, taskLog), "device disconnected or worker stopped");
                throw;
            }
            catch (DeviceNotConnectedException ex)
            {
                Console.Error.WriteLine($"Task {task.TaskId} stopped: {ex.Message}");
                LogTask(config, taskLog, $"Task {task.TaskId} stopped: {ex.Message}");
                await SafeHeartbeat(api, config, "no_device", ex.Message);
                await SafeUploadTaskLog(api, config.AgentId, task.TaskId, "failed", taskLog);
                await SafeCompleteTask(api, config.AgentId, task.TaskId, "failed", WithTaskLog(new { message = "device_not_connected" }, taskLog), ex.Message);
                completedTasks++;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"Task {task.TaskId} failed: {ex}");
                LogTask(config, taskLog, $"Task {task.TaskId} failed: {ex.Message}");
                await SafeHeartbeat(api, config, "error", ex.Message);
                await SafeUploadTaskLog(api, config.AgentId, task.TaskId, "failed", taskLog);
                await SafeCompleteTask(api, config.AgentId, task.TaskId, "failed", WithTaskLog(new { message = "failed" }, taskLog), ex.Message);
                completedTasks++;
            }

            if (config.MaxTasks > 0 && completedTasks >= config.MaxTasks)
            {
                Console.WriteLine($"MaxTasks={config.MaxTasks} reached. Exiting poll mode.");
                return;
            }
        }
    }

    private static async Task RunRunningHeartbeatLoop(
        AgentApi api,
        AgentConfig config,
        string taskId,
        CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(Math.Max(5, config.HeartbeatSeconds)), token);
                if (token.IsCancellationRequested)
                {
                    return;
                }

                await SafeHeartbeat(api, config, "running", null);
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                return;
            }
        }
    }

    private static async Task IgnoreCancellation(Task task)
    {
        try
        {
            await task;
        }
        catch (OperationCanceledException)
        {
            // Expected when the task heartbeat loop is stopped.
        }
    }

    private static Dictionary<string, object?> WithTaskLog(object result, TaskLogBuffer taskLog)
    {
        var merged = JsonSerializer.Deserialize<Dictionary<string, object?>>(
                         JsonSerializer.Serialize(result))
                     ?? new Dictionary<string, object?>();
        merged["agent_log_tail"] = taskLog.Snapshot();
        merged["agent_log_line_count"] = taskLog.TotalCount;
        merged["forwarded_event_counts"] = taskLog.EventCountsSnapshot();
        merged["accepted_event_counts"] = taskLog.AcceptedEventCountsSnapshot();
        merged["rejected_event_counts"] = taskLog.RejectedEventCountsSnapshot();
        merged["forwarded_events"] = taskLog.ForwardedEventsSnapshot();
        return merged;
    }

    private static void LogTask(AgentConfig config, TaskLogBuffer taskLog, string message)
    {
        taskLog.Append($"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} agent_id={config.AgentId} adb_serial={config.Serial} {message}");
    }

    private static void EnsureConfiguredDeviceConnected(AgentConfig config)
    {
        if (!IsConfiguredDeviceConnected(config, out var message))
        {
            throw new DeviceNotConnectedException(message);
        }
    }

    private static bool IsConfiguredDeviceConnected(AgentConfig config, out string message)
    {
        string[] devices;
        try
        {
            devices = Adb.ListDevices(config.AdbPath);
        }
        catch (Exception ex)
        {
            message = $"unable_to_scan_adb_devices: {ex.Message}";
            return false;
        }

        if (string.IsNullOrWhiteSpace(config.Serial))
        {
            var hasAnyDevice = devices.Length > 0;
            message = hasAnyDevice ? "" : "no adb device connected";
            return hasAnyDevice;
        }

        var connected = devices.Contains(config.Serial, StringComparer.OrdinalIgnoreCase);
        message = connected
            ? ""
            : $"device_not_connected adb_serial={config.Serial}";
        return connected;
    }

    private static async Task SafeRegister(AgentApi api, AgentConfig config)
    {
        try
        {
            await api.Register(config);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Unable to register agent: {ex.Message}");
        }
    }

    private static async Task SafeHeartbeat(AgentApi api, AgentConfig config, string status, string? lastError)
    {
        try
        {
            await api.Heartbeat(config, status, lastError);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Unable to send heartbeat: {ex.Message}");
        }
    }

    private static async Task SafeCompleteTask(
        AgentApi api,
        string agentId,
        string taskId,
        string status,
        object result,
        string? errorMessage)
    {
        try
        {
            await api.CompleteTask(agentId, taskId, status, result, errorMessage);
            Console.WriteLine($"Task {taskId} completed with status {status}.");
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Unable to complete task {taskId}: {ex.Message}");
        }
    }

    private static async Task SafeUploadTaskLog(
        AgentApi api,
        string agentId,
        string taskId,
        string status,
        TaskLogBuffer taskLog)
    {
        try
        {
            await api.UploadTaskLog(agentId, taskId, status, taskLog);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Unable to upload task log {taskId}: {ex.Message}");
        }
    }

    private static async Task<object> ExecuteTask(AgentConfig config, AgentTask task, AgentApi api, CancellationToken token)
    {
        RefreshVirtualUsers(config);
        var virtualUserIds = ResolveVirtualUserIds(config, task);
        var waitSeconds = task.WaitSeconds > 0 ? task.WaitSeconds : config.WaitSeconds;
        OriginalAppResult? originalAppResult = null;

        switch (task.TaskType)
        {
            case "discover_shops":
                if (task.IncludeOriginalApp)
                {
                    originalAppResult = await TryRunOriginalApp(config, api, WorkbenchUrl, "identity discovery", waitSeconds, true);
                }
                await RunForUsers(config, virtualUserIds, WorkbenchUrl, "identity discovery", "idlefish_shop_identity", waitSeconds, token);
                return new
                {
                    message = "discovery finished",
                    virtual_user_mode = task.VirtualUserMode,
                    virtual_user_ids = virtualUserIds,
                    discovered_count = virtualUserIds.Length + (originalAppResult?.Uploaded == true ? 1 : 0),
                    include_original_app = task.IncludeOriginalApp,
                    original_app_status = originalAppResult?.Status,
                    original_app_error = originalAppResult?.ErrorMessage
                };
            case "collect_service_scores":
                if (task.IncludeOriginalApp)
                {
                    originalAppResult = await TryRunOriginalApp(config, api, ServicePointsUrl, "service score collection", waitSeconds, false);
                }
                await RunForUsers(config, virtualUserIds, ServicePointsUrl, "service score collection", "idlefish_shop_service_score", waitSeconds, token);
                return new
                {
                    message = "service score collection finished",
                    virtual_user_mode = task.VirtualUserMode,
                    virtual_user_ids = virtualUserIds,
                    collected_count = virtualUserIds.Length + (originalAppResult?.Uploaded == true ? 1 : 0),
                    include_original_app = task.IncludeOriginalApp,
                    original_app_status = originalAppResult?.Status,
                    original_app_error = originalAppResult?.ErrorMessage
                };
            default:
                throw new InvalidOperationException($"Unsupported task_type: {task.TaskType}");
        }
    }

    private static async Task<OriginalAppResult> TryRunOriginalApp(
        AgentConfig config,
        AgentApi api,
        string url,
        string label,
        int waitSeconds,
        bool discovery)
    {
        try
        {
            Console.WriteLine();
            Console.WriteLine($"=== original app {label} ===");
            var result = await OriginalAppCollector.Collect(config, url, label, waitSeconds);
            if (discovery)
            {
                await api.UploadDiscoveryEvent(result.Event);
            }
            else
            {
                await api.UploadIngestEvent(result.Event);
            }

            result.Uploaded = true;
            result.Status = "succeeded";
            return result;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Original app {label} failed: {ex.Message}");
            return new OriginalAppResult
            {
                Status = "failed",
                ErrorMessage = ex.Message,
                Uploaded = false
            };
        }
    }

    private static int[] ResolveVirtualUserIds(AgentConfig config, AgentTask task)
    {
        if (task.VirtualUserIds.Length > 0 && !task.IsAllVirtualUsers)
        {
            return task.VirtualUserIds;
        }

        return config.VirtualUserIds;
    }

    private static void RefreshVirtualUsers(AgentConfig config)
    {
        if (!config.IsAllVirtualUsersMode)
        {
            return;
        }

        var detected = VirtualUserProbe.Detect(config);
        if (detected.Length == 0)
        {
            Console.WriteLine("Virtual user auto-detect returned no users; keeping configured fallback.");
            return;
        }

        if (!config.VirtualUserIds.SequenceEqual(detected))
        {
            config.VirtualUserIds = detected;
            Console.WriteLine($"Virtual users detected: {string.Join(", ", config.VirtualUserIds)}");
        }
    }

    private static async Task RunForUsers(
        AgentConfig config,
        int[] virtualUserIds,
        string url,
        string label,
        string expectedEventType,
        int waitSeconds,
        CancellationToken token)
    {
        var localBase = $"http://127.0.0.1:{config.LocalPort}";
        foreach (var userId in virtualUserIds)
        {
            token.ThrowIfCancellationRequested();
            Console.WriteLine();
            Console.WriteLine($"=== {label}: VirtualApp user {userId} ===");
            AgentTaskLogs.Append(config.AgentId, $"=== {label}: VirtualApp user {userId} ===");
            if (config.ForceStop)
            {
                Adb.Run(config.AdbPath, config.Serial, "shell", "am", "force-stop", HostPackage);
                await Task.Delay(TimeSpan.FromSeconds(2), token);
            }

            OpenVirtualUrl(
                config,
                userId,
                url,
                $"{localBase}/api/v1/ingest/events",
                $"{localBase}/api/v1/idlefish/discovery/events");

            Console.WriteLine($"Waiting up to {waitSeconds}s for {expectedEventType}...");
            AgentTaskLogs.Append(config.AgentId, $"Waiting up to {waitSeconds}s for {label} user={userId} app_slot={userId + 1} event_type={expectedEventType}");
            var matched = await AgentTaskLogs.WaitForForwardedEvent(
                config.AgentId,
                expectedEventType,
                userId,
                userId + 1,
                TimeSpan.FromSeconds(waitSeconds),
                token);
            if (matched)
            {
                AgentTaskLogs.Append(config.AgentId, $"Early finish for {label} user={userId} app_slot={userId + 1}: received {expectedEventType}");
            }
            else
            {
                AgentTaskLogs.Append(config.AgentId, $"Finished wait timeout for {label} user={userId} app_slot={userId + 1}");
            }
        }
    }

    private static void OpenVirtualUrl(
        AgentConfig config,
        int userId,
        string targetUrl,
        string ingestUrl,
        string discoveryUrl)
    {
        var openUrl = "fleamarket://webview?url=" + Uri.EscapeDataString(targetUrl);
        Adb.Run(config.AdbPath, config.Serial, "shell", "am", "start", "-n", LauncherComponent);
        Thread.Sleep(TimeSpan.FromSeconds(6));

        Adb.Run(
            config.AdbPath,
            config.Serial,
            "shell",
            "am",
            "broadcast",
            "-n",
            ReceiverComponent,
            "-a",
            ActionOpenUrl,
            "--ei",
            "user_id",
            userId.ToString(),
            "--es",
            "package_name",
            TargetPackage,
            "--es",
            "intent_action",
            "android.intent.action.idlefish",
            "--es",
            "component_name",
            TargetComponent,
            "--es",
            "url",
            openUrl,
            "--es",
            "sync_endpoint",
            ingestUrl,
            "--es",
            "discovery_endpoint",
            discoveryUrl,
            "--es",
            "device_no",
            config.DeviceNo,
            "--ez",
            "sync_enabled",
            "true");
    }
}

internal sealed class AgentConfig
{
    public string Mode { get; set; } = "discover";
    public string AgentId { get; set; } = "";
    public string ServerBaseUrl { get; set; } = "http://10.6.0.10:8000";
    public string DeviceNo { get; set; } = "device_001";
    public string DeviceName { get; set; } = "";
    public string Serial { get; set; } = "";
    public string AdbPath { get; set; } = "";
    public string AgentToken { get; set; } = "";
    public bool AutoDeviceNoBySerial { get; set; } = true;
    public string DeviceMapPath { get; set; } = "idlefish-devices.json";
    public string DeviceNoPrefix { get; set; } = "device_";
    public int LocalPort { get; set; } = 18080;
    public int WaitSeconds { get; set; } = 42;
    public int PollSeconds { get; set; } = 5;
    public int HeartbeatSeconds { get; set; } = 15;
    public int DeviceScanSeconds { get; set; } = 15;
    public int MaxTasks { get; set; } = 0;
    public bool ForceStop { get; set; } = true;
    public string VirtualUserMode { get; set; } = "all";
    public int[] VirtualUserIds { get; set; } = [0, 1];

    public bool IsAllVirtualUsersMode =>
        string.Equals(VirtualUserMode, "all", StringComparison.OrdinalIgnoreCase);

    [JsonIgnore]
    public string ConfigDirectory { get; set; } = AppContext.BaseDirectory;

    public AgentConfig Clone()
    {
        return new AgentConfig
        {
            Mode = Mode,
            AgentId = AgentId,
            ServerBaseUrl = ServerBaseUrl,
            DeviceNo = DeviceNo,
            DeviceName = DeviceName,
            Serial = Serial,
            AdbPath = AdbPath,
            AgentToken = AgentToken,
            AutoDeviceNoBySerial = AutoDeviceNoBySerial,
            DeviceMapPath = DeviceMapPath,
            DeviceNoPrefix = DeviceNoPrefix,
            LocalPort = LocalPort,
            WaitSeconds = WaitSeconds,
            PollSeconds = PollSeconds,
            HeartbeatSeconds = HeartbeatSeconds,
            DeviceScanSeconds = DeviceScanSeconds,
            MaxTasks = MaxTasks,
            ForceStop = ForceStop,
            VirtualUserMode = VirtualUserMode,
            VirtualUserIds = VirtualUserIds.ToArray(),
            ConfigDirectory = ConfigDirectory
        };
    }

    public static AgentConfig Load(string[] args)
    {
        var configPath = "idlefish-agent.json";
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (args[i] == "--config")
            {
                configPath = args[i + 1];
            }
        }

        var fullConfigPath = Path.GetFullPath(configPath);
        var config = File.Exists(fullConfigPath)
            ? JsonSerializer.Deserialize<AgentConfig>(File.ReadAllText(fullConfigPath), JsonOptions()) ?? new AgentConfig()
            : new AgentConfig();
        config.ConfigDirectory = Path.GetDirectoryName(fullConfigPath) ?? AppContext.BaseDirectory;

        for (var i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"Missing value for {arg}");
            switch (arg)
            {
                case "--config":
                    _ = Next();
                    break;
                case "--mode":
                    config.Mode = Next();
                    break;
                case "--agent-id":
                    config.AgentId = Next();
                    break;
                case "--server":
                    config.ServerBaseUrl = Next();
                    break;
                case "--device-no":
                    config.DeviceNo = Next();
                    break;
                case "--device-name":
                    config.DeviceName = Next();
                    break;
                case "--no-auto-device-no":
                    config.AutoDeviceNoBySerial = false;
                    break;
                case "--device-map":
                    config.DeviceMapPath = Next();
                    break;
                case "--serial":
                    config.Serial = Next();
                    break;
                case "--adb":
                    config.AdbPath = Next();
                    break;
                case "--users":
                    config.VirtualUserIds = Next().Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                        .Select(int.Parse)
                        .ToArray();
                    config.VirtualUserMode = "manual";
                    break;
                case "--virtual-user-mode":
                    config.VirtualUserMode = Next();
                    break;
                case "--local-port":
                    config.LocalPort = int.Parse(Next());
                    break;
                case "--wait-seconds":
                    config.WaitSeconds = int.Parse(Next());
                    break;
                case "--poll-seconds":
                    config.PollSeconds = int.Parse(Next());
                    break;
                case "--heartbeat-seconds":
                    config.HeartbeatSeconds = int.Parse(Next());
                    break;
                case "--device-scan-seconds":
                    config.DeviceScanSeconds = int.Parse(Next());
                    break;
                case "--max-tasks":
                    config.MaxTasks = int.Parse(Next());
                    break;
                case "--token":
                    config.AgentToken = Next();
                    break;
                case "--no-force-stop":
                    config.ForceStop = false;
                    break;
            }
        }

        config.ServerBaseUrl = config.ServerBaseUrl.TrimEnd('/');
        config.VirtualUserMode = string.IsNullOrWhiteSpace(config.VirtualUserMode)
            ? "all"
            : config.VirtualUserMode.Trim();
        return config;
    }

    private static JsonSerializerOptions JsonOptions() => new()
    {
        PropertyNameCaseInsensitive = true,
        WriteIndented = true
    };
}

internal sealed class DeviceWorkerState(
    string serial,
    AgentConfig config,
    CancellationTokenSource cts,
    Task task) : IDisposable
{
    public string Serial { get; } = serial;
    public AgentConfig Config { get; } = config;
    public CancellationTokenSource Cts { get; } = cts;
    public Task Task { get; } = task;

    public void Dispose()
    {
        try
        {
            Cts.Cancel();
        }
        catch
        {
            // Best effort: cancellation may already be requested.
        }
    }
}

internal sealed class DeviceNotConnectedException(string message) : Exception(message);

internal static class DeviceRegistry
{
    public static void ApplyDeviceNo(AgentConfig config)
    {
        if (!config.AutoDeviceNoBySerial)
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(config.Serial))
        {
            return;
        }

        var path = ResolveMapPath(config);
        var registry = Load(path);
        var serial = config.Serial.Trim();
        var now = DateTimeOffset.Now.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture);

        if (registry.Devices.TryGetValue(serial, out var known)
            && !string.IsNullOrWhiteSpace(known.DeviceNo))
        {
            config.DeviceNo = known.DeviceNo.Trim();
            if (!string.IsNullOrWhiteSpace(known.DeviceName))
            {
                config.DeviceName = known.DeviceName;
            }

            known.LastSeenAt = now;
            known.AdbSerial = serial;
            known.HostName = Environment.MachineName;
            Save(path, registry);
            Console.WriteLine($"Device serial mapped: {serial} -> {config.DeviceNo}");
            return;
        }

        var configuredDeviceNo = registry.Devices.Count == 0 && !string.IsNullOrWhiteSpace(config.DeviceNo)
            ? config.DeviceNo.Trim()
            : NextDeviceNo(registry, config.DeviceNoPrefix);
        registry.Devices[serial] = new DeviceRegistryEntry
        {
            AdbSerial = serial,
            DeviceNo = configuredDeviceNo,
            DeviceName = config.DeviceName,
            HostName = Environment.MachineName,
            FirstSeenAt = now,
            LastSeenAt = now
        };

        config.DeviceNo = configuredDeviceNo;
        UpdateNextDeviceNo(registry, config.DeviceNoPrefix);
        Save(path, registry);
        Console.WriteLine($"Device serial registered: {serial} -> {config.DeviceNo}");
    }

    private static string ResolveMapPath(AgentConfig config)
    {
        var configured = string.IsNullOrWhiteSpace(config.DeviceMapPath)
            ? "idlefish-devices.json"
            : config.DeviceMapPath.Trim();
        var expanded = Environment.ExpandEnvironmentVariables(configured);
        return Path.IsPathFullyQualified(expanded)
            ? expanded
            : Path.GetFullPath(Path.Combine(config.ConfigDirectory, expanded));
    }

    private static DeviceRegistryState Load(string path)
    {
        if (!File.Exists(path))
        {
            return new DeviceRegistryState();
        }

        try
        {
            return JsonSerializer.Deserialize<DeviceRegistryState>(File.ReadAllText(path), JsonOptions())
                   ?? new DeviceRegistryState();
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException($"Unable to read device map {path}: {ex.Message}", ex);
        }
    }

    private static void Save(string path, DeviceRegistryState registry)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path) ?? ".");
        File.WriteAllText(path, JsonSerializer.Serialize(registry, JsonOptions()), Encoding.UTF8);
    }

    private static string NextDeviceNo(DeviceRegistryState registry, string prefix)
    {
        UpdateNextDeviceNo(registry, prefix);
        var value = registry.NextDeviceNo <= 0 ? 1 : registry.NextDeviceNo;
        return prefix + value.ToString("000", CultureInfo.InvariantCulture);
    }

    private static void UpdateNextDeviceNo(DeviceRegistryState registry, string prefix)
    {
        var max = 0;
        foreach (var entry in registry.Devices.Values)
        {
            if (string.IsNullOrWhiteSpace(entry.DeviceNo)
                || !entry.DeviceNo.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var suffix = entry.DeviceNo[prefix.Length..];
            if (int.TryParse(suffix, NumberStyles.Integer, CultureInfo.InvariantCulture, out var number))
            {
                max = Math.Max(max, number);
            }
        }

        registry.NextDeviceNo = Math.Max(registry.NextDeviceNo, max + 1);
    }

    private static JsonSerializerOptions JsonOptions() => new()
    {
        PropertyNameCaseInsensitive = true,
        WriteIndented = true
    };
}

internal sealed class DeviceRegistryState
{
    public int NextDeviceNo { get; set; } = 1;
    public Dictionary<string, DeviceRegistryEntry> Devices { get; set; } = new(StringComparer.OrdinalIgnoreCase);
}

internal sealed class DeviceRegistryEntry
{
    public string AdbSerial { get; set; } = "";
    public string DeviceNo { get; set; } = "";
    public string DeviceName { get; set; } = "";
    public string HostName { get; set; } = "";
    public string FirstSeenAt { get; set; } = "";
    public string LastSeenAt { get; set; } = "";
}

internal static class Adb
{
    public static string ResolvePath(string configuredPath)
    {
        foreach (var candidate in CandidatePaths(configuredPath))
        {
            if (IsUsable(candidate))
            {
                return candidate;
            }
        }

        throw new FileNotFoundException(
            "Unable to find adb.exe. Put platform-tools next to IdlefishDeviceAgent.exe, configure adbPath, or add adb to PATH.");
    }

    public static string FirstDevice(string adbPath)
    {
        var devices = ListDevices(adbPath);
        if (devices.Length > 0)
        {
            return devices[0];
        }

        throw new InvalidOperationException("No adb device in device state.");
    }

    public static string[] ListDevices(string adbPath)
    {
        var result = RunForOutput(adbPath, "devices", "-l");
        var devices = new List<string>();
        foreach (var line in result.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (line.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var parts = Regex.Split(line.Trim(), "\\s+");
            if (parts.Length >= 2 && string.Equals(parts[1], "device", StringComparison.Ordinal))
            {
                devices.Add(parts[0]);
            }
        }

        return devices
            .Where(serial => !string.IsNullOrWhiteSpace(serial))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    public static void Run(string adbPath, string serial, params string[] args)
    {
        var allArgs = new List<string>();
        if (!string.IsNullOrWhiteSpace(serial))
        {
            allArgs.Add("-s");
            allArgs.Add(serial);
        }

        allArgs.AddRange(args);
        var result = RunProcess(adbPath, allArgs);
        Console.Write(result.Stdout);
        if (!string.IsNullOrWhiteSpace(result.Stderr))
        {
            Console.Error.Write(result.Stderr);
        }

        if (result.ExitCode != 0)
        {
            throw new InvalidOperationException($"adb failed with exit code {result.ExitCode}: {string.Join(' ', args)}");
        }
    }

    public static string RunForOutput(string adbPath, params string[] args)
    {
        var result = RunProcess(adbPath, args);
        if (result.ExitCode != 0)
        {
            throw new InvalidOperationException($"adb failed with exit code {result.ExitCode}: {result.Stderr}");
        }

        return result.Stdout;
    }

    public static string RunForDeviceOutput(string adbPath, string serial, params string[] args)
    {
        var allArgs = new List<string>();
        if (!string.IsNullOrWhiteSpace(serial))
        {
            allArgs.Add("-s");
            allArgs.Add(serial);
        }

        allArgs.AddRange(args);
        var result = RunProcess(adbPath, allArgs);
        if (result.ExitCode != 0)
        {
            throw new InvalidOperationException($"adb failed with exit code {result.ExitCode}: {result.Stderr}");
        }

        return result.Stdout;
    }

    private static ProcessResult RunProcess(string fileName, IEnumerable<string> args)
    {
        var argList = args.ToArray();
        var startInfo = new ProcessStartInfo(fileName)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false
        };
        foreach (var arg in argList)
        {
            startInfo.ArgumentList.Add(arg);
        }

        using var process = Process.Start(startInfo) ?? throw new InvalidOperationException($"Unable to start {fileName}");
        var stdoutTask = process.StandardOutput.ReadToEndAsync();
        var stderrTask = process.StandardError.ReadToEndAsync();
        var timeout = TimeSpan.FromSeconds(90);
        if (!process.WaitForExit(timeout))
        {
            try
            {
                process.Kill(entireProcessTree: true);
            }
            catch
            {
                // Best effort: the process may have already exited while timing out.
            }

            throw new TimeoutException($"{fileName} {string.Join(" ", argList)} timed out after {timeout.TotalSeconds:0}s.");
        }

        Task.WaitAll(new Task[] { stdoutTask, stderrTask }, TimeSpan.FromSeconds(5));
        var stdout = stdoutTask.IsCompletedSuccessfully ? stdoutTask.Result : "";
        var stderr = stderrTask.IsCompletedSuccessfully ? stderrTask.Result : "";
        return new ProcessResult(process.ExitCode, stdout, stderr);
    }

    private sealed record ProcessResult(int ExitCode, string Stdout, string Stderr);

    private static IEnumerable<string> CandidatePaths(string configuredPath)
    {
        if (!string.IsNullOrWhiteSpace(configuredPath))
        {
            yield return ExpandPath(configuredPath);
        }

        var baseDir = AppContext.BaseDirectory;
        yield return Path.Combine(baseDir, "platform-tools", "adb.exe");
        yield return Path.Combine(baseDir, "adb.exe");
        yield return "adb";

        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (!string.IsNullOrWhiteSpace(localAppData))
        {
            yield return Path.Combine(localAppData, "Android", "Sdk", "platform-tools", "adb.exe");
        }

        var androidHome = Environment.GetEnvironmentVariable("ANDROID_HOME");
        if (!string.IsNullOrWhiteSpace(androidHome))
        {
            yield return Path.Combine(androidHome, "platform-tools", "adb.exe");
        }

        var androidSdkRoot = Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
        if (!string.IsNullOrWhiteSpace(androidSdkRoot))
        {
            yield return Path.Combine(androidSdkRoot, "platform-tools", "adb.exe");
        }
    }

    private static string ExpandPath(string path)
    {
        var expanded = Environment.ExpandEnvironmentVariables(path);
        if (Path.IsPathFullyQualified(expanded))
        {
            return expanded;
        }

        return Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, expanded));
    }

    private static bool IsUsable(string path)
    {
        try
        {
            var result = RunProcess(path, ["version"]);
            return result.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }
}

internal static class VirtualUserProbe
{
    private const string ReceiverComponent = "com.carlos.multiapp/com.carlos.home.idlefish.IdlefishDeepLinkReceiver";
    private const string TargetPackage = "com.taobao.idlefish";
    private const string ActionListVirtualUsers = "com.carlos.multiapp.IDLEFISH_LIST_VIRTUAL_USERS";
    private const string AppPackage = "com.carlos.multiapp";
    private const string SnapshotPath = "files/idlefish_virtual_users.json";
    private const string LegacySnapshotPath = "/sdcard/Android/data/com.carlos.multiapp/files/idlefish_virtual_users.json";

    public static int[] Detect(AgentConfig config)
    {
        try
        {
            Adb.Run(
                config.AdbPath,
                config.Serial,
                "shell",
                "am",
                "broadcast",
                "-n",
                ReceiverComponent,
                "-a",
                ActionListVirtualUsers,
                "--es",
                "package_name",
                TargetPackage);
            Thread.Sleep(TimeSpan.FromMilliseconds(500));

            var json = ReadSnapshot(config).Trim();
            if (string.IsNullOrWhiteSpace(json))
            {
                return [];
            }

            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (root.TryGetProperty("ok", out var ok)
                && ok.ValueKind is JsonValueKind.False)
            {
                Console.WriteLine($"Virtual user auto-detect failed on device: {GetString(root, "error")}");
                return [];
            }

            if (!root.TryGetProperty("virtual_user_ids", out var users)
                || users.ValueKind != JsonValueKind.Array)
            {
                return [];
            }

            var values = new SortedSet<int>();
            foreach (var user in users.EnumerateArray())
            {
                if (user.ValueKind == JsonValueKind.Number && user.TryGetInt32(out var value))
                {
                    values.Add(value);
                }
            }

            return values.ToArray();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Virtual user auto-detect unavailable: {ex.Message}");
            return [];
        }
    }

    private static string ReadSnapshot(AgentConfig config)
    {
        try
        {
            return Adb.RunForDeviceOutput(
                config.AdbPath,
                config.Serial,
                "shell",
                "run-as",
                AppPackage,
                "cat",
                SnapshotPath);
        }
        catch
        {
            return Adb.RunForDeviceOutput(config.AdbPath, config.Serial, "shell", "cat", LegacySnapshotPath);
        }
    }

    private static string GetString(JsonElement element, string name)
    {
        return element.ValueKind == JsonValueKind.Object
               && element.TryGetProperty(name, out var property)
               && property.ValueKind == JsonValueKind.String
            ? property.GetString() ?? ""
            : "";
    }
}

internal sealed class OriginalAppResult
{
    public string Status { get; set; } = "pending";
    public bool Uploaded { get; set; }
    public string? ErrorMessage { get; set; }
    public Dictionary<string, object?> Event { get; set; } = new();
}

internal static class OriginalAppCollector
{
    private const string TargetPackage = "com.taobao.idlefish";
    private const string TargetActivity = "com.taobao.idlefish/com.taobao.idlefish.webview.WebHybridActivity";

    public static async Task<OriginalAppResult> Collect(
        AgentConfig config,
        string targetUrl,
        string label,
        int waitSeconds)
    {
        if (config.ForceStop)
        {
            Adb.Run(config.AdbPath, config.Serial, "shell", "am", "force-stop", TargetPackage);
            await Task.Delay(TimeSpan.FromSeconds(2));
        }

        OpenOriginalUrl(config, targetUrl);
        Console.WriteLine($"Waiting {waitSeconds}s for original app {label}...");
        await Task.Delay(TimeSpan.FromSeconds(waitSeconds));

        var snapshot = CaptureUiSnapshot(config);
        var isDiscovery = targetUrl.Contains("fish-pro-workbench", StringComparison.OrdinalIgnoreCase);
        var pageUrl = snapshot.Urls.LastOrDefault(url => url.Contains("m.goofish.com", StringComparison.OrdinalIgnoreCase))
            ?? targetUrl;
        var eventPayload = isDiscovery
            ? BuildIdentityEvent(config, snapshot, pageUrl)
            : BuildServiceScoreEvent(config, snapshot, pageUrl);

        return new OriginalAppResult
        {
            Status = "captured",
            Event = eventPayload
        };
    }

    private static void OpenOriginalUrl(AgentConfig config, string targetUrl)
    {
        var openUrl = "fleamarket://webview?url=" + Uri.EscapeDataString(targetUrl);
        Adb.Run(
            config.AdbPath,
            config.Serial,
            "shell",
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            openUrl,
            "-p",
            TargetPackage);
    }

    private static UiSnapshot CaptureUiSnapshot(AgentConfig config)
    {
        var window = Adb.RunForDeviceOutput(config.AdbPath, config.Serial, "shell", "dumpsys", "window");
        var activity = Adb.RunForDeviceOutput(config.AdbPath, config.Serial, "shell", "dumpsys", "activity", TargetPackage);
        var uiRaw = Adb.RunForDeviceOutput(config.AdbPath, config.Serial, "exec-out", "uiautomator", "dump", "/dev/tty");
        var xml = ExtractXml(uiRaw);
        var texts = ExtractUiTexts(xml);
        if (texts.Count == 0)
        {
            throw new InvalidOperationException("uiautomator did not expose readable original-app page text");
        }

        return new UiSnapshot
        {
            CurrentActivity = FindCurrentActivity(window),
            Urls = FindUrls(activity),
            UiTexts = texts,
            RawXml = xml
        };
    }

    private static Dictionary<string, object?> BuildIdentityEvent(AgentConfig config, UiSnapshot snapshot, string pageUrl)
    {
        var lines = snapshot.UiTexts;
        var identity = new Dictionary<string, object?>
        {
            ["shop_name"] = FindWorkbenchShopName(lines),
            ["category"] = FindWorkbenchCategory(lines),
            ["service_score"] = FindWorkbenchServiceScore(lines)
        };

        if (identity["shop_name"] == null && identity["service_score"] == null)
        {
            throw new InvalidOperationException("original app workbench text did not expose shop identity");
        }

        return BuildEvent(
            config,
            "idlefish_shop_identity",
            pageUrl,
            snapshot,
            new Dictionary<string, object?>
            {
                ["category"] = identity["category"],
                ["service_score"] = identity["service_score"]
            },
            identity);
    }

    private static Dictionary<string, object?> BuildServiceScoreEvent(AgentConfig config, UiSnapshot snapshot, string pageUrl)
    {
        var lines = snapshot.UiTexts;
        var metrics = new Dictionary<string, object?>
        {
            ["category"] = FindCategory(lines),
            ["updated_date"] = FindUpdatedDate(lines),
            ["service_score"] = FindServiceScore(lines),
            ["service_score_peer_status"] = FindFirstPeerStatus(lines),
            ["item_quality_score"] = NumberAfterLabel(lines, LabelItemQuality()),
            ["response_speed_score"] = NumberAfterLabel(lines, LabelResponseSpeed()),
            ["logistics_score"] = NumberAfterLabel(lines, LabelLogistics()),
            ["after_sales_score"] = NumberAfterLabel(lines, LabelAfterSales()),
            ["quality_refund_rate_percent"] = PercentAfterLabel(lines, LabelQualityRefund()),
            ["punished_item_ratio_percent"] = PercentAfterLabel(lines, LabelPunishedItem()),
            ["risk_item_ratio_percent"] = PercentAfterLabel(lines, LabelRiskItem()),
            ["description_service_coverage_percent"] = PercentAfterLabel(lines, LabelDescriptionCoverage()),
            ["thirty_minute_response_rate_percent"] = PercentAfterLabel(lines, LabelThirtyMinuteResponse()),
            ["five_minute_response_rate_percent"] = PercentAfterLabel(lines, LabelFiveMinuteResponse()),
            ["average_response_time"] = FirstMatch(lines, "^\\d+" + Regex.Escape(LabelMinute()) + "$"),
            ["delivery_refund_rate_percent"] = PercentAfterLabel(lines, LabelDeliveryRefund()),
            ["forty_eight_hour_delivery_rate_percent"] = PercentAfterLabel(lines, LabelFortyEightHourDelivery()),
            ["fast_delivery_coverage_percent"] = PercentAfterLabel(lines, LabelFastDeliveryCoverage()),
            ["complaint_confirmed_count"] = FirstMatch(lines, "^\\d+" + Regex.Escape(LabelCountUnit()) + "$"),
            ["extra_score"] = NumberAfterLabel(lines, LabelExtraScore())
        };

        if (metrics["service_score"] == null)
        {
            throw new InvalidOperationException("original app service page text did not expose service_score");
        }

        return BuildEvent(config, "idlefish_shop_service_score", pageUrl, snapshot, metrics, null);
    }

    private static Dictionary<string, object?> BuildEvent(
        AgentConfig config,
        string eventType,
        string pageUrl,
        UiSnapshot snapshot,
        Dictionary<string, object?> metrics,
        Dictionary<string, object?>? identity)
    {
        var account = new Dictionary<string, object?>
        {
            ["device_no"] = config.DeviceNo,
            ["device_id"] = config.Serial,
            ["device_serial"] = config.Serial,
            ["collector_type"] = "uiautomator",
            ["app_slot"] = 0,
            ["package_name"] = TargetPackage,
            ["virtual_user_id"] = null,
            ["shop_id"] = null,
            ["account_alias"] = null
        };

        var payload = new Dictionary<string, object?>
        {
            ["event_type"] = eventType,
            ["schema_version"] = 1,
            ["event_id"] = Guid.NewGuid().ToString(),
            ["captured_at"] = DateTimeOffset.Now.ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture),
            ["source"] = "uiautomator",
            ["device_no"] = config.DeviceNo,
            ["device_id"] = config.Serial,
            ["collector_type"] = "uiautomator",
            ["app_slot"] = 0,
            ["virtual_user_id"] = null,
            ["account"] = account,
            ["page"] = new Dictionary<string, object?>
            {
                ["activity"] = string.IsNullOrWhiteSpace(snapshot.CurrentActivity) ? TargetActivity : snapshot.CurrentActivity,
                ["url"] = pageUrl
            },
            ["metrics"] = metrics,
            ["raw"] = new Dictionary<string, object?>
            {
                ["ui_texts"] = snapshot.UiTexts,
                ["api"] = null,
                ["payload_hash"] = null,
                ["xml_text_length"] = snapshot.RawXml.Length
            }
        };

        if (identity != null)
        {
            payload["identity"] = identity;
        }

        return payload;
    }

    private static string ExtractXml(string raw)
    {
        var match = Regex.Match(raw, "(?s)<\\?xml.*</hierarchy>");
        if (!match.Success)
        {
            throw new InvalidOperationException("uiautomator dump did not contain hierarchy XML");
        }

        return match.Value;
    }

    private static List<string> ExtractUiTexts(string xml)
    {
        var document = XDocument.Parse(xml);
        var values = new List<string>();
        foreach (var element in document.Descendants())
        {
            AddAttributeValue(values, element, "text");
            AddAttributeValue(values, element, "content-desc");
        }

        return values;
    }

    private static void AddAttributeValue(List<string> values, XElement element, string name)
    {
        var value = element.Attribute(name)?.Value?.Trim();
        if (!string.IsNullOrWhiteSpace(value))
        {
            values.Add(value);
        }
    }

    private static string FindCurrentActivity(string window)
    {
        foreach (var pattern in new[] { "mCurrentFocus=.*? ([^/\\s]+/[^}\\s]+)", "mFocusedApp=.*? ([^/\\s]+/[^}\\s]+)" })
        {
            var match = Regex.Match(window, pattern);
            if (match.Success)
            {
                return match.Groups[1].Value;
            }
        }

        return TargetActivity;
    }

    private static List<string> FindUrls(string text)
    {
        return Regex.Matches(text, "https?://[^\\s,}\\]]+")
            .Select(match => match.Value)
            .Distinct()
            .ToList();
    }

    private static string? FindWorkbenchShopName(IReadOnlyList<string> lines)
    {
        var settingsIndex = IndexOfEquals(lines, LabelSettings());
        if (settingsIndex >= 0)
        {
            var candidate = FirstLikelyShopName(lines, settingsIndex + 1, Math.Min(lines.Count, settingsIndex + 5));
            if (candidate != null)
            {
                return candidate;
            }
        }

        var workbenchIndex = IndexOfContains(lines, LabelWorkbench());
        return workbenchIndex >= 0
            ? FirstLikelyShopName(lines, workbenchIndex + 1, Math.Min(lines.Count, workbenchIndex + 8))
            : null;
    }

    private static string? FirstLikelyShopName(IReadOnlyList<string> lines, int startInclusive, int endExclusive)
    {
        for (var i = startInclusive; i < endExclusive; i++)
        {
            var line = lines[i];
            if (line.Length == 0
                || line == LabelSettings()
                || line == LabelSubscribeService()
                || line == LabelWorkbench()
                || line.Contains(LabelServiceScore(), StringComparison.Ordinal)
                || line.Contains(LabelJoinClub(), StringComparison.Ordinal)
                || line.Contains(LabelYesterdayData(), StringComparison.Ordinal)
                || line.Contains(LabelMiddleDot(), StringComparison.Ordinal)
                || NumberPattern().IsMatch(line))
            {
                continue;
            }

            return line;
        }

        return null;
    }

    private static string? FindWorkbenchCategory(IReadOnlyList<string> lines)
    {
        for (var i = 0; i < lines.Count; i++)
        {
            var line = lines[i];
            var index = line.IndexOf(LabelMiddleDot(), StringComparison.Ordinal);
            if (index > 0)
            {
                return line[..index].Trim();
            }
            if (index == 0 && i > 0)
            {
                return lines[i - 1];
            }
        }

        return null;
    }

    private static double? FindWorkbenchServiceScore(IReadOnlyList<string> lines)
    {
        var index = IndexOfContains(lines, LabelServiceScore());
        return index >= 0 ? FirstNumberAfter(lines, index + 1, Math.Min(lines.Count, index + 5)) : null;
    }

    private static string? FindCategory(IReadOnlyList<string> lines)
    {
        var prefixes = new[] { LabelCategory() + ":", LabelCategory() + "：" };
        foreach (var line in lines)
        {
            foreach (var prefix in prefixes)
            {
                if (line.StartsWith(prefix, StringComparison.Ordinal))
                {
                    return line[prefix.Length..].Trim();
                }
            }
        }

        return null;
    }

    private static string? FindUpdatedDate(IReadOnlyList<string> lines)
    {
        foreach (var line in lines)
        {
            if (!line.Contains(LabelUpdated(), StringComparison.Ordinal))
            {
                continue;
            }

            var match = Regex.Match(line, "(\\d{4})\\.(\\d{2})\\.(\\d{1,2})");
            if (match.Success)
            {
                return string.Format(
                    CultureInfo.InvariantCulture,
                    "{0}-{1}-{2:00}",
                    match.Groups[1].Value,
                    match.Groups[2].Value,
                    int.Parse(match.Groups[3].Value, CultureInfo.InvariantCulture));
            }
        }

        return null;
    }

    private static double? FindServiceScore(IReadOnlyList<string> lines)
    {
        var updatedIndex = IndexOfContains(lines, LabelUpdated());
        if (updatedIndex >= 0)
        {
            var score = FirstNumberAfter(lines, updatedIndex + 1, Math.Min(lines.Count, updatedIndex + 5));
            if (score != null)
            {
                return score;
            }
        }

        var scoreLabel = IndexOfEquals(lines, LabelServiceScore());
        return scoreLabel >= 0 ? FirstNumberAfter(lines, scoreLabel + 1, lines.Count) : null;
    }

    private static string? FindFirstPeerStatus(IReadOnlyList<string> lines)
    {
        var combined = new Regex("^(" + Regex.Escape(LabelHigherThan()) + "|"
                                 + Regex.Escape(LabelExceeding()) + "|"
                                 + Regex.Escape(LabelLagging()) + ")\\d+%"
                                 + Regex.Escape(LabelPeer()) + "$");
        foreach (var line in lines)
        {
            if (combined.IsMatch(line))
            {
                return line;
            }
        }

        for (var i = 0; i + 2 < lines.Count; i++)
        {
            if ((lines[i] == LabelHigherThan() || lines[i] == LabelExceeding() || lines[i] == LabelLagging())
                && PercentPattern().IsMatch(lines[i + 1])
                && lines[i + 2] == LabelPeer())
            {
                return lines[i] + lines[i + 1] + lines[i + 2];
            }
        }

        return null;
    }

    private static double? NumberAfterLabel(IReadOnlyList<string> lines, string label)
    {
        var index = IndexOfEquals(lines, label);
        return index >= 0 ? FirstNumberAfter(lines, index + 1, lines.Count) : null;
    }

    private static int? PercentAfterLabel(IReadOnlyList<string> lines, string label)
    {
        var index = IndexOfEquals(lines, label);
        if (index < 0)
        {
            return null;
        }

        for (var i = index + 1; i < lines.Count; i++)
        {
            var match = PercentPattern().Match(lines[i]);
            if (match.Success)
            {
                return int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture);
            }
            if (i + 1 < lines.Count
                && Regex.IsMatch(lines[i], "^\\d+$")
                && lines[i + 1] == "%")
            {
                return int.Parse(lines[i], CultureInfo.InvariantCulture);
            }
        }

        return null;
    }

    private static double? FirstNumberAfter(IReadOnlyList<string> lines, int startInclusive, int endExclusive)
    {
        for (var i = startInclusive; i < endExclusive; i++)
        {
            if (NumberPattern().IsMatch(lines[i]))
            {
                return double.Parse(lines[i], CultureInfo.InvariantCulture);
            }
        }

        return null;
    }

    private static string? FirstMatch(IReadOnlyList<string> lines, string pattern)
    {
        var compiled = new Regex(pattern);
        return lines.FirstOrDefault(line => compiled.IsMatch(line));
    }

    private static int IndexOfEquals(IReadOnlyList<string> lines, string expected)
    {
        for (var i = 0; i < lines.Count; i++)
        {
            if (lines[i] == expected)
            {
                return i;
            }
        }

        return -1;
    }

    private static int IndexOfContains(IReadOnlyList<string> lines, string expected)
    {
        for (var i = 0; i < lines.Count; i++)
        {
            if (lines[i].Contains(expected, StringComparison.Ordinal))
            {
                return i;
            }
        }

        return -1;
    }

    private static Regex NumberPattern() => new("^\\d+(?:\\.\\d+)?$");
    private static Regex PercentPattern() => new("^(\\d+)%$");

    private static string Text(params int[] codePoints)
    {
        var builder = new StringBuilder();
        foreach (var codePoint in codePoints)
        {
            builder.Append(char.ConvertFromUtf32(codePoint));
        }

        return builder.ToString();
    }

    private static string LabelServiceScore() => Text(0x5C0F, 0x94FA, 0x670D, 0x52A1, 0x5206);
    private static string LabelWorkbench() => Text(0x9C7C, 0x5C0F, 0x94FA, 0x5DE5, 0x4F5C, 0x53F0);
    private static string LabelSettings() => Text(0x8BBE, 0x7F6E);
    private static string LabelSubscribeService() => Text(0x8BA2, 0x9605, 0x670D, 0x52A1, 0x53F7);
    private static string LabelJoinClub() => Text(0x52A0, 0x5165, 0x8D85, 0x8D5E, 0x4FF1, 0x4E50, 0x90E8);
    private static string LabelYesterdayData() => Text(0x5C0F, 0x94FA, 0x6628, 0x65E5, 0x6570, 0x636E);
    private static string LabelMiddleDot() => Text(0x00B7);
    private static string LabelCategory() => Text(0x8003, 0x6838, 0x7C7B, 0x76EE);
    private static string LabelUpdated() => Text(0x66F4, 0x65B0);
    private static string LabelHigherThan() => Text(0x9AD8, 0x4E8E);
    private static string LabelExceeding() => Text(0x8D85, 0x8FC7);
    private static string LabelLagging() => Text(0x843D, 0x540E);
    private static string LabelPeer() => Text(0x540C, 0x884C);
    private static string LabelItemQuality() => Text(0x5B9D, 0x8D1D, 0x8D28, 0x91CF);
    private static string LabelResponseSpeed() => Text(0x54CD, 0x5E94, 0x901F, 0x5EA6);
    private static string LabelLogistics() => Text(0x7269, 0x6D41, 0x4F53, 0x9A8C);
    private static string LabelAfterSales() => Text(0x552E, 0x540E, 0x4F53, 0x9A8C);
    private static string LabelQualityRefund() => Text(0x54C1, 0x8D28, 0x539F, 0x56E0, 0x9000, 0x6B3E, 0x7387);
    private static string LabelPunishedItem() => Text(0x5904, 0x7F5A, 0x5B9D, 0x8D1D, 0x5360, 0x6BD4);
    private static string LabelRiskItem() => Text(0x98CE, 0x9669, 0x5B9D, 0x8D1D, 0x5360, 0x6BD4);
    private static string LabelDescriptionCoverage() => Text(0x300C, 0x771F, 0x5B9E, 0x63CF, 0x8FF0, 0x300D, 0x76F8, 0x5173, 0x670D, 0x52A1, 0x8986, 0x76D6, 0x7387);
    private static string LabelThirtyMinuteResponse() => Text(0x0033, 0x0030, 0x5206, 0x949F, 0x56DE, 0x590D, 0x7387);
    private static string LabelFiveMinuteResponse() => Text(0x0035, 0x5206, 0x949F, 0x56DE, 0x590D, 0x7387);
    private static string LabelMinute() => Text(0x5206, 0x949F);
    private static string LabelDeliveryRefund() => Text(0x53D1, 0x8D27, 0x539F, 0x56E0, 0x9000, 0x6B3E, 0x7387);
    private static string LabelFortyEightHourDelivery() => Text(0x0034, 0x0038, 0x5C0F, 0x65F6, 0x53D1, 0x8D27, 0x7387);
    private static string LabelFastDeliveryCoverage() => Text(0x300C, 0x6781, 0x901F, 0x53D1, 0x8D27, 0x300D, 0x670D, 0x52A1, 0x8986, 0x76D6, 0x7387);
    private static string LabelCountUnit() => Text(0x4E2A);
    private static string LabelExtraScore() => Text(0x9644, 0x52A0, 0x5206);

    private sealed class UiSnapshot
    {
        public string CurrentActivity { get; init; } = "";
        public List<string> Urls { get; init; } = [];
        public List<string> UiTexts { get; init; } = [];
        public string RawXml { get; init; } = "";
    }
}

internal sealed class AgentTask
{
    public string TaskId { get; init; } = "";
    public string TaskType { get; init; } = "";
    public string VirtualUserMode { get; init; } = "";
    public int[] VirtualUserIds { get; init; } = [];
    public bool IncludeOriginalApp { get; init; }
    public int WaitSeconds { get; init; }

    public bool IsAllVirtualUsers =>
        string.Equals(VirtualUserMode, "all", StringComparison.OrdinalIgnoreCase);

    public static AgentTask FromJson(JsonElement task)
    {
        var payload = task.TryGetProperty("payload", out var payloadElement)
            ? payloadElement
            : task.TryGetProperty("payload_json", out var payloadJsonElement)
                ? payloadJsonElement
                : default;

        if (payload.ValueKind == JsonValueKind.String)
        {
            using var payloadDocument = JsonDocument.Parse(payload.GetString() ?? "{}");
            return FromJson(task, payloadDocument.RootElement);
        }

        return FromJson(task, payload);
    }

    private static AgentTask FromJson(JsonElement task, JsonElement payload)
    {
        return new AgentTask
        {
            TaskId = GetString(task, "task_id"),
            TaskType = GetString(task, "task_type"),
            VirtualUserMode = GetString(payload, "virtual_user_mode"),
            VirtualUserIds = GetIntArray(payload, "virtual_user_ids"),
            IncludeOriginalApp = GetBool(payload, "include_original_app"),
            WaitSeconds = GetInt(payload, "wait_seconds")
        };
    }

    private static string GetString(JsonElement element, string name)
    {
        return element.ValueKind == JsonValueKind.Object
               && element.TryGetProperty(name, out var property)
               && property.ValueKind == JsonValueKind.String
            ? property.GetString() ?? ""
            : "";
    }

    private static bool GetBool(JsonElement element, string name)
    {
        return element.ValueKind == JsonValueKind.Object
               && element.TryGetProperty(name, out var property)
               && property.ValueKind is JsonValueKind.True or JsonValueKind.False
               && property.GetBoolean();
    }

    private static int GetInt(JsonElement element, string name)
    {
        return element.ValueKind == JsonValueKind.Object
               && element.TryGetProperty(name, out var property)
               && property.ValueKind == JsonValueKind.Number
               && property.TryGetInt32(out var value)
            ? value
            : 0;
    }

    private static int[] GetIntArray(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object
            || !element.TryGetProperty(name, out var property)
            || property.ValueKind != JsonValueKind.Array)
        {
            return [];
        }

        var values = new List<int>();
        foreach (var item in property.EnumerateArray())
        {
            if (item.ValueKind == JsonValueKind.Number && item.TryGetInt32(out var value))
            {
                values.Add(value);
            }
        }

        return values.ToArray();
    }
}

internal sealed class AgentApi : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        WriteIndented = false
    };

    private readonly HttpClient _client = new();
    private readonly string _baseUrl;
    private readonly string _agentToken;

    public AgentApi(AgentConfig config)
    {
        _baseUrl = config.ServerBaseUrl.TrimEnd('/');
        _agentToken = config.AgentToken;
    }

    public async Task Register(AgentConfig config)
    {
        var payload = new
        {
            agent_id = config.AgentId,
            device_no = config.DeviceNo,
            device_name = config.DeviceName,
            adb_serial = config.Serial,
            device_id = "",
            host_name = Environment.MachineName,
            host_user = Environment.UserName,
            agent_version = ThisAssemblyVersion(),
            virtual_user_mode = config.VirtualUserMode,
            virtual_user_ids = config.VirtualUserIds,
            supported_targets = new[] { "original_app", "virtualapp" },
            supported_collectors = new[] { "virtualapp_webview_js", "uiautomator" }
        };
        await PostJson("/api/v1/idlefish/agents/register", payload);
        Console.WriteLine("Agent registered.");
    }

    public async Task Heartbeat(AgentConfig config, string status, string? lastError)
    {
        var payload = new
        {
            agent_id = config.AgentId,
            device_no = config.DeviceNo,
            device_name = config.DeviceName,
            status,
            adb_serial = config.Serial,
            device_id = "",
            host_name = Environment.MachineName,
            host_user = Environment.UserName,
            agent_version = ThisAssemblyVersion(),
            virtual_user_mode = config.VirtualUserMode,
            virtual_user_ids = config.VirtualUserIds,
            supported_targets = new[] { "original_app", "virtualapp" },
            supported_collectors = new[] { "virtualapp_webview_js", "uiautomator" },
            last_error = lastError
        };
        await PostJson("/api/v1/idlefish/agents/heartbeat", payload);
    }

    public async Task<AgentTask?> GetNextTask(string agentId)
    {
        using var request = NewRequest(HttpMethod.Get, $"/api/v1/idlefish/agents/tasks/next?agent_id={Uri.EscapeDataString(agentId)}");
        using var response = await _client.SendAsync(request);
        var body = await response.Content.ReadAsStringAsync();
        response.EnsureSuccessStatusCode();

        using var document = JsonDocument.Parse(body);
        if (!document.RootElement.TryGetProperty("task", out var task)
            || task.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
        {
            return null;
        }

        return AgentTask.FromJson(task);
    }

    public async Task CompleteTask(string agentId, string taskId, string status, object result, string? errorMessage)
    {
        var payload = new
        {
            agent_id = agentId,
            status,
            result,
            error_message = errorMessage
        };
        await PostJson($"/api/v1/idlefish/agents/tasks/{Uri.EscapeDataString(taskId)}/complete", payload);
    }

    public async Task UploadTaskLog(string agentId, string taskId, string status, TaskLogBuffer taskLog)
    {
        var payload = new
        {
            agent_id = agentId,
            task_id = taskId,
            status,
            log_lines = taskLog.Snapshot(),
            line_count = taskLog.TotalCount,
            forwarded_event_counts = taskLog.EventCountsSnapshot(),
            accepted_event_counts = taskLog.AcceptedEventCountsSnapshot(),
            rejected_event_counts = taskLog.RejectedEventCountsSnapshot(),
            forwarded_events = taskLog.ForwardedEventsSnapshot(),
            uploaded_at = DateTimeOffset.Now.ToString("yyyy-MM-dd HH:mm:ss.fff zzz", CultureInfo.InvariantCulture)
        };
        await PostJson($"/api/v1/idlefish/agents/tasks/{Uri.EscapeDataString(taskId)}/logs", payload);
    }

    public async Task UploadIngestEvent(object idlefishEvent)
    {
        await PostJson("/api/v1/ingest/events", idlefishEvent);
    }

    public async Task UploadDiscoveryEvent(object idlefishEvent)
    {
        await PostJson("/api/v1/idlefish/discovery/events", idlefishEvent);
    }

    private async Task PostJson(string path, object payload)
    {
        var body = JsonSerializer.Serialize(payload, JsonOptions);
        using var request = NewRequest(HttpMethod.Post, path);
        request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await _client.SendAsync(request);
        var responseBody = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"POST {path} failed {(int)response.StatusCode}: {responseBody}");
        }
    }

    private HttpRequestMessage NewRequest(HttpMethod method, string path)
    {
        var request = new HttpRequestMessage(method, _baseUrl + path);
        if (!string.IsNullOrWhiteSpace(_agentToken))
        {
            request.Headers.TryAddWithoutValidation("x-agent-token", _agentToken);
        }
        return request;
    }

    private static string ThisAssemblyVersion()
    {
        return typeof(AgentApi).Assembly.GetName().Version?.ToString() ?? "0.1.0";
    }

    public void Dispose()
    {
        _client.Dispose();
    }
}

internal sealed class RelayServer : IDisposable
{
    private readonly HttpListener _listener = new();
    private readonly HttpClient _client = new();
    private readonly string _serverBaseUrl;
    private readonly string _agentToken;
    private readonly AgentRelayIdentity _identity;
    private CancellationTokenSource? _cts;

    public RelayServer(AgentConfig config)
    {
        _serverBaseUrl = config.ServerBaseUrl.TrimEnd('/');
        _agentToken = config.AgentToken;
        _identity = new AgentRelayIdentity(
            config.AgentId,
            config.DeviceNo,
            config.Serial,
            Environment.MachineName,
            ThisAssemblyVersion());
        _listener.Prefixes.Add($"http://127.0.0.1:{config.LocalPort}/");
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();
        _listener.Start();
        _ = Task.Run(() => Loop(_cts.Token));
    }

    private async Task Loop(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            HttpListenerContext context;
            try
            {
                context = await _listener.GetContextAsync();
            }
            catch when (token.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"Relay accept failed: {ex.Message}");
                continue;
            }

            _ = Task.Run(() => Forward(context, token), token);
        }
    }

    private async Task Forward(HttpListenerContext context, CancellationToken token)
    {
        var request = context.Request;
        var target = _serverBaseUrl + request.RawUrl;
        byte[]? requestBodyBytes = null;
        var idlefishEventRequest = ShouldEnrichIdlefishEvents(request);
        try
        {
            using var message = new HttpRequestMessage(new HttpMethod(request.HttpMethod), target);
            if (request.HasEntityBody)
            {
                using var buffer = new MemoryStream();
                await request.InputStream.CopyToAsync(buffer, token);
                requestBodyBytes = buffer.ToArray();
                if (idlefishEventRequest)
                {
                    requestBodyBytes = EnrichIdlefishEventPayload(requestBodyBytes);
                    message.Content = new ByteArrayContent(requestBodyBytes);
                    message.Content.Headers.TryAddWithoutValidation("Content-Type", "application/json; charset=utf-8");
                }
                else
                {
                    message.Content = new ByteArrayContent(requestBodyBytes);
                    if (!string.IsNullOrWhiteSpace(request.ContentType))
                    {
                        message.Content.Headers.TryAddWithoutValidation("Content-Type", request.ContentType);
                    }
                }
            }

            if (!string.IsNullOrWhiteSpace(_agentToken))
            {
                message.Headers.TryAddWithoutValidation("x-agent-token", _agentToken);
            }

            using var response = await _client.SendAsync(message, token);
            context.Response.StatusCode = (int)response.StatusCode;
            var bytes = await response.Content.ReadAsByteArrayAsync(token);
            var contentType = response.Content.Headers.ContentType?.ToString();
            if (!string.IsNullOrWhiteSpace(contentType))
            {
                context.Response.ContentType = contentType;
            }

            await context.Response.OutputStream.WriteAsync(bytes, token);
            var forwardResult = $"{request.HttpMethod} {request.RawUrl} -> {(int)response.StatusCode}";
            Console.WriteLine(forwardResult);
            AgentTaskLogs.Append(_identity.AgentId, forwardResult);
            if (!response.IsSuccessStatusCode && bytes.Length > 0)
            {
                var responseBody = Encoding.UTF8.GetString(bytes);
                AgentTaskLogs.Append(_identity.AgentId, $"Relay response body for {request.RawUrl}: {RedactForLog(responseBody)}");
            }

            if (idlefishEventRequest && requestBodyBytes is { Length: > 0 })
            {
                AgentTaskLogs.RecordEventResponse(_identity.AgentId, requestBodyBytes, (int)response.StatusCode);
            }
        }
        catch (Exception ex)
        {
            context.Response.StatusCode = 502;
            var bytes = System.Text.Encoding.UTF8.GetBytes($"relay failed: {ex.Message}");
            await context.Response.OutputStream.WriteAsync(bytes, token);
            Console.Error.WriteLine($"Relay failed for {target}: {ex.Message}");
            AgentTaskLogs.Append(_identity.AgentId, $"Relay failed for {request.RawUrl}: {ex.Message}");
            if (idlefishEventRequest && requestBodyBytes is { Length: > 0 })
            {
                AgentTaskLogs.RecordEventResponse(_identity.AgentId, requestBodyBytes, 502);
            }
        }
        finally
        {
            context.Response.Close();
        }
    }

    private bool ShouldEnrichIdlefishEvents(HttpListenerRequest request)
    {
        if (!string.Equals(request.HttpMethod, "POST", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var path = request.Url?.AbsolutePath ?? "";
        return string.Equals(path, "/api/v1/idlefish/discovery/events", StringComparison.OrdinalIgnoreCase)
               || string.Equals(path, "/api/v1/ingest/events", StringComparison.OrdinalIgnoreCase);
    }

    private byte[] EnrichIdlefishEventPayload(byte[] bytes)
    {
        var body = Encoding.UTF8.GetString(bytes);
        if (string.IsNullOrWhiteSpace(body))
        {
            return bytes;
        }

        try
        {
            var node = JsonNode.Parse(body);
            if (node == null)
            {
                return bytes;
            }

            RemoveSensitiveFields(node);
            if (node is JsonObject obj
                && obj.TryGetPropertyValue("events", out var eventsNode)
                && eventsNode is JsonArray events)
            {
                foreach (var item in events)
                {
                    if (item is JsonObject eventObj)
                    {
                        EnrichEvent(eventObj);
                        LogForwardedEvent(eventObj);
                    }
                }
            }
            else if (node is JsonObject eventObj)
            {
                EnrichEvent(eventObj);
                LogForwardedEvent(eventObj);
            }

            return Encoding.UTF8.GetBytes(node.ToJsonString(new JsonSerializerOptions
            {
                WriteIndented = false
            }));
        }
        catch (JsonException ex)
        {
            Console.Error.WriteLine($"Relay event JSON enrichment skipped: {ex.Message}");
            return bytes;
        }
    }

    private void EnrichEvent(JsonObject eventObj)
    {
        eventObj["agent_id"] = _identity.AgentId;
        eventObj["device_no"] = _identity.DeviceNo;
        eventObj["adb_serial"] = _identity.AdbSerial;
        eventObj["host_name"] = _identity.HostName;
        eventObj["agent_version"] = _identity.AgentVersion;
    }

    private static void RemoveSensitiveFields(JsonNode node)
    {
        if (node is JsonObject obj)
        {
            var remove = obj
                .Select(property => property.Key)
                .Where(IsSensitiveFieldName)
                .ToArray();
            foreach (var key in remove)
            {
                obj.Remove(key);
            }

            foreach (var property in obj.ToArray())
            {
                if (property.Value != null)
                {
                    RemoveSensitiveFields(property.Value);
                }
            }
        }
        else if (node is JsonArray array)
        {
            foreach (var item in array)
            {
                if (item != null)
                {
                    RemoveSensitiveFields(item);
                }
            }
        }
    }

    private static bool IsSensitiveFieldName(string name)
    {
        var lower = name.ToLowerInvariant();
        return lower.Contains("cookie", StringComparison.Ordinal)
               || lower.Contains("session", StringComparison.Ordinal)
               || lower.Contains("token", StringComparison.Ordinal)
               || lower.Contains("authorization", StringComparison.Ordinal)
               || lower == "auth"
               || lower.Contains("password", StringComparison.Ordinal)
               || lower == "sign"
               || lower.Contains("signature", StringComparison.Ordinal)
               || lower.Contains("secret", StringComparison.Ordinal)
               || lower == "headers";
    }

    private void LogForwardedEvent(JsonObject eventObj)
    {
        var eventType = GetString(eventObj, "event_type");
        var appSlot = GetString(eventObj, "app_slot");
        var virtualUserId = GetString(eventObj, "virtual_user_id");
        var message = "Forward idlefish event: "
                      + $"event_type={eventType} "
                      + $"device_no={_identity.DeviceNo} "
                      + $"adb_serial={_identity.AdbSerial} "
                      + $"app_slot={appSlot} "
                      + $"virtual_user_id={virtualUserId}";
        Console.WriteLine(message);
        AgentTaskLogs.RecordForwardedEvent(_identity.AgentId, message, eventType, virtualUserId, appSlot);
    }

    private static string GetString(JsonObject obj, string name)
    {
        if (!obj.TryGetPropertyValue(name, out var node) || node == null)
        {
            return "-";
        }

        if (node is JsonValue value)
        {
            return value.ToString();
        }

        return "-";
    }

    private static string RedactForLog(string line)
    {
        var redacted = Regex.Replace(
            line,
            "(cookie|session|token|authorization|auth|password|sign|signature|secret|headers?)\\s*[:=]\\s*\\S+",
            "$1=<redacted>",
            RegexOptions.IgnoreCase);
        return redacted.Length > 1000 ? redacted[..1000] + "...<truncated>" : redacted;
    }

    private static string ThisAssemblyVersion()
    {
        return typeof(RelayServer).Assembly.GetName().Version?.ToString() ?? "0.1.0";
    }

    public void Dispose()
    {
        _cts?.Cancel();
        if (_listener.IsListening)
        {
            _listener.Stop();
        }

        _listener.Close();
        _client.Dispose();
        _cts?.Dispose();
    }
}

internal sealed record AgentRelayIdentity(
    string AgentId,
    string DeviceNo,
    string AdbSerial,
    string HostName,
    string AgentVersion);

internal sealed class TaskLogBuffer
{
    private const int MaxLines = 300;
    private readonly Queue<string> _lines = new();
    private readonly Dictionary<string, int> _eventCounts = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, int> _acceptedEventCounts = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, int> _rejectedEventCounts = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<ForwardedEventLog> _forwardedEvents = [];
    private readonly object _lock = new();

    public int TotalCount { get; private set; }

    public void Append(string line)
    {
        var redacted = Redact(line);
        lock (_lock)
        {
            TotalCount++;
            _lines.Enqueue(redacted);
            while (_lines.Count > MaxLines)
            {
                _lines.Dequeue();
            }
        }
    }

    public void RecordForwardedEvent(string eventType, string virtualUserId, string appSlot)
    {
        var normalizedType = string.IsNullOrWhiteSpace(eventType) ? "-" : eventType.Trim();
        lock (_lock)
        {
            _eventCounts.TryGetValue(normalizedType, out var count);
            _eventCounts[normalizedType] = count + 1;
            _forwardedEvents.Add(new ForwardedEventLog
            {
                EventType = normalizedType,
                VirtualUserId = virtualUserId,
                AppSlot = appSlot,
                SeenAt = DateTimeOffset.Now.ToString("yyyy-MM-dd HH:mm:ss.fff zzz", CultureInfo.InvariantCulture)
            });

            if (_forwardedEvents.Count > MaxLines)
            {
                _forwardedEvents.RemoveRange(0, _forwardedEvents.Count - MaxLines);
            }
        }
    }

    public void RecordEventResponse(string eventType, bool accepted)
    {
        var normalizedType = string.IsNullOrWhiteSpace(eventType) ? "-" : eventType.Trim();
        lock (_lock)
        {
            var target = accepted ? _acceptedEventCounts : _rejectedEventCounts;
            target.TryGetValue(normalizedType, out var count);
            target[normalizedType] = count + 1;
        }
    }

    public string[] Snapshot()
    {
        lock (_lock)
        {
            return _lines.ToArray();
        }
    }

    public Dictionary<string, int> EventCountsSnapshot()
    {
        lock (_lock)
        {
            return new Dictionary<string, int>(_eventCounts, StringComparer.OrdinalIgnoreCase);
        }
    }

    public Dictionary<string, int> AcceptedEventCountsSnapshot()
    {
        lock (_lock)
        {
            return new Dictionary<string, int>(_acceptedEventCounts, StringComparer.OrdinalIgnoreCase);
        }
    }

    public Dictionary<string, int> RejectedEventCountsSnapshot()
    {
        lock (_lock)
        {
            return new Dictionary<string, int>(_rejectedEventCounts, StringComparer.OrdinalIgnoreCase);
        }
    }

    public ForwardedEventLog[] ForwardedEventsSnapshot()
    {
        lock (_lock)
        {
            return _forwardedEvents.ToArray();
        }
    }

    private static string Redact(string line)
    {
        var redacted = Regex.Replace(
            line,
            "(cookie|session|token|authorization|auth|password|sign|signature|secret|headers)\\s*[:=]\\s*\\S+",
            "$1=<redacted>",
            RegexOptions.IgnoreCase);
        return redacted.Length > 800 ? redacted[..800] + "...<truncated>" : redacted;
    }
}

internal sealed class ForwardedEventLog
{
    public string EventType { get; init; } = "";
    public string VirtualUserId { get; init; } = "";
    public string AppSlot { get; init; } = "";
    public string SeenAt { get; init; } = "";
}

internal static class AgentTaskLogs
{
    private static readonly ConcurrentDictionary<string, TaskLogBuffer> Active = new(StringComparer.OrdinalIgnoreCase);
    private static readonly ConcurrentDictionary<string, List<ForwardedEventWaiter>> Waiters = new(StringComparer.OrdinalIgnoreCase);

    public static IDisposable Register(string agentId, TaskLogBuffer buffer)
    {
        Active[agentId] = buffer;
        return new Registration(agentId);
    }

    public static void Append(string agentId, string line)
    {
        if (Active.TryGetValue(agentId, out var buffer))
        {
            buffer.Append($"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} {line}");
        }
    }

    public static void RecordForwardedEvent(
        string agentId,
        string line,
        string eventType,
        string virtualUserId,
        string appSlot)
    {
        if (Active.TryGetValue(agentId, out var buffer))
        {
            buffer.Append($"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} {line}");
            buffer.RecordForwardedEvent(eventType, virtualUserId, appSlot);
        }
        NotifyWaiters(agentId, eventType, virtualUserId, appSlot);
    }

    public static async Task<bool> WaitForForwardedEvent(
        string agentId,
        string eventType,
        int virtualUserId,
        int appSlot,
        TimeSpan timeout,
        CancellationToken token)
    {
        if (timeout <= TimeSpan.Zero)
        {
            return false;
        }

        var waiter = new ForwardedEventWaiter(eventType, virtualUserId.ToString(CultureInfo.InvariantCulture), appSlot.ToString(CultureInfo.InvariantCulture));
        var waiters = Waiters.GetOrAdd(agentId, _ => []);
        lock (waiters)
        {
            waiters.Add(waiter);
        }

        try
        {
            var completed = await Task.WhenAny(waiter.Task, Task.Delay(timeout, token));
            return completed == waiter.Task && waiter.Task.Result;
        }
        finally
        {
            if (Waiters.TryGetValue(agentId, out var current))
            {
                lock (current)
                {
                    current.Remove(waiter);
                    if (current.Count == 0)
                    {
                        Waiters.TryRemove(agentId, out _);
                    }
                }
            }
        }
    }

    private static void NotifyWaiters(string agentId, string eventType, string virtualUserId, string appSlot)
    {
        if (!Waiters.TryGetValue(agentId, out var waiters))
        {
            return;
        }

        ForwardedEventWaiter[] snapshot;
        lock (waiters)
        {
            snapshot = waiters.ToArray();
        }

        foreach (var waiter in snapshot)
        {
            if (waiter.Matches(eventType, virtualUserId, appSlot))
            {
                waiter.Complete();
            }
        }
    }

    public static void RecordEventResponse(string agentId, byte[] requestBodyBytes, int statusCode)
    {
        if (!Active.TryGetValue(agentId, out var buffer))
        {
            return;
        }

        var accepted = statusCode >= 200 && statusCode < 300;
        foreach (var eventType in ExtractEventTypes(requestBodyBytes))
        {
            buffer.RecordEventResponse(eventType, accepted);
        }
    }

    private static string[] ExtractEventTypes(byte[] requestBodyBytes)
    {
        try
        {
            using var document = JsonDocument.Parse(requestBodyBytes);
            var root = document.RootElement;
            var eventTypes = new List<string>();
            if (root.ValueKind == JsonValueKind.Object
                && root.TryGetProperty("events", out var events)
                && events.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in events.EnumerateArray())
                {
                    eventTypes.Add(GetEventType(item));
                }
            }
            else
            {
                eventTypes.Add(GetEventType(root));
            }

            return eventTypes.ToArray();
        }
        catch
        {
            return [];
        }
    }

    private static string GetEventType(JsonElement item)
    {
        if (item.ValueKind == JsonValueKind.Object
            && item.TryGetProperty("event_type", out var eventType)
            && eventType.ValueKind == JsonValueKind.String)
        {
            return eventType.GetString() ?? "-";
        }

        return "-";
    }

    private sealed class Registration(string agentId) : IDisposable
    {
        public void Dispose()
        {
            Active.TryRemove(agentId, out _);
            Waiters.TryRemove(agentId, out _);
        }
    }
}

internal sealed class ForwardedEventWaiter(string eventType, string virtualUserId, string appSlot)
{
    private readonly TaskCompletionSource<bool> _completion = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public Task<bool> Task => _completion.Task;

    public bool Matches(string incomingEventType, string incomingVirtualUserId, string incomingAppSlot)
    {
        return string.Equals(eventType, Normalize(incomingEventType), StringComparison.OrdinalIgnoreCase)
               && (string.Equals(virtualUserId, Normalize(incomingVirtualUserId), StringComparison.OrdinalIgnoreCase)
                   || string.Equals(appSlot, Normalize(incomingAppSlot), StringComparison.OrdinalIgnoreCase));
    }

    public void Complete()
    {
        _completion.TrySetResult(true);
    }

    private static string Normalize(string value)
    {
        return string.IsNullOrWhiteSpace(value) ? "-" : value.Trim();
    }
}
