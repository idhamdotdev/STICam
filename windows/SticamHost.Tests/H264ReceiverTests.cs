using SticamHost.Stream;

namespace SticamHost.Tests;

internal static class H264ReceiverTests
{
    internal static void ContainsIdrFindsThreeAndFourByteStartCodes()
    {
        TestAssert.True(H264Receiver.ContainsIdr(new byte[] { 0, 0, 1, 0x65, 1 }));
        TestAssert.True(H264Receiver.ContainsIdr(new byte[] { 0, 0, 0, 1, 0x65, 1 }));
        TestAssert.False(H264Receiver.ContainsIdr(new byte[] { 0, 0, 0, 1, 0x61, 1 }));
    }
}
