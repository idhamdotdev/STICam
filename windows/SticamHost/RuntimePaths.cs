using System;
using System.IO;

namespace SticamHost
{
    internal static class RuntimePaths
    {
        private static readonly string AppVersion =
            typeof(RuntimePaths).Assembly.GetName().Version?.ToString(3) ?? "unknown";

        public static readonly string RootDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "STICamHost",
            AppVersion);

        public static readonly string ToolsDirectory = Path.Combine(RootDirectory, "tools");
        public static readonly string FontsDirectory = Path.Combine(RootDirectory, "fonts");

        public static string? FindExecutable(string fileName)
        {
            string extracted = Path.Combine(ToolsDirectory, fileName);
            if (File.Exists(extracted)) return extracted;

            string bundled = Path.Combine(AppContext.BaseDirectory, "tools", fileName);
            if (File.Exists(bundled)) return bundled;

            string? path = Environment.GetEnvironmentVariable("PATH");
            if (string.IsNullOrWhiteSpace(path)) return null;

            foreach (string entry in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            {
                try
                {
                    string candidate = Path.Combine(entry.Trim().Trim('"'), fileName);
                    if (File.Exists(candidate)) return Path.GetFullPath(candidate);
                }
                catch
                {
                    // Ignore malformed PATH entries and continue searching.
                }
            }

            return null;
        }
    }
}
