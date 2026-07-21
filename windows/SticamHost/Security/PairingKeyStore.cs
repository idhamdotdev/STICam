using System.Security.Cryptography;
using System.Text;

namespace SticamHost.Security;

public static class PairingKeyStore
{
    private const string MutexName = @"Local\STICamHost.PairingKeyStore.v2";
    private static readonly object Gate = new();
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("STICamHost pairing key v2");
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "STICamHost");
    private static readonly string FilePath = Path.Combine(DirectoryPath, "pairing.key");

    public static string GetOrCreate() => WithExclusiveAccess(() =>
    {
        Directory.CreateDirectory(DirectoryPath);
        if (File.Exists(FilePath)) return ReadProtectedKey();

        string key = GenerateKey();
        WriteProtectedKey(key);
        return key;
    });

    public static string Rotate() => WithExclusiveAccess(() =>
    {
        Directory.CreateDirectory(DirectoryPath);
        string key = GenerateKey();
        WriteProtectedKey(key);
        return key;
    });

    private static T WithExclusiveAccess<T>(Func<T> action)
    {
        lock (Gate)
        {
            using var mutex = new Mutex(initiallyOwned: false, MutexName);
            bool acquired = false;
            try
            {
                try { acquired = mutex.WaitOne(TimeSpan.FromSeconds(5)); }
                catch (AbandonedMutexException) { acquired = true; }
                if (!acquired) throw new IOException("Timed out while opening the STICam pairing key.");
                return action();
            }
            finally
            {
                if (acquired) mutex.ReleaseMutex();
            }
        }
    }

    private static string ReadProtectedKey()
    {
        string stored = File.ReadAllText(FilePath, Encoding.UTF8).Trim();
        if (stored.Length == 0)
            throw new InvalidDataException("The stored pairing key is empty or truncated.");

        // Migrate the original plaintext development format on first read.
        if (IsValid(stored))
        {
            string migrated = stored.ToUpperInvariant();
            WriteProtectedKey(migrated);
            return migrated;
        }

        try
        {
            byte[] protectedBytes = Convert.FromBase64String(stored);
            byte[] clearBytes = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            try
            {
                string key = Encoding.ASCII.GetString(clearBytes).Trim().ToUpperInvariant();
                if (!IsValid(key)) throw new InvalidDataException("The stored pairing key is invalid.");
                return key;
            }
            finally
            {
                CryptographicOperations.ZeroMemory(clearBytes);
            }
        }
        catch (Exception ex) when (ex is FormatException or CryptographicException or ArgumentException)
        {
            throw new InvalidDataException(
                "The stored pairing key cannot be decrypted. Rotate it explicitly to recover.",
                ex);
        }
    }

    private static void WriteProtectedKey(string key)
    {
        byte[] clearBytes = Encoding.ASCII.GetBytes(key);
        string temporary = FilePath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            byte[] protectedBytes = ProtectedData.Protect(clearBytes, Entropy, DataProtectionScope.CurrentUser);
            File.WriteAllText(temporary, Convert.ToBase64String(protectedBytes), Encoding.UTF8);
            File.Move(temporary, FilePath, overwrite: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clearBytes);
            try { if (File.Exists(temporary)) File.Delete(temporary); } catch { }
        }
    }

    private static string GenerateKey() => Convert.ToHexString(RandomNumberGenerator.GetBytes(16));

    private static bool IsValid(string value) =>
        value.Length == 32 && value.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F' or >= 'a' and <= 'f');
}
