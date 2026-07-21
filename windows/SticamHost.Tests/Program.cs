namespace SticamHost.Tests;

internal static class Program
{
    private static async Task<int> Main()
    {
        try
        {
            SecureProtocolTests.HandshakeDerivationMatchesAndroidVector();
            SecureProtocolTests.SequenceZeroRecordMatchesAndroidVector();
            await SecureProtocolTests.RejectsInvalidClientFinishAsync();
            H264ReceiverTests.ContainsIdrFindsThreeAndFourByteStartCodes();
            Console.WriteLine("All STICamHost deterministic tests passed.");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex);
            return 1;
        }
    }
}

internal static class TestAssert
{
    internal static void Equal<T>(T expected, T actual)
    {
        if (!EqualityComparer<T>.Default.Equals(expected, actual))
            throw new InvalidOperationException($"Expected '{expected}', got '{actual}'.");
    }

    internal static void True(bool value)
    {
        if (!value) throw new InvalidOperationException("Expected true, got false.");
    }

    internal static void False(bool value)
    {
        if (value) throw new InvalidOperationException("Expected false, got true.");
    }
}
