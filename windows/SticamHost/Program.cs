using System;
using System.IO;
using System.Security.Cryptography;
using System.Windows.Forms;

namespace SticamHost
{
    internal static class Program
    {
        [STAThread]
        static void Main()
        {
            ExtractResources();

            Application.SetHighDpiMode(HighDpiMode.SystemAware);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            try
            {
                Application.Run(new MainForm());
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    $"STICam could not start: {ex.Message}",
                    "Startup Error",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
        }

        private static void ExtractResources()
        {
            try
            {
                Directory.CreateDirectory(RuntimePaths.ToolsDirectory);
                Directory.CreateDirectory(RuntimePaths.FontsDirectory);

                var assembly = typeof(Program).Assembly;
                string[] resourceNames = assembly.GetManifestResourceNames();

                foreach (var name in resourceNames)
                {
                    if (name.EndsWith("adb.exe", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(RuntimePaths.ToolsDirectory, "adb.exe"));
                    else if (name.EndsWith("AdbWinApi.dll", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(RuntimePaths.ToolsDirectory, "AdbWinApi.dll"));
                    else if (name.EndsWith("AdbWinUsbApi.dll", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(RuntimePaths.ToolsDirectory, "AdbWinUsbApi.dll"));
                    else if (name.EndsWith("ffmpeg.exe", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(RuntimePaths.ToolsDirectory, "ffmpeg.exe"));
                    else if (name.EndsWith("Lalezar-Regular.ttf", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(RuntimePaths.FontsDirectory, "Lalezar-Regular.ttf"));
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to initialize STICam resources: {ex.Message}", "Initialization Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static void ExtractResource(System.Reflection.Assembly assembly, string resourceName, string targetPath)
        {
            using var stream = assembly.GetManifestResourceStream(resourceName)
                ?? throw new InvalidOperationException($"Embedded resource not found: {resourceName}");
            using var content = new MemoryStream();
            stream.CopyTo(content);
            byte[] bytes = content.ToArray();
            byte[] expectedHash = SHA256.HashData(bytes);

            if (File.Exists(targetPath))
            {
                try
                {
                    using var existing = File.OpenRead(targetPath);
                    if (CryptographicOperations.FixedTimeEquals(expectedHash, SHA256.HashData(existing)))
                        return;
                }
                catch (IOException)
                {
                    // Replace unreadable or partially-written files below.
                }
            }

            Directory.CreateDirectory(Path.GetDirectoryName(targetPath)
                ?? throw new InvalidOperationException("Resource target has no directory."));
            string temporaryPath = targetPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                using (var file = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                {
                    file.Write(bytes);
                    file.Flush(flushToDisk: true);
                }
                File.Move(temporaryPath, targetPath, overwrite: true);
            }
            finally
            {
                try { File.Delete(temporaryPath); } catch { }
            }
        }
    }
}
