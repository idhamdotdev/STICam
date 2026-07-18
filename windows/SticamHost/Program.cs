using System;
using System.IO;
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
            Application.Run(new MainForm());
        }

        private static void ExtractResources()
        {
            try
            {
                string tempDir = Path.Combine(Path.GetTempPath(), "STICamHost");
                Directory.CreateDirectory(Path.Combine(tempDir, "tools"));
                Directory.CreateDirectory(Path.Combine(tempDir, "fonts"));

                var assembly = typeof(Program).Assembly;
                string[] resourceNames = assembly.GetManifestResourceNames();

                foreach (var name in resourceNames)
                {
                    if (name.EndsWith("adb.exe", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(tempDir, "tools", "adb.exe"));
                    else if (name.EndsWith("AdbWinApi.dll", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(tempDir, "tools", "AdbWinApi.dll"));
                    else if (name.EndsWith("AdbWinUsbApi.dll", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(tempDir, "tools", "AdbWinUsbApi.dll"));
                    else if (name.EndsWith("ffmpeg.exe", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(tempDir, "tools", "ffmpeg.exe"));
                    else if (name.EndsWith("obs-virtualcam-module64.dll", StringComparison.OrdinalIgnoreCase))
                    {
                        string targetPath = Path.Combine(tempDir, "tools", "obs-virtualcam-module64.dll");
                        ExtractResource(assembly, name, targetPath);
                        RegisterVirtualCamera(targetPath);
                    }
                    else if (name.EndsWith("Lalezar-Regular.ttf", StringComparison.OrdinalIgnoreCase))
                        ExtractResource(assembly, name, Path.Combine(tempDir, "fonts", "Lalezar-Regular.ttf"));
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to initialize STICam resources: {ex.Message}", "Initialization Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static void RegisterVirtualCamera(string dllPath)
        {
            try
            {
                // 0. Remove the legacy "Sticam Camera" registration left behind
                //    by the old register_sticam.ps1 — it made a second (ghost)
                //    camera appear next to "STICam Camera" in every app.
                Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(
                    @"Software\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{D77F129F-53C4-4959-8984-BB2996200234}",
                    throwOnMissingSubKey: false);

                // 1. COM CLSID Entry
                string clsidPath = @"Software\Classes\CLSID\{A3FCE0F5-3493-419F-958A-ABA1250EC20B}";
                using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(clsidPath))
                {
                    key.SetValue("", "STICam Camera");
                }
                using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(clsidPath + @"\InprocServer32"))
                {
                    key.SetValue("", dllPath);
                    key.SetValue("ThreadingModel", "Both");
                }

                // 2. DirectShow Category Instance Entry
                string instancePath = @"Software\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{A3FCE0F5-3493-419F-958A-ABA1250EC20B}";
                using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(instancePath))
                {
                    key.SetValue("FriendlyName", "STICam Camera");
                    key.SetValue("CLSID", "{A3FCE0F5-3493-419F-958A-ABA1250EC20B}");

                    // Standard FilterData for OBS Virtual Camera (DirectShow format)
                    byte[] filterData = new byte[] {
                        0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x30, 0x70, 0x69, 0x6e, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
                        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x31, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x65, 0x76, 0x79, 0x30, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                    };
                    key.SetValue("FilterData", filterData, Microsoft.Win32.RegistryValueKind.Binary);
                }
            }
            catch (Exception)
            {
                // Silent fail if registry writes fail (e.g. read-only permissions in sandboxes)
            }
        }

        private static void ExtractResource(System.Reflection.Assembly assembly, string resourceName, string targetPath)
        {
            try
            {
                if (File.Exists(targetPath))
                {
                    return;
                }

                using (var stream = assembly.GetManifestResourceStream(resourceName))
                {
                    if (stream == null) return;
                    using (var fileStream = File.Create(targetPath))
                    {
                        stream.CopyTo(fileStream);
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Failed to extract {resourceName}: {ex.Message}");
            }
        }
    }
}
