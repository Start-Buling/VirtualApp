namespace IdlefishDeviceAgent;

internal static class DeploymentEnvironment
{
    public static void Load(string path)
    {
        if (!File.Exists(path)) return;
        foreach (var line in File.ReadLines(path))
        {
            var entry = line.Trim();
            if (entry.Length == 0 || entry.StartsWith('#')) continue;
            var separator = entry.IndexOf('=');
            if (separator <= 0) throw new ArgumentException("Invalid .env entry. Expected KEY=value.");
            var key = entry[..separator].Trim();
            if (key.Length == 0 || !(char.IsAsciiLetter(key[0]) || key[0] == '_')
                || key.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '_'))
                throw new ArgumentException("Invalid .env variable name.");
            var value = entry[(separator + 1)..].Trim();
            if (value.Length >= 2 && ((value[0] == '\"' && value[^1] == '\"')
                || (value[0] == '\'' && value[^1] == '\''))) value = value[1..^1];
            if (Environment.GetEnvironmentVariable(key) == null)
                Environment.SetEnvironmentVariable(key, value);
        }
    }
}
