using System.Security.Cryptography;
using System.Security;
using System.Text;
using System.Net;
using System.Net.Sockets;
using SticamHost.Security;

namespace SticamHost.Tests;

internal static class SecureProtocolTests
{
    internal static void HandshakeDerivationMatchesAndroidVector()
    {
        byte[] clientRandom = Convert.FromHexString("000102030405060708090A0B0C0D0E0F");
        byte[] serverRandom = Convert.FromHexString("F0F1F2F3F4F5F6F7F8F9FAFBFCFDFEFF");
        byte[] baseKey = SecureProtocolCrypto.DeriveBaseKey("00112233445566778899AABBCCDDEEFF");

        TestAssert.Equal("0A95E6086DADB687E6173045F32E65CFBF036844891D9ECDB7440E8121D1B142", Convert.ToHexString(baseKey));
        byte[] clientProof = SecureProtocolCrypto.Hmac(
            baseKey,
            SecureProtocolCrypto.ClientLabel,
            clientRandom);
        TestAssert.Equal(
            "B3E5261EE8D0A6C71077286C9EE82F69140EB3A3141FEA60A21983195D5E4424",
            Convert.ToHexString(clientProof));
        byte[] serverProof = SecureProtocolCrypto.Hmac(
            baseKey,
            SecureProtocolCrypto.ServerLabel,
            clientRandom,
            serverRandom);
        TestAssert.Equal(
            "1436CEEF1C7F7250DCDCF51B2EFEF3EABCD5FEFCFEFFD19482445714DF1470F2",
            Convert.ToHexString(serverProof));

        byte[] session = SecureProtocolCrypto.Hmac(
            baseKey,
            SecureProtocolCrypto.SessionLabel,
            clientRandom,
            serverRandom);
        TestAssert.Equal(
            "486E18028CCDD51CEB66D634491332B50ACC500E63148AAE902B8A6076ED7A98",
            Convert.ToHexString(SecureProtocolCrypto.Hmac(
                session,
                SecureProtocolCrypto.ClientFinishLabel,
                SecureProtocolCrypto.Magic,
                clientRandom,
                serverRandom,
                clientProof,
                serverProof)));
        TestAssert.Equal(
            "A0EBD5DEA4E3E4B4AC8E3A483FC7C1F9F00CAA01761976A0C315D41383BD1882",
            Convert.ToHexString(SecureProtocolCrypto.Hmac(session, SecureProtocolCrypto.ClientToServerLabel)));
        TestAssert.Equal(
            "3577699EE9BD14B3685E07DAC1407A89F3E98DA6ADA5F28E136DF624CDA1660A",
            Convert.ToHexString(SecureProtocolCrypto.Hmac(session, SecureProtocolCrypto.ServerToClientLabel)));
    }

    internal static void SequenceZeroRecordMatchesAndroidVector()
    {
        byte[] key = Convert.FromHexString("A0EBD5DEA4E3E4B4AC8E3A483FC7C1F9F00CAA01761976A0C315D41383BD1882");
        byte[] payload = Encoding.UTF8.GetBytes("{\"cmd\":\"request_idr\"}");
        byte[] plain = new byte[5 + payload.Length];
        plain[0] = 0x10;
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(plain.AsSpan(1), payload.Length);
        payload.CopyTo(plain, 5);
        byte[] encrypted = new byte[plain.Length];
        byte[] tag = new byte[16];
        using var aes = new AesGcm(key, 16);
        aes.Encrypt(SecureProtocolCrypto.Nonce(0), plain, encrypted, tag, SecureProtocolCrypto.Aad(0));

        TestAssert.Equal(
            "DC6E5984A336E8A4377A92E6FA9D9592901C74E4A032FDECB37A",
            Convert.ToHexString(encrypted));
        TestAssert.Equal("E1C6E80E4A765FA102F25413AE7081A4", Convert.ToHexString(tag));
    }

    internal static async Task RejectsInvalidClientFinishAsync()
    {
        const string pairingKey = "00112233445566778899AABBCCDDEEFF";
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));

        Task server = Task.Run(async () =>
        {
            using TcpClient accepted = await listener.AcceptTcpClientAsync(timeout.Token);
            try
            {
                await using SecureChannel channel = await SecureChannel.AcceptAsync(
                    accepted.GetStream(),
                    pairingKey,
                    timeout.Token);
                throw new InvalidOperationException("Invalid client-finish proof was accepted.");
            }
            catch (SecurityException)
            {
                // Expected: a captured hello cannot finish a fresh transcript.
            }
        }, timeout.Token);

        using var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
        NetworkStream stream = client.GetStream();
        byte[] clientRandom = Convert.FromHexString("000102030405060708090A0B0C0D0E0F");
        byte[] baseKey = SecureProtocolCrypto.DeriveBaseKey(pairingKey);
        byte[] clientProof = SecureProtocolCrypto.Hmac(
            baseKey,
            SecureProtocolCrypto.ClientLabel,
            clientRandom);
        await stream.WriteAsync(SecureProtocolCrypto.Magic, timeout.Token);
        await stream.WriteAsync(clientRandom, timeout.Token);
        await stream.WriteAsync(clientProof, timeout.Token);
        await stream.FlushAsync(timeout.Token);

        byte[] serverChallenge = new byte[48];
        await stream.ReadExactlyAsync(serverChallenge, timeout.Token);
        await stream.WriteAsync(new byte[32], timeout.Token);
        await stream.FlushAsync(timeout.Token);
        await server;
    }
}
