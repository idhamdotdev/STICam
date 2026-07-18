using System;
using System.IO;
using System.Text.Json;

namespace SticamHost
{
    /// <summary>
    /// Tiny persisted host preferences, stored in %APPDATA%\STICamHost\settings.json.
    /// Loading is best-effort: a missing or corrupted file falls back to defaults.
    /// </summary>
    public static class HostSettings
    {
        private static readonly string Dir =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "STICamHost");
        private static readonly string FilePath = Path.Combine(Dir, "settings.json");

        /// <summary>Start connecting as soon as the host window opens.</summary>
        public static bool AutoConnect { get; set; } = true;

        static HostSettings()
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(FilePath));
                    if (doc.RootElement.TryGetProperty("autoConnect", out var v))
                        AutoConnect = v.GetBoolean();
                }
            }
            catch { /* fall back to defaults */ }
        }

        public static void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(new { autoConnect = AutoConnect }));
            }
            catch { /* best-effort */ }
        }
    }
}
